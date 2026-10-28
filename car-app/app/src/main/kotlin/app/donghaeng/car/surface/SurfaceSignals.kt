package app.donghaeng.car.surface

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 오버레이(SYSTEM_ALERT_WINDOW) 허용 여부 감시 — 앱 시작·포그라운드 복귀·[intervalMs](5초)마다 다시 확인한다.
 * Pleos 에는 설정 화면이 없어 `adb shell appops set --user 10 app.donghaeng.car SYSTEM_ALERT_WINDOW allow` 로만 바뀌므로(docs/10 R16) 주기 확인이 필요하다.
 */
class OverlayPermissionMonitor(
    private val check: () -> Boolean,
    private val intervalMs: Long = INTERVAL_MS,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var job: Job? = null
    private var checked = false

    private val _canDraw = MutableStateFlow(false)
    val canDraw: StateFlow<Boolean> = _canDraw.asStateFlow()

    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        recheck()
        job = scope.launch {
            while (isActive) {
                delay(intervalMs)
                recheck()
            }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel(); job = null
    }

    fun recheck() {
        val v = runCatching(check).getOrDefault(false)
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
 * 동행 화면(MainActivity)이 전면(resumed)인지 추적 — 전면이면 플로팅 패널을 숨긴다(같은 정보를 대시보드가 보여 준다).
 * Application 에 한 번 등록한다(DriveSurfaceController). 포그라운드 복귀 때 [onResume] 으로 오버레이 권한을 다시 확인한다.
 */
class AppForegroundTracker : Application.ActivityLifecycleCallbacks {
    private val _appResumed = MutableStateFlow(false)
    val appResumed: StateFlow<Boolean> = _appResumed.asStateFlow()

    @Volatile
    var onResume: (() -> Unit)? = null

    override fun onActivityResumed(activity: Activity) {
        if (activity is MainActivity) _appResumed.value = true
        onResume?.invoke()
    }

    override fun onActivityPaused(activity: Activity) {
        if (activity is MainActivity) _appResumed.value = false
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}

/** [내비 보기] — 순정 내비를 앞으로. 지도 앱 selector 로 시도하고 실패하면 false(호출 쪽이 NaviHelper 화면 요청으로 폴백) */
object NaviLauncher {
    fun bringToFront(context: Context): Boolean {
        val intent = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MAPS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            DhLog.i("surface: 내비 보기 → APP_MAPS selector")
            true
        } catch (e: Exception) {
            DhLog.w("surface: 내비 보기 APP_MAPS 실패 ${e.javaClass.simpleName} — NaviHelper 화면 요청으로 폴백")
            false
        }
    }
}
