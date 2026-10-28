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
 * 누적 표본 요약 한 줄 (debug `latency_dump`, docs/05 §3.4). [clamped]·[unsynced] 는 **누적 표본을 비운 뒤(reset) 기준**이다
 * (화면·진단 카드의 [LatencyState.clamped]·[LatencyState.unsynced] 는 앱 시작 이후 누적 — 따로 둔다).
 *
 * @param clamped  0 으로 자른 표본 수 — 누적 표본에 **0 으로 들어 있다**(n 에 포함)
 * @param unsynced 시계 동기화 전에 와서 재지 못한 메시지 수 — 누적 표본에 **없다**(n 에 미포함)
 */
data class LatencyDump(
    val event: LatencySummary?,
    val state: LatencySummary?,
    val clockRttMs: Long?,
    val clockOffsetMs: Long?,
    val clamped: Int,
    val unsynced: Int,
) {
    /** `latency: summary event n=… p50=…ms p95=…ms min=…ms max=…ms | state n=… … | clockRtt=…ms offset=…ms clamped=… unsynced=…` (표본이 없으면 `n=0`, 동기화 전이면 `-`) */
    fun toLogLine(): String =
        "latency: summary event ${segment(event)} | state ${segment(state)} | " +
            "clockRtt=${clockRttMs?.let { "${it}ms" } ?: "-"} offset=${clockOffsetMs?.let { "${it}ms" } ?: "-"} clamped=$clamped unsynced=$unsynced"

    private fun segment(s: LatencySummary?): String =
        if (s == null) "n=0" else "n=${s.n} p50=${s.p50}ms p95=${s.p95}ms min=${s.min}ms max=${s.max}ms"
}

/**
 * 메시지 지연 측정 (T31, PRD F12 보조, docs/05 §3.4).
 *
 * `지연 = (수신 로컬 시각 + 시계 오프셋) - 봉투 ts(서버 발행 시각)` 을 그룹 EVENT·STATE 마다 재고, 최근 [windowMs] 창의 마지막/평균/최대를 낸다.
 * 시계 오프셋은 [ClockSync] 가 준다 — 동기화 전 메시지는 세지 않고 [LatencyState.unsynced] 로만 남긴다. 음수(오프셋 오차)는 0 으로 자른다.
 * 값은 서버 → 차량 한 방향(브로커 경유)이며 텔레메트리 상행은 포함하지 않는다.
 *
 * **진단용 누적 표본(클라우드 지연 측정, docs/09 §4.2):** 60 s 창과 별개로 EVENT·STATE 각각 최근 [HISTORY_CAP]개를 모아
 * [summary] 가 P50·P95·min·max·n 을 낸다([LatencyPercentiles] — 최근접 순위법). 0 으로 자른 표본도 **0 으로 포함**한다
 * (참값이 0 과 오프셋 오차 사이라 정렬 순서상 맨 아래가 맞다 — 빼면 백분위가 위로 밀린다). 동기화 전 메시지는 값이 없어 넣지 않는다.
 * [resetHistory] 로 비운다(창·화면 값은 그대로). EVENT 는 이벤트마다 `latency: event …ms … type=… id=…<끝 6자> ts=<서버 발행 ms> rx=<수신 로컬 ms>`
 * 한 줄을 남긴다 — `tools/demo/latency.py` 가 REST 로 보낸 메시지(응답 eventId 끝 6자)와 맞춘다(본문·닉네임 없음, 규칙 8).
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

    /** 진단용 누적 표본(지연 ms, 0 으로 자른 값 포함) — 각각 최근 [HISTORY_CAP]개 */
    private val eventHistory = ArrayDeque<Long>()
    private val stateHistory = ArrayDeque<Long>()
    private var historyClamped = 0
    private var historyUnsynced = 0

    fun setOverlayEnabled(enabled: Boolean) {
        _state.update { it.copy(overlayEnabled = enabled) }
        DhLog.i("latency: overlay=$enabled")
    }

    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            launch { clockState.collect { c -> _state.update { it.copy(clockSynced = c.synced, clockOffsetMs = c.offsetMs, clockRttMs = c.rttMs) } } }
            launch { events.collect { e -> record(eventSamples, e.envelopeTsMs, e.receivedAtMs, isEvent = true, label = eventLabel(e.event.type, e.event.eventId)) } }
            launch { states.collect { s -> record(stateSamples, s.envelopeTsMs, s.receivedAtMs, isEvent = false) } }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel(); job = null
    }

    /**
     * 테스트·진단에서 직접 부를 수 있다.
     * @param label EVENT 줄 꼬리(`type=… id=…<끝 6자>`, [eventLabel]) — 있으면 `ts=`(봉투 서버 발행 ms)·`rx=`(수신 로컬 ms)와 함께 남긴다(`latency.py` 매칭용)
     */
    @Synchronized
    fun record(samples: ArrayDeque<Pair<Long, Long>>, envelopeTsMs: Long, receivedAtMs: Long, isEvent: Boolean, label: String? = null) {
        val tail = label?.let { " $it ts=$envelopeTsMs rx=$receivedAtMs" }.orEmpty()
        val offset = clockState.value.offsetMs
        if (offset == null) {
            _state.update { it.copy(unsynced = it.unsynced + 1) }
            historyUnsynced++
            // 동기화 전이라 지연은 못 재지만 수신 시각은 남긴다(latency.py 의 'PC 송신 → 차 수신' 은 기기 시계를 따로 보정한다)
            if (isEvent && label != null) DhLog.i("latency: event unsynced$tail")
            return
        }
        val raw = (receivedAtMs + offset) - envelopeTsMs
        val latency = raw.coerceAtLeast(0)
        val now = clock()
        samples.addLast(now to latency)
        while (samples.isNotEmpty() && now - samples.first().first > windowMs) samples.removeFirst()
        val history = if (isEvent) eventHistory else stateHistory
        history.addLast(latency)
        while (history.size > HISTORY_CAP) history.removeFirst()
        if (raw < 0) historyClamped++
        val stat = LatencyStat(lastMs = latency, avgMs = samples.sumOf { it.second } / samples.size, maxMs = samples.maxOf { it.second }, count = samples.size)
        _state.update { s -> if (isEvent) s.copy(event = stat, clamped = s.clamped + (if (raw < 0) 1 else 0)) else s.copy(state = stat, clamped = s.clamped + (if (raw < 0) 1 else 0)) }
        if (isEvent || logged % 15 == 0) DhLog.i("latency: ${if (isEvent) "event" else "state"} ${latency}ms (avg ${stat.avgMs} max ${stat.maxMs} n=${stat.count})${if (raw < 0) " clamped(raw=$raw)" else ""}$tail")
        if (!isEvent) logged++
    }

    /** 누적 표본 요약 — EVENT·STATE 각각 P50·P95·min·max·n(최근접 순위법, [LatencyPercentiles]) + 지금 시계 동기화 값 */
    @Synchronized
    fun summary(): LatencyDump {
        val clock = clockState.value
        return LatencyDump(
            event = LatencyPercentiles.summarize(eventHistory),
            state = LatencyPercentiles.summarize(stateHistory),
            clockRttMs = clock.rttMs,
            clockOffsetMs = clock.offsetMs,
            clamped = historyClamped,
            unsynced = historyUnsynced,
        )
    }

    /** 누적 표본과 그 기준의 clamped·unsynced 를 비운다(60 s 창·화면 값은 그대로). 돌려주는 값 = 비운 표본 수 (EVENT, STATE) */
    @Synchronized
    fun resetHistory(): Pair<Int, Int> {
        val dropped = eventHistory.size to stateHistory.size
        eventHistory.clear()
        stateHistory.clear()
        historyClamped = 0
        historyUnsynced = 0
        return dropped
    }

    companion object {
        const val WINDOW_MS = 60_000L

        /** 진단용 누적 표본 상한(EVENT·STATE 각각) — STATE 가 2 s 주기면 약 66분 */
        const val HISTORY_CAP = 2_000

        private const val ID_TAIL = 6

        /** EVENT 로그 꼬리: 종류 + eventId 끝 6자(ULID 의 난수 부분 — 본문·닉네임·차량 ID 가 아니다). 짧은 ID 는 가린다 */
        fun eventLabel(type: String, eventId: String): String =
            "type=$type id=${if (eventId.length > ID_TAIL + 2) "…" + eventId.takeLast(ID_TAIL) else "*"}"
    }
}
