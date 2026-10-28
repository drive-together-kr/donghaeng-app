package app.donghaeng.car.surface

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import app.donghaeng.car.MainActivity
import app.donghaeng.car.core.common.DhLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * 오버레이(SYSTEM_ALERT_WINDOW) 허용 여부 감시 — 앱 시작·포그라운드 복귀 때 다시 확인하고, **그룹이 있는 동안([active])만** [intervalMs](5초)마다 확인한다.
 * Pleos 에는 설정 화면이 없어 `adb shell appops set --user 10 app.donghaeng.car SYSTEM_ALERT_WINDOW allow` 로만 바뀌므로(docs/10 R16) 주기 확인이 필요하다.
 * 그룹이 없으면(표면 OFF) 결과를 쓸 데가 없으므로 5초 주기 바인더 호출을 하지 않는다.
 */
class OverlayPermissionMonitor(
    private val check: () -> Boolean,
    private val active: Flow<Boolean> = flowOf(true),
    private val intervalMs: Long = INTERVAL_MS,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var job: Job? = null
    private var checked = false

    private val _canDraw = MutableStateFlow(false)
    val canDraw: StateFlow<Boolean> = _canDraw.asStateFlow()

    /** 지금까지 확인한 횟수(진단·테스트) */
    @Volatile
    var checks: Int = 0
        private set

    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        recheck()
        job = scope.launch {
            active.distinctUntilChanged().collectLatest { on ->
                if (!on) return@collectLatest
                recheck() // 그룹이 생기면 바로 한 번
                while (true) {
                    delay(intervalMs)
                    recheck()
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel(); job = null
    }

    fun recheck() {
        val v = runCatching(check).getOrDefault(false)
        checks++
        if (!checked || v != _canDraw.value) DhLog.i("surface: overlay permission canDraw=$v")
        checked = true
        _canDraw.value = v
    }

    companion object {
        const val INTERVAL_MS = 5_000L

        fun androidCheck(context: Context): () -> Boolean {
            val app = context.applicationContext
            return { Settings.canDrawOverlays(app) }
        }

        /** 오버레이 권한 설정 화면 인텐트. 기기에 그 화면이 없으면 null — 버튼을 숨긴다(Pleos AVD 는 설정 화면이 없다, docs/10 R16) */
        fun settingsIntent(context: Context): Intent? {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
            return runCatching { intent.resolveActivity(context.packageManager) }.getOrNull()?.let { intent }
        }
    }
}

/**
 * 동행 화면(MainActivity)이 **보이는지**(started~stopped) 추적 — 보이면 플로팅 패널을 숨긴다(같은 정보를 대시보드가 보여 준다).
 * resumed 가 아니라 started 로 본다: 정차 중 위치 권한 다이얼로그처럼 화면은 보이지만 paused 인 동안 패널이 동행 화면·다이얼로그 위에 뜨지 않게.
 * Application 에 한 번 등록한다(DriveSurfaceController). 포그라운드 복귀 때 [onResume] 으로 오버레이 권한을 다시 확인한다. 콜백은 메인 스레드에서 온다.
 */
class AppForegroundTracker : Application.ActivityLifecycleCallbacks {
    private val _appVisible = MutableStateFlow(false)
    val appVisible: StateFlow<Boolean> = _appVisible.asStateFlow()

    /** started 상태인 MainActivity 수(재생성 때 잠깐 0 → 1, 표시 지연 0.6 s 가 깜빡임을 흡수) */
    private var started = 0

    @Volatile
    var onResume: (() -> Unit)? = null

    override fun onActivityStarted(activity: Activity) {
        if (activity is MainActivity) {
            started++
            _appVisible.value = started > 0
        }
    }

    override fun onActivityStopped(activity: Activity) {
        if (activity is MainActivity) {
            started = (started - 1).coerceAtLeast(0)
            _appVisible.value = started > 0
        }
    }

    override fun onActivityResumed(activity: Activity) {
        onResume?.invoke()
    }

    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}

/**
 * [내비 보기] — 순정 내비를 앞으로. 엉뚱한 지도 앱이나 선택 창이 뜨지 않게 순서를 정한다([choose], 순수 함수):
 * 1) 순정 내비 패키지([STOCK_NAVI_PACKAGES])의 실행 인텐트가 있으면 그것(기존 작업이 앞으로 온다)
 * 2) `CATEGORY_APP_MAPS` selector 가 순정 내비로 풀리면 그것
 * 3) pleos 빌드면 false → 호출 쪽이 NaviHelper(`NaviAdapter.showNavigation()`)로 폴백 — 다른 지도 앱으로 새지 않게
 * 4) fake 빌드(일반 에뮬레이터)는 selector 가 **한 앱으로** 풀릴 때만(선택 창 = 패키지 "android" → false)
 */
object NaviLauncher {
    /** Pleos 순정 내비(docs/10 R4 실측 패키지) */
    val STOCK_NAVI_PACKAGES = listOf("ai.umos.maps.android.navigation.app")

    /** 선택 창(ResolverActivity)으로 풀렸을 때의 패키지 */
    private const val RESOLVER_PACKAGE = "android"

    enum class Route { STOCK_PACKAGE, MAPS_SELECTOR, NAVI_HELPER }

    fun choose(stockLaunchable: Boolean, mapsResolvedPackage: String?, pleosBuild: Boolean): Route = when {
        stockLaunchable -> Route.STOCK_PACKAGE
        mapsResolvedPackage != null && mapsResolvedPackage in STOCK_NAVI_PACKAGES -> Route.MAPS_SELECTOR
        pleosBuild -> Route.NAVI_HELPER
        mapsResolvedPackage != null && mapsResolvedPackage != RESOLVER_PACKAGE -> Route.MAPS_SELECTOR
        else -> Route.NAVI_HELPER
    }

    /** 앞으로 올렸으면 true, false 면 호출 쪽이 NaviHelper 화면 요청으로 폴백 */
    fun bringToFront(context: Context, pleosBuild: Boolean): Boolean {
        val pm = context.packageManager
        val stock = STOCK_NAVI_PACKAGES.firstNotNullOfOrNull { runCatching { pm.getLaunchIntentForPackage(it) }.getOrNull() }
        val selector = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MAPS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val mapsPkg = if (stock != null) null else runCatching { pm.resolveActivity(selector, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName }.getOrNull()
        val route = choose(stock != null, mapsPkg, pleosBuild)
        val intent = when (route) {
            Route.STOCK_PACKAGE -> stock!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            Route.MAPS_SELECTOR -> selector
            Route.NAVI_HELPER -> {
                DhLog.i("surface: 내비 보기 → NaviHelper 폴백 (순정 내비 실행 인텐트 없음, maps=${mapsPkg ?: "없음"})")
                return false
            }
        }
        return try {
            context.startActivity(intent)
            DhLog.i("surface: 내비 보기 → $route")
            true
        } catch (e: Exception) {
            DhLog.w("surface: 내비 보기 $route 실패 ${e.javaClass.simpleName} — NaviHelper 화면 요청으로 폴백")
            false
        }
    }
}
