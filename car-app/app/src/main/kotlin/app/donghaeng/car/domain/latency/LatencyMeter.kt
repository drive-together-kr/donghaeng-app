package app.donghaeng.car.domain.latency

import app.donghaeng.car.core.common.DhLog
import app.donghaeng.car.domain.session.ReceivedGroupEvent
import app.donghaeng.car.domain.session.ReceivedGroupState
import app.donghaeng.car.domain.time.ClockSyncState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 최근 [LatencyMeter.windowMs] 창의 통계 */
data class LatencyStat(val lastMs: Long, val avgMs: Long, val maxMs: Long, val count: Int)

data class LatencyState(
    /** 그룹 주행 화면 오버레이 표시 여부(설정) */
    val overlayEnabled: Boolean = true,
    val clockSynced: Boolean = false,
    val clockOffsetMs: Long? = null,
    val clockRttMs: Long? = null,
    /** 그룹 EVENT: 서버 발행 ts → 수신 */
    val event: LatencyStat? = null,
    /** 그룹 STATE 스냅샷: 서버 발행 ts → 수신 */
    val state: LatencyStat? = null,
    /** 시계 오차로 음수가 나와 0 으로 자른 표본 수 */
    val clamped: Int = 0,
    /** 동기화 전에 와서 재지 못한 메시지 수 */
    val unsynced: Int = 0,
) {
    /** "이벤트 123 ms · 상태 85 ms" — 표시할 게 없으면 null */
    val overlayText: String?
        get() {
            if (!overlayEnabled) return null
            if (!clockSynced) return "시계 동기화 중"
            val parts = listOfNotNull(event?.let { "이벤트 ${it.lastMs} ms" }, state?.let { "상태 ${it.lastMs} ms" })
            return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
        }
}

/**
 * 메시지 지연 측정 (T31, PRD F12 보조, docs/05 §3.4).
 *
 * `지연 = (수신 로컬 시각 + 시계 오프셋) - 봉투 ts(서버 발행 시각)` 을 그룹 EVENT·STATE 마다 재고, 최근 [windowMs] 창의 마지막/평균/최대를 낸다.
 * 시계 오프셋은 [ClockSync] 가 준다 — 동기화 전 메시지는 세지 않고 [LatencyState.unsynced] 로만 남긴다. 음수(오프셋 오차)는 0 으로 자른다.
 * 값은 서버 → 차량 한 방향(브로커 경유)이며 텔레메트리 상행은 포함하지 않는다.
 */
class LatencyMeter(
    private val events: Flow<ReceivedGroupEvent>,
    private val states: Flow<ReceivedGroupState>,
    private val clockState: StateFlow<ClockSyncState>,
    private val windowMs: Long = WINDOW_MS,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var job: Job? = null

    private val _state = MutableStateFlow(LatencyState())
    val state: StateFlow<LatencyState> = _state.asStateFlow()

    private val eventSamples = ArrayDeque<Pair<Long, Long>>()   // (측정 로컬 시각, 지연)
    private val stateSamples = ArrayDeque<Pair<Long, Long>>()
    private var logged = 0

    fun setOverlayEnabled(enabled: Boolean) {
        _state.update { it.copy(overlayEnabled = enabled) }
        DhLog.i("latency: overlay=$enabled")
    }

    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            launch { clockState.collect { c -> _state.update { it.copy(clockSynced = c.synced, clockOffsetMs = c.offsetMs, clockRttMs = c.rttMs) } } }
            launch { events.collect { e -> record(eventSamples, e.envelopeTsMs, e.receivedAtMs, isEvent = true) } }
            launch { states.collect { s -> record(stateSamples, s.envelopeTsMs, s.receivedAtMs, isEvent = false) } }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel(); job = null
    }

    /** 테스트·진단에서 직접 부를 수 있다 */
    @Synchronized
    fun record(samples: ArrayDeque<Pair<Long, Long>>, envelopeTsMs: Long, receivedAtMs: Long, isEvent: Boolean) {
        val offset = clockState.value.offsetMs
        if (offset == null) {
            _state.update { it.copy(unsynced = it.unsynced + 1) }
            return
        }
        val raw = (receivedAtMs + offset) - envelopeTsMs
        val latency = raw.coerceAtLeast(0)
        val now = clock()
        samples.addLast(now to latency)
        while (samples.isNotEmpty() && now - samples.first().first > windowMs) samples.removeFirst()
        val stat = LatencyStat(lastMs = latency, avgMs = samples.sumOf { it.second } / samples.size, maxMs = samples.maxOf { it.second }, count = samples.size)
        _state.update { s -> if (isEvent) s.copy(event = stat, clamped = s.clamped + (if (raw < 0) 1 else 0)) else s.copy(state = stat, clamped = s.clamped + (if (raw < 0) 1 else 0)) }
        if (isEvent || logged % 15 == 0) DhLog.i("latency: ${if (isEvent) "event" else "state"} ${latency}ms (avg ${stat.avgMs} max ${stat.maxMs} n=${stat.count})${if (raw < 0) " clamped(raw=$raw)" else ""}")
        if (!isEvent) logged++
    }

    companion object {
        const val WINDOW_MS = 60_000L
    }
}
