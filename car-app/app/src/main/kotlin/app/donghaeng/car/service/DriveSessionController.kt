package app.donghaeng.car.service

import android.content.Context
import android.os.Build
import app.donghaeng.car.core.common.DhLog
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
 * 백그라운드에서 포그라운드 서비스 시작이 막히면(API 31+ `ForegroundServiceStartNotAllowedException`) 로그만 남기고
 * 다음 세션 변화 때 다시 시도한다 — 수집·발행은 서비스와 무관하게 싱글턴이 계속한다.
 */
class DriveSessionController(
    private val context: Context,
    private val session: Flow<GroupSession?>,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val started = AtomicBoolean(false)

    private val _serviceRunning = MutableStateFlow(false)

    /** 서비스 시작 요청이 성공한 상태(진단용) */
    val serviceRunning: StateFlow<Boolean> get() = _serviceRunning

    fun start() {
        if (!started.compareAndSet(false, true)) return
        session.distinctUntilChangedBy { it?.groupId }
            .onEach { s -> if (s != null) startService(s) else stopService() }
            .launchIn(scope)
    }

    /**
     * 권한 다이얼로그 결과 뒤(앱이 앞에 있을 때) 서비스를 다시 시작해 포그라운드 타입을 새 권한에 맞춘다 —
     * 위치 승인 → location, 마이크 승인 → microphone(F16 무전 녹음이 백그라운드에서도 무음이 되지 않게). 그룹이 없으면 아무것도 안 한다.
     */
    fun refreshType(session: GroupSession?) {
        if (session == null || !_serviceRunning.value) return
        startService(session)
    }

    private fun startService(s: GroupSession) {
        val intent = DriveSessionService.intent(context)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
            _serviceRunning.value = true
            DhLog.i("drive session service: start 요청 (group=${s.name.take(20)})")
        } catch (e: Exception) {
            _serviceRunning.value = false
            DhLog.w("drive session service: 시작 실패 ${e.javaClass.simpleName}: ${e.message?.take(100)}")
        }
    }

    private fun stopService() {
        if (!_serviceRunning.value) return
        runCatching { context.stopService(DriveSessionService.intent(context)) }
            .onFailure { DhLog.w("drive session service: 종료 실패", it) }
        _serviceRunning.value = false
        DhLog.i("drive session service: stop 요청 (그룹 없음)")
    }
}

