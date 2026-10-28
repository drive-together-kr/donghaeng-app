package app.donghaeng.car.domain.latency

import app.donghaeng.car.domain.session.ReceivedGroupEvent
import app.donghaeng.car.domain.session.ReceivedGroupState
import app.donghaeng.car.domain.time.ClockSyncState
import app.donghaeng.protocol.GroupEvent
import app.donghaeng.protocol.Severity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LatencyMeterTest {

    private val events = MutableSharedFlow<ReceivedGroupEvent>(extraBufferCapacity = 8)
    private val states = MutableSharedFlow<ReceivedGroupState>(extraBufferCapacity = 8)
    private val clockState = MutableStateFlow(ClockSyncState())

    private fun event(tsMs: Long, receivedMs: Long) =
        ReceivedGroupEvent(GroupEvent("evt", "HARD_BRAKE", "veh_A", Severity.WARN, "앞차가 급제동했어요.", JsonObject(emptyMap())), envelopeTsMs = tsMs, receivedAtMs = receivedMs)

    @Test
    fun `latency uses the clock offset, keeps a window and clamps negative values`() = runTest {
        var now = 1_000_000L
        val meter = LatencyMeter(events, states, clockState, windowMs = 60_000L, dispatcher = StandardTestDispatcher(testScheduler), clock = { now })
        meter.start(); runCurrent()
        try {
            // 동기화 전 → 재지 않는다
            events.emit(event(tsMs = 500, receivedMs = 900)); runCurrent()
            assertNull(meter.state.value.event)
            assertEquals(1, meter.state.value.unsynced)
            assertEquals("시계 동기화 중", meter.state.value.overlayText)

            // 차량 시계가 서버보다 16시간 느림: offset = +57_600_000
            clockState.value = ClockSyncState(offsetMs = 57_600_000L, rttMs = 6, samples = 3, syncs = 1, lastSyncAtMs = now); runCurrent()
            val serverTs = 2_000_000_000L
            events.emit(event(tsMs = serverTs, receivedMs = serverTs - 57_600_000L + 120)); runCurrent()
            assertEquals(120L, meter.state.value.event?.lastMs)
            states.emit(ReceivedGroupState(envelopeTsMs = serverTs, receivedAtMs = serverTs - 57_600_000L + 80, members = 2)); runCurrent()
            assertEquals(80L, meter.state.value.state?.lastMs)
            assertEquals("이벤트 120 ms · 상태 80 ms", meter.state.value.overlayText)

            // 두 번째 이벤트: 평균·최대
            now += 10_000
            events.emit(event(tsMs = serverTs + 10_000, receivedMs = serverTs + 10_000 - 57_600_000L + 300)); runCurrent()
            val e = meter.state.value.event!!
            assertEquals(300L, e.lastMs); assertEquals(210L, e.avgMs); assertEquals(300L, e.maxMs); assertEquals(2, e.count)

            // 창(60초) 밖으로 밀려남 → 첫 표본 제거
            now += 61_000
            events.emit(event(tsMs = serverTs + 71_000, receivedMs = serverTs + 71_000 - 57_600_000L + 50)); runCurrent()
            assertEquals(1, meter.state.value.event?.count)
            assertEquals(50L, meter.state.value.event?.avgMs)

            // 음수(오프셋 오차) → 0 으로 자르고 센다
            events.emit(event(tsMs = serverTs + 72_000, receivedMs = serverTs + 72_000 - 57_600_000L - 7)); runCurrent()
            assertEquals(0L, meter.state.value.event?.lastMs)
            assertEquals(1, meter.state.value.clamped)

            meter.setOverlayEnabled(false)
            assertNull(meter.state.value.overlayText)
            meter.setOverlayEnabled(true)
            assertNotNull(meter.state.value.overlayText)
        } finally { meter.stop() }
    }

    @Test
    fun `cumulative samples outlive the window, include clamped as 0, exclude unsynced, and reset`() {
        var now = 1_000_000L
        val meter = LatencyMeter(events, states, clockState, windowMs = 60_000L, clock = { now })
        val ev = ArrayDeque<Pair<Long, Long>>()
        val st = ArrayDeque<Pair<Long, Long>>()

        // 동기화 전: 누적 표본에 넣지 않고 unsynced 로만 센다
        meter.record(ev, envelopeTsMs = 1_000, receivedAtMs = 1_100, isEvent = true, label = "type=X id=…abc123")
        assertEquals(LatencyDump(event = null, state = null, clockRttMs = null, clockOffsetMs = null, clamped = 0, unsynced = 1), meter.summary())

        clockState.value = ClockSyncState(offsetMs = 1_000L, rttMs = 12, samples = 3, syncs = 1, lastSyncAtMs = now)
        val serverTs = 5_000_000L
        // EVENT 20개: 지연 10, 20, …, 200 ms. 60 s 창을 한참 넘겨 받는다(창은 비워져도 누적 표본은 남는다)
        for (i in 1..20) {
            now += 10_000
            meter.record(ev, envelopeTsMs = serverTs, receivedAtMs = serverTs - 1_000L + i * 10L, isEvent = true)
        }
        assertEquals(7, meter.state.value.event?.count)   // 60 s 창 = 마지막 7개(정확히 60 s 전 표본까지 포함)
        // 음수(오프셋 오차) 한 개 → 0 으로 잘려 누적 표본에 0 으로 들어간다(n 에 포함, clamped 1)
        meter.record(ev, envelopeTsMs = serverTs, receivedAtMs = serverTs - 1_000L - 5, isEvent = true)
        // STATE 3개: 30, 10, 20 ms
        for (lat in listOf(30L, 10L, 20L)) meter.record(st, envelopeTsMs = serverTs, receivedAtMs = serverTs - 1_000L + lat, isEvent = false)

        val dump = meter.summary()
        // 21개 = 0, 10, 20, …, 200 → P50 = ⌈10.5⌉ = 11번째 = 100, P95 = ⌈19.95⌉ = 20번째 = 190
        assertEquals(LatencySummary(n = 21, p50 = 100, p95 = 190, min = 0, max = 200), dump.event)
        assertEquals(LatencySummary(n = 3, p50 = 20, p95 = 30, min = 10, max = 30), dump.state)
        assertEquals(1, dump.clamped)
        assertEquals(1, dump.unsynced)
        assertEquals(
            "latency: summary event n=21 p50=100ms p95=190ms min=0ms max=200ms | state n=3 p50=20ms p95=30ms min=10ms max=30ms | clockRtt=12ms offset=1000ms clamped=1 unsynced=1",
            dump.toLogLine(),
        )

        // reset: 누적 표본과 그 기준의 clamped/unsynced 만 비운다 — 창·화면 값은 그대로
        assertEquals(21 to 3, meter.resetHistory())
        val empty = meter.summary()
        assertNull(empty.event); assertNull(empty.state)
        assertEquals(0, empty.clamped); assertEquals(0, empty.unsynced)
        assertEquals("latency: summary event n=0 | state n=0 | clockRtt=12ms offset=1000ms clamped=0 unsynced=0", empty.toLogLine())
        assertEquals(1, meter.state.value.clamped)
        assertNotNull(meter.state.value.event)
    }

    @Test
    fun `cumulative samples keep only the latest HISTORY_CAP`() {
        clockState.value = ClockSyncState(offsetMs = 0L, rttMs = 5, samples = 1, syncs = 1, lastSyncAtMs = 0)
        val meter = LatencyMeter(events, states, clockState, clock = { 0L })
        val ev = ArrayDeque<Pair<Long, Long>>()
        // 지연 1..2005 ms → 앞의 5개(1..5)는 밀려난다
        for (lat in 1L..(LatencyMeter.HISTORY_CAP + 5L)) meter.record(ev, envelopeTsMs = 10_000, receivedAtMs = 10_000 + lat, isEvent = true)
        val s = meter.summary().event!!
        assertEquals(LatencyMeter.HISTORY_CAP, s.n)
        assertEquals(6L, s.min)
        assertEquals(LatencyMeter.HISTORY_CAP + 5L, s.max)
    }

    @Test
    fun `clamped count follows the cumulative samples when old ones are pushed out by HISTORY_CAP`() {
        clockState.value = ClockSyncState(offsetMs = 0L, rttMs = 5, samples = 1, syncs = 1, lastSyncAtMs = 0)
        val meter = LatencyMeter(events, states, clockState, clock = { 0L })
        val ev = ArrayDeque<Pair<Long, Long>>()
        val st = ArrayDeque<Pair<Long, Long>>()
        // EVENT 첫 표본이 음수(raw = -7) → 0 으로 잘려 들어간다. STATE 도 하나
        meter.record(ev, envelopeTsMs = 10_000, receivedAtMs = 10_000 - 7, isEvent = true)
        meter.record(st, envelopeTsMs = 10_000, receivedAtMs = 10_000 - 3, isEvent = false)
        // 정상 EVENT 를 상한 - 1 개 더 → 아직 clamped 표본이 누적 표본 안에 있다(n = 상한, min = 0)
        for (i in 1 until LatencyMeter.HISTORY_CAP) meter.record(ev, envelopeTsMs = 10_000, receivedAtMs = 10_000 + 50, isEvent = true)
        meter.summary().let { d ->
            assertEquals(LatencyMeter.HISTORY_CAP, d.event!!.n)
            assertEquals(0L, d.event!!.min)
            assertEquals(2, d.clamped)   // EVENT 1 + STATE 1
        }
        // 하나 더 → clamped EVENT 표본이 밀려난다. 덤프 clamped 도 같이 줄고 min > 0 (STATE 의 clamped 는 남는다)
        meter.record(ev, envelopeTsMs = 10_000, receivedAtMs = 10_000 + 50, isEvent = true)
        meter.summary().let { d ->
            assertEquals(LatencyMeter.HISTORY_CAP, d.event!!.n)
            assertEquals(50L, d.event!!.min)
            assertEquals(1, d.clamped)
            assertEquals(0L, d.state!!.min)
        }
        // 화면·진단 카드 값(앱 시작 이후 누적)은 밀려나도 그대로
        assertEquals(2, meter.state.value.clamped)
    }

    @Test
    fun `event label keeps type and the last 6 chars of the id only`() {
        assertEquals("type=PASSENGER_MESSAGE id=…X7K2QZ", LatencyMeter.eventLabel("PASSENGER_MESSAGE", "evt_01JB8Z3M4N5P6Q7R8S9T0X7K2QZ"))
        assertEquals("type=HARD_BRAKE id=*", LatencyMeter.eventLabel("HARD_BRAKE", "evt_1"))
    }
}
