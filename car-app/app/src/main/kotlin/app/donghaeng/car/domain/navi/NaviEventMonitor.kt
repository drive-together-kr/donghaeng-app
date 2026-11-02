package app.donghaeng.car.domain.navi

import app.donghaeng.car.adapter.navi.NaviAdapter
import app.donghaeng.car.core.common.CoordinateMasking
import app.donghaeng.car.core.common.DhLog
import app.donghaeng.car.domain.model.NaviEvent
import app.donghaeng.car.domain.model.RouteState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** 진단 화면용 내비 이벤트 요약(좌표는 마스킹된 문자열만 보관) */
data class NaviMonitorState(
    val running: Boolean = false,
    /** 이벤트 종류별 누적 수 — 어떤 콜백이 실제로 오는지 확인용(T20 완료 기준) */
    val counts: Map<String, Int> = emptyMap(),
    val lastFixMasked: String? = null,
    val lastFixHeading: Float? = null,
    val lastFixTs: Long? = null,
    val lastTrip: String? = null,
    /** 마지막 TripInfo 원값(T23 텔레메트리 ETA 용) */
    val lastRemainDistanceM: Int? = null,
    val lastRemainTimeS: Int? = null,
    val lastTripTs: Long? = null,
    val lastStatus: String? = null,
    val lastRouteState: String? = null,
    val destinationName: String? = null,
    val waypointCount: Int? = null,
    val speedLimitKph: Int? = null,
    val lastArrival: String? = null,
    val lastError: String? = null,
    /** 스트림이 끝나거나 예외로 죽어 다시 구독한 횟수 */
    val restarts: Int = 0,
) {
    val totalEvents: Int get() = counts.values.sum()
}

/**
 * 앱이 사는 동안 [NaviAdapter.events] 를 한 번만 구독해 두고(SDK 리스너 1개) 요약 상태와 공유 스트림을 제공한다 (T20).
 *
 * - 진단 화면은 [state] 로 콜백 종류별 수·마지막 위치(마스킹)·상태를 보여 준다 → 실차/AVD 에서 "어떤 콜백이 오는지" 를 화면·로그로 확인.
 * - 다른 소비자(T23 TelemetryCollector, T26 이벤트 엔진)는 [events] 를 구독한다 — 어댑터에 리스너를 또 등록하지 않는다.
 * - 스트림이 끝나거나(초기화 실패 등) 예외가 나면 [restartDelayMs] 뒤 다시 구독한다. 크래시 없음.
 * - 로그: 위치는 [LOG_EVERY_FIXES] 번마다 한 줄(마스킹), 나머지 이벤트는 매번 한 줄. `adb logcat -s DongHaeng | grep navi`.
 */
@Singleton
class NaviEventMonitor @Inject constructor(
    private val navi: NaviAdapter,
) {
    private var dispatcher: CoroutineDispatcher = Dispatchers.Default
    private var restartDelayMs: Long = RESTART_DELAY_MS
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    private val _state = MutableStateFlow(NaviMonitorState())
    val state: StateFlow<NaviMonitorState> = _state

    private val _events = MutableSharedFlow<NaviEvent>(extraBufferCapacity = 64)

    /** 모든 내비 이벤트(위치 포함) 공유 스트림. 느린 구독자가 있으면 오래된 것부터 버린다 */
    val events: SharedFlow<NaviEvent> = _events

    /** 테스트용: 디스패처·재구독 지연 교체 */
    fun configureForTest(dispatcher: CoroutineDispatcher, restartDelayMs: Long) {
        this.dispatcher = dispatcher
        this.restartDelayMs = restartDelayMs
    }

    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(dispatcher) {
            var first = true
            while (isActive) {
                if (!first) {
                    _state.update { it.copy(running = false, restarts = it.restarts + 1) }
                    delay(restartDelayMs)
                }
                first = false
                try {
                    DhLog.i("navi monitor: subscribe")
                    _state.update { it.copy(running = true) }
                    navi.events().collect { onEvent(it) }
                    DhLog.w("navi monitor: stream completed — ${restartDelayMs / 1000}s 뒤 재구독")
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DhLog.w("navi monitor: stream failed ${e.javaClass.simpleName}: ${e.message} — 재구독 예정")
                    _state.update { it.copy(lastError = "${e.javaClass.simpleName}: ${e.message}") }
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        _state.update { it.copy(running = false) }
    }

    private suspend fun onEvent(e: NaviEvent) {
        val key = e::class.simpleName ?: "?"
        _state.update { s ->
            val counts = s.counts + (key to (s.counts[key] ?: 0) + 1)
            when (e) {
                is NaviEvent.CurrentLocation -> s.copy(counts = counts, lastFixMasked = CoordinateMasking.mask(e.fix.lat, e.fix.lon), lastFixHeading = e.fix.heading, lastFixTs = e.fix.ts)
                is NaviEvent.TripInfo -> s.copy(counts = counts, lastTrip = formatTrip(e), lastRemainDistanceM = e.remainDistanceM, lastRemainTimeS = e.remainTimeS, lastTripTs = System.currentTimeMillis())
                is NaviEvent.DrivingInfo -> s.copy(counts = counts)
                // A009: 안내가 끝나면(종료·취소·목적지 없음) 남은 거리·시간을 비운다 — 텔레메트리가 120 s 동안 굳은 ETA 를 다시 보내지 않고 바로 '지움'을 보낸다
                is NaviEvent.RouteStateInfo -> if (e.state == RouteState.ENDED || e.state == RouteState.CANCELLED) {
                    s.copy(counts = counts, lastRouteState = e.state.name + (e.detail?.let { " ($it)" } ?: ""), lastRemainDistanceM = null, lastRemainTimeS = null, lastTripTs = null)
                } else {
                    s.copy(counts = counts, lastRouteState = e.state.name + (e.detail?.let { " ($it)" } ?: ""))
                }
                is NaviEvent.DestinationChanged -> if (e.destination == null) {
                    s.copy(counts = counts, destinationName = "(없음)", lastRemainDistanceM = null, lastRemainTimeS = null, lastTripTs = null)
                } else {
                    s.copy(counts = counts, destinationName = e.destination.name)
                }
                is NaviEvent.WaypointChanged -> s.copy(counts = counts, waypointCount = e.waypoints.size)
                is NaviEvent.WaypointArrived -> s.copy(counts = counts, lastArrival = "경유지 ${e.name ?: e.waypoint?.name ?: "?"}")
                is NaviEvent.DestinationArrived -> s.copy(counts = counts, lastArrival = "목적지 ${e.name ?: e.destination?.name ?: "?"}")
                is NaviEvent.SpeedLimit -> s.copy(counts = counts, speedLimitKph = e.limitKph)
                is NaviEvent.NavigationStatus -> s.copy(counts = counts, lastStatus = "${e.status ?: "?"} guiding=${e.isGuiding ?: "?"}")
                is NaviEvent.Error -> s.copy(counts = counts, lastError = "${e.code ?: "?"} ${e.message ?: ""}".trim())
            }
        }
        when (e) {
            is NaviEvent.CurrentLocation -> {
                val n = _state.value.counts[key] ?: 0
                if (n == 1 || n % LOG_EVERY_FIXES == 0) DhLog.i("navi monitor: fix #$n ${e.fix.toLogString()}")
            }
            is NaviEvent.DrivingInfo -> DhLog.d("navi monitor: $key speed=${e.speedKph}")
            else -> DhLog.i("navi monitor: $key ${summarize(e)}  total=${_state.value.totalEvents}")
        }
        _events.tryEmit(e)
    }

    private fun summarize(e: NaviEvent): String = when (e) {
        is NaviEvent.TripInfo -> formatTrip(e)
        is NaviEvent.RouteStateInfo -> e.state.name
        is NaviEvent.DestinationChanged -> e.destination?.name ?: "(없음)"
        is NaviEvent.WaypointChanged -> "count=${e.waypoints.size}"
        is NaviEvent.WaypointArrived -> e.name ?: "?"
        is NaviEvent.DestinationArrived -> e.name ?: "?"
        is NaviEvent.SpeedLimit -> "${e.limitKph ?: "?"} km/h"
        is NaviEvent.NavigationStatus -> "${e.status} guiding=${e.isGuiding}"
        is NaviEvent.Error -> "${e.code} ${e.message ?: ""}"
        else -> ""
    }

    private fun formatTrip(e: NaviEvent.TripInfo): String {
        val dist = e.remainDistanceM?.let { if (it >= 1_000) "%.1f km".format(it / 1_000.0) else "$it m" } ?: "?"
        val time = e.remainTimeS?.let { if (it >= 60) "${it / 60}분" else "${it}초" } ?: "?"
        return "$dist · $time"
    }

    private companion object {
        const val RESTART_DELAY_MS = 10_000L
        const val LOG_EVERY_FIXES = 10
    }
}
