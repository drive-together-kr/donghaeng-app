package app.donghaeng.car.surface

import android.app.Application
import android.content.Intent
import app.donghaeng.car.core.common.DhLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 주행 중 표면 배선(싱글턴, Application 에서 한 번 [start], docs/05 §5.2).
 *
 * 1) Activity 생명주기 추적 등록 → 동행 화면이 보이는지, 포그라운드 복귀 때 오버레이 권한 재확인
 * 2) 오버레이 권한 확인(그룹이 있는 동안 5초 주기), [SurfaceState]·[EventCardNotifier] 시작
 * 3) [PanelKeeper]: [SurfaceState.panelVisible] 이 true 면 [FloatingPanelService] 를 시작하고 창이 실제로 붙을 때까지 다시 시도, false 면 종료.
 *    잠깐의 일시정지(알림 창 등)로 패널이 깜빡이지 않게 표시만 [SHOW_DELAY_MS] 늦춘다.
 *
 * 로그: `adb logcat -s DongHaeng | grep surface` — `surface: mode=PANEL`, `surface: panel start/stop`, `surface panel: shown corner=TOP_END …`, `surface: card …`.
 */
class DriveSurfaceController(
    private val app: Application,
    private val state: SurfaceState,
    private val overlay: OverlayPermissionMonitor,
    private val tracker: AppForegroundTracker,
    private val cards: EventCardNotifier,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val started = AtomicBoolean(false)

    private val keeper = PanelKeeper(
        visible = state.panelVisible,
        shown = FloatingPanelService.shownState,
        start = ::startPanel,
        stop = ::stopPanel,
    )

    fun start() {
        if (!started.compareAndSet(false, true)) return
        app.registerActivityLifecycleCallbacks(tracker)
        tracker.onResume = { overlay.recheck() }
        overlay.start()
        state.start()
        cards.start()
        scope.launch { state.mode.collect { DhLog.i("surface: mode=$it canDraw=${overlay.canDraw.value}") } }
        scope.launch { keeper.run() }
    }

    /** 실패하면 예외 — [PanelKeeper] 가 기록하고 다시 시도한다(백그라운드 시작 제한 IllegalStateException 등) */
    private fun startPanel(attempt: Int) {
        app.startService(Intent(app, FloatingPanelService::class.java))
        DhLog.i("surface: panel start 요청 attempt=$attempt (${state.describe()})")
    }

    private fun stopPanel() {
        val wasShown = FloatingPanelService.shown
        runCatching { app.stopService(Intent(app, FloatingPanelService::class.java)) }
            .onFailure { DhLog.w("surface: panel 종료 실패 ${it.javaClass.simpleName}") }
        if (wasShown) DhLog.i("surface: panel stop 요청")
    }

    /** 디버그 리시버 `surface` 액션이 찍는 한 줄 */
    fun describe(): String = "${state.describe()} panelShown=${FloatingPanelService.shown} keeper=${keeper.status.value} cards=${cards.state.value}"

    companion object {
        const val SHOW_DELAY_MS = PanelKeeper.SHOW_DELAY_MS
    }
}

/**
 * 패널 서비스 유지 — 순수 코루틴 로직(단위 테스트 `PanelKeeperTest`).
 *
 * [visible] 이 true 이고 [shown](창이 실제로 붙음)이 false 면 [showDelayMs] 뒤 [start] 하고, 그래도 붙지 않으면 [retryBaseMs] 부터 두 배씩(최대 [retryMaxMs]) 기다려 다시 시작한다.
 * 백그라운드 시작 제한(startService 의 IllegalStateException — DriveSessionService 가 FGS 승격에 실패한 경우)·서비스 안 addView 실패 뒤 stopSelf·
 * 시스템의 유휴 서비스 정리가 모두 이 경로로 회복된다. [visible] 이 false 면 [stop]. 시도·실패는 [status] 에 남긴다(디버그 `surface`).
 */
class PanelKeeper(
    private val visible: Flow<Boolean>,
    private val shown: Flow<Boolean>,
    private val start: (attempt: Int) -> Unit,
    private val stop: () -> Unit,
    private val showDelayMs: Long = SHOW_DELAY_MS,
    private val retryBaseMs: Long = RETRY_BASE_MS,
    private val retryMaxMs: Long = RETRY_MAX_MS,
) {
    data class Status(val starts: Int = 0, val retries: Int = 0, val failures: Int = 0, val lastFailure: String? = null) {
        override fun toString(): String = "starts=$starts retries=$retries failures=$failures last=${lastFailure ?: "-"}"
    }

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    suspend fun run() {
        combine(visible, shown) { v, s -> v to s }.distinctUntilChanged().collectLatest { (v, s) ->
            if (!v) {
                stop()
                return@collectLatest
            }
            if (s) return@collectLatest
            delay(showDelayMs)
            var attempt = 0
            while (true) {
                attempt++
                val error = runCatching { start(attempt) }.exceptionOrNull()
                _status.update {
                    it.copy(
                        starts = it.starts + 1,
                        retries = it.retries + if (attempt > 1) 1 else 0,
                        failures = it.failures + if (error != null) 1 else 0,
                        lastFailure = error?.let { e -> "start ${e.javaClass.simpleName}" } ?: it.lastFailure,
                    )
                }
                if (error != null) DhLog.w("surface: panel 시작 실패 ${error.javaClass.simpleName}: ${error.message?.take(100)} — 다시 시도")
                val wait = backoffMs(attempt)
                delay(wait)
                // 여기까지 왔다 = 아직 창이 안 붙었다(붙었으면 shown 변화로 이 블록이 취소된다)
                if (error == null) {
                    _status.update { it.copy(failures = it.failures + 1, lastFailure = "not-shown ${wait}ms") }
                    DhLog.w("surface: panel 이 ${wait} ms 안에 안 붙음 — 다시 시작 (attempt=$attempt)")
                }
            }
        }
    }

    private fun backoffMs(attempt: Int): Long = (retryBaseMs shl (attempt - 1).coerceIn(0, 8)).coerceAtMost(retryMaxMs)

    companion object {
        const val SHOW_DELAY_MS = 600L
        const val RETRY_BASE_MS = 3_000L
        const val RETRY_MAX_MS = 30_000L
    }
}
