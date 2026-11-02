package app.donghaeng.car.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import app.donghaeng.car.core.common.DhLog
import app.donghaeng.car.core.common.LocationPermissions
import app.donghaeng.car.core.common.TextMasking
import app.donghaeng.car.domain.session.GroupSession
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 그룹 세션 유무 → [DriveSessionService] 시작/종료 (T23). Application 에서 한 번 [start].
 *
 * 백그라운드에서 포그라운드 서비스 시작이 막히면(API 31+ `ForegroundServiceStartNotAllowedException` — 오버레이 권한 없는 실차에서 폰 합류·
 * START_STICKY 재시작) 로그만 남기고, **동행 화면이 보이는 순간**([ensureType]·[refreshType], MainActivity.onResume·권한 결과) 다시 시작한다(A003).
 * 예전엔 '다음 세션 변화 때' 만 다시 시도해, 같은 그룹 안에서는 그룹이 끝날 때까지 FGS 없이 돌았다. 마지막 실패는 [lastStartFailure] 로 보인다.
 * 그룹이 끝나면 [serviceRunning] 과 상관없이 늘 `stopService` 를 부른다(멱등 — 시스템이 sticky 로 다시 띄운 서비스도 멈춘다).
 * 수집·발행은 서비스와 무관하게 싱글턴이 계속한다.
 *
 * 타입 갱신([refreshType]·[ensureType])은 **동행 화면이 보일 때만** 한다([appVisible]) — 백그라운드에서 다시 시작하면 location·microphone 이
 * SecurityException 으로 빠져 오히려 dataSync 로 떨어진다(내비가 앞일 때 위치·무전 녹음이 끊긴다). 이미 도는 서비스의 갱신이 실패해도
 * [serviceRunning] 은 그대로 둔다(옛 서비스는 계속 돌고, 그룹이 끝나면 멈춰야 한다).
 */
class DriveSessionController(
    private val context: Context,
    private val session: Flow<GroupSession?>,
    /** 동행 화면(MainActivity)이 보이는가 — AppForegroundTracker.appVisible(started). 보일 때만 while-in-use 타입(location·microphone)이 붙는다 */
    private val appVisible: () -> Boolean = { true },
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val started = AtomicBoolean(false)

    private val _serviceRunning = MutableStateFlow(false)

    /** 서비스 시작 요청이 성공한 상태(진단용) */
    val serviceRunning: StateFlow<Boolean> get() = _serviceRunning

    private val _lastStartFailure = MutableStateFlow<String?>(null)

    /** 마지막 시작 실패 사유(예외 이름, 진단·health 줄용). 시작에 성공하면 null */
    val lastStartFailure: StateFlow<String?> get() = _lastStartFailure

    fun start() {
        if (!started.compareAndSet(false, true)) return
        session.distinctUntilChangedBy { it?.groupId }
            .onEach { s -> if (s != null) startService(s) else stopService() }
            .launchIn(scope)
    }

    /** [refreshType]·[ensureType] 결과(로그·디버그용) */
    enum class TypeRefresh { REQUESTED, NOT_NEEDED, NO_SESSION, DEFERRED_BACKGROUND, FAILED }

    /**
     * 권한 다이얼로그 결과 뒤(앱이 앞에 있을 때) 서비스를 다시 시작해 포그라운드 타입을 새 권한에 맞춘다 —
     * 위치 승인 → location, 마이크 승인 → microphone(F16 무전 녹음이 백그라운드에서도 무음이 되지 않게). 그룹이 없으면 아무것도 안 한다.
     * 동행 화면이 안 보이면(디버그 브로드캐스트 등 백그라운드) 하지 않는다 — 화면이 앞에 오면 [ensureType] 이 붙인다.
     */
    fun refreshType(session: GroupSession?, reason: String = "permission"): TypeRefresh {
        if (session == null) return TypeRefresh.NO_SESSION
        if (!_serviceRunning.value) return retryStart(session, reason)
        if (!appVisible()) {
            DhLog.i("drive session service: 타입 갱신 미룸 reason=$reason — 앱이 백그라운드(location·microphone 이 막힌다). 동행 화면이 앞에 오면 붙인다")
            return TypeRefresh.DEFERRED_BACKGROUND
        }
        return if (startService(session, refresh = true)) TypeRefresh.REQUESTED else TypeRefresh.FAILED
    }

    /**
     * 동행 화면이 앞에 올 때마다(MainActivity.onResume) 부른다 — 지금 붙은 타입([DriveSessionService.attachedType])이 권한으로 붙일 수 있는 타입보다
     * 모자라면(백그라운드에서 시작·재시작돼 microphone·location 이 빠졌다) 다시 시작해 올린다. 권한이 '새로 생긴' 순간이 아니어도 맞춘다.
     */
    fun ensureType(session: GroupSession?): TypeRefresh {
        if (session == null) return TypeRefresh.NO_SESSION
        if (!_serviceRunning.value) return retryStart(session, "resume")
        val attached = DriveSessionService.attachedType.value
        val fine = LocationPermissions.fineGranted(context)
        val mic = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!DriveSessionService.needsUpgrade(attached, fine, mic, Build.VERSION.SDK_INT)) return TypeRefresh.NOT_NEEDED
        DhLog.i("drive session service: 붙은 타입 ${attached?.let { DriveSessionService.typeName(it) } ?: "none"} < 권한(location=$fine mic=$mic) → 다시 시작")
        return refreshType(session, reason = "resume")
    }

    /**
     * A003: 그룹은 있는데 서비스가 없다(백그라운드 시작이 막혔다) — 동행 화면이 보이면 지금 다시 시작한다(앱이 앞이면 FGS 시작이 허용된다).
     * 안 보이면 미룬다(다음 onResume·권한 결과).
     */
    private fun retryStart(session: GroupSession, reason: String): TypeRefresh {
        if (!appVisible()) {
            DhLog.i("drive session service: 시작 재시도 미룸 reason=$reason — 앱이 백그라운드")
            return TypeRefresh.DEFERRED_BACKGROUND
        }
        DhLog.i("drive session service: 서비스 없음(이전 시작 실패=${_lastStartFailure.value ?: "-"}) → 다시 시작 reason=$reason")
        return if (startService(session)) TypeRefresh.REQUESTED else TypeRefresh.FAILED
    }

    /** 시작(또는 타입 갱신) 요청 — 성공하면 true. [refresh] 면 이미 도는 서비스라 실패해도 [serviceRunning] 을 내리지 않는다 */
    private fun startService(s: GroupSession, refresh: Boolean = false): Boolean {
        val intent = DriveSessionService.intent(context)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
            _serviceRunning.value = true
            _lastStartFailure.value = null
            // 규칙 8: 그룹 이름(기본값에 방장 닉네임)은 남기지 않는다(A090)
            DhLog.i("drive session service: ${if (refresh) "타입 갱신" else "start"} 요청 (groupId=${TextMasking.maskVehicleId(s.groupId)})")
            true
        } catch (e: Exception) {
            if (!refresh) {
                _serviceRunning.value = false
                _lastStartFailure.value = e.javaClass.simpleName
            }
            DhLog.w("drive session service: ${if (refresh) "타입 갱신" else "시작"} 실패 ${e.javaClass.simpleName}: ${e.message?.take(100)}")
            false
        }
    }

    /** 그룹 없음 → 늘 멈춤 요청(멱등). 시작이 실패했던 것으로 알아도 시스템이 sticky 로 다시 띄웠을 수 있다(A003) */
    private fun stopService() {
        val wasRunning = _serviceRunning.value
        runCatching { context.stopService(DriveSessionService.intent(context)) }
            .onFailure { DhLog.w("drive session service: 종료 실패", it) }
        _serviceRunning.value = false
        _lastStartFailure.value = null
        if (wasRunning) DhLog.i("drive session service: stop 요청 (그룹 없음)")
    }
}

