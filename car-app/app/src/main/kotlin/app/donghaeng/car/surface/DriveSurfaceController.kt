package app.donghaeng.car.surface

import android.app.Application
import android.content.Intent
import app.donghaeng.car.core.common.DhLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 주행 중 표면 배선(싱글턴, Application 에서 한 번 [start], docs/05 §5.2).
 *
 * 1) Activity 생명주기 추적 등록 → 동행 화면 전면 여부, 포그라운드 복귀 때 오버레이 권한 재확인
 * 2) 오버레이 권한 5초 주기 확인, [SurfaceState]·[EventCardNotifier] 시작
 * 3) [SurfaceState.panelVisible] 이 true 면 [FloatingPanelService] 시작, false 면 종료.
 *    잠깐의 일시정지(권한 다이얼로그·알림 창)로 패널이 깜빡이지 않게 표시만 [SHOW_DELAY_MS] 늦춘다.
 *
 * 로그: `adb logcat -s DongHaeng | grep surface:` — `surface: mode=PANEL`, `surface: panel start/stop`, `surface panel: shown corner=TOP_END …`, `surface: card …`.
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

    fun start() {
        if (!started.compareAndSet(false, true)) return
        app.registerActivityLifecycleCallbacks(tracker)
        tracker.onResume = { overlay.recheck() }
        overlay.start()
        state.start()
        cards.start()
        scope.launch { state.mode.collect { DhLog.i("surface: mode=$it canDraw=${overlay.canDraw.value}") } }
        scope.launch {
            state.panelVisible.collectLatest { visible ->
                if (visible) {
                    delay(SHOW_DELAY_MS)
                    startPanel()
                } else {
                    stopPanel()
                }
            }
        }
    }

    private fun startPanel() {
        try {
            app.startService(Intent(app, FloatingPanelService::class.java))
            DhLog.i("surface: panel start 요청 (${state.describe()})")
        } catch (e: Exception) {
            DhLog.w("surface: panel 시작 실패 ${e.javaClass.simpleName}: ${e.message?.take(100)}")
        }
    }

    private fun stopPanel() {
        runCatching { app.stopService(Intent(app, FloatingPanelService::class.java)) }
            .onFailure { DhLog.w("surface: panel 종료 실패 ${it.javaClass.simpleName}") }
        if (FloatingPanelService.shown) DhLog.i("surface: panel stop 요청")
    }

    /** 디버그 리시버 `surface` 액션이 찍는 한 줄 */
    fun describe(): String = "${state.describe()} panelShown=${FloatingPanelService.shown} cards=${cards.state.value}"

    companion object {
        const val SHOW_DELAY_MS = 600L
    }
}
