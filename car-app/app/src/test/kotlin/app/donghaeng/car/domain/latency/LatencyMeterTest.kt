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
}
