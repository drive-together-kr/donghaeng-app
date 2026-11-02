package app.donghaeng.car.surface

import app.donghaeng.car.adapter.voice.VoicePath
import app.donghaeng.car.domain.model.NextTurn
import app.donghaeng.car.domain.model.NextTurnResult
import app.donghaeng.car.domain.session.ReceivedGroupEvent
import app.donghaeng.car.domain.voice.VoiceCue
import app.donghaeng.car.feature.drive.DriveFormat
import app.donghaeng.car.feature.drive.EventHighlight
import app.donghaeng.protocol.GroupEvent
import app.donghaeng.protocol.Severity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 위험 이벤트(급제동·에어백)가 화면에서 가려지거나 늦지 않게 — 소식 줄을 붙잡음(B016), 카드 + 음성 모드에서 TBT 게이트 예외(B067),
 * 주행 중 카드는 눌러도 동행을 열지 않음(B125).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SurfaceDangerTest {

    private val t0 = 100_000L

    private fun received(id: String, type: String, source: String, atMs: Long, speech: String = "$type 문구", severity: Severity = Severity.INFO) =
        ReceivedGroupEvent(GroupEvent(id, type, source, severity, speech, JsonObject(emptyMap())), envelopeTsMs = atMs, receivedAtMs = atMs)

    // ------------------------------------------------------------------ 소식 줄(B016)

    @Test
    fun `a late HARD_BRAKE keeps the event line over a newer message until its voice has started plus 3 s (B016)`() {
        val brake = received("evt_b", "HARD_BRAKE", "veh_A", t0, "앞쪽 아빠 차가 급제동했어요.", Severity.WARN)
        val preset = received("evt_p", "PRESET_MESSAGE", "veh_C", t0 + 2_000, "천천히 가요")
        val events = listOf(preset, brake)
        // Pleos 부하(평균 6 s) — 받은 순간부터 강조
        val late = VoiceCue(path = VoicePath.PLEOS_TTS, avgLatencyMs = 6_000, lateAtReceipt = mapOf("evt_b" to true))
        val held = SurfaceEvents.bannerUi(events, "veh_ME", t0 + 2_500, t0 + 2_500, late)!!
        assertEquals("뒤이은 메시지에 가려지지 않는다", DriveFormat.eventText("HARD_BRAKE", brake.event.speech), held.text)
        assertEquals(EventHighlight.BLINK, held.highlight)
        // 음성이 6 s 에 시작 → 3초 동안 STEADY 로 붙잡고, 그 뒤 최신 소식으로
        val started = late.copy(starts = mapOf("evt_b" to t0 + 6_000))
        assertEquals(EventHighlight.STEADY, SurfaceEvents.bannerUi(events, "veh_ME", t0 + 7_000, t0 + 7_000, started)!!.highlight)
        val after = SurfaceEvents.bannerUi(events, "veh_ME", t0 + 9_500, t0 + 9_500, started)!!
        assertEquals(DriveFormat.eventText("PRESET_MESSAGE", "천천히 가요"), after.text)
        assertEquals(EventHighlight.NONE, after.highlight)
    }

    @Test
    fun `a brake already spoken on a fast path does not hold the line, without voice values danger goes first`() {
        val brake = received("evt_b", "HARD_BRAKE", "veh_A", t0, "앞쪽 아빠 차가 급제동했어요.", Severity.WARN)
        val preset = received("evt_p", "PRESET_MESSAGE", "veh_C", t0 + 2_000, "천천히 가요")
        val fast = VoiceCue(path = VoicePath.ANDROID_TTS, avgLatencyMs = 800, starts = mapOf("evt_b" to t0 + 500), lateAtReceipt = mapOf("evt_b" to false))
        assertEquals("귀로 이미 들었다 — 최신순", "evt_p", SurfaceEvents.banner(listOf(preset, brake), "veh_ME", t0 + 2_500, t0 + 2_500, fast)!!.event.eventId)
        assertEquals("음성 값이 없으면 창 안의 위험 톤 먼저", "evt_b", SurfaceEvents.banner(listOf(preset, brake), "veh_ME", t0 + 2_500, t0 + 2_500)!!.event.eventId)
        assertEquals("창(10초) 밖이면 붙잡지 않는다", "evt_p", SurfaceEvents.banner(listOf(preset, brake), "veh_ME", t0 + 10_500, t0 + 10_500)!!.event.eventId)
    }

    // ------------------------------------------------------------------ 카드(B067·B125)

    private fun ahead(m: Int) = NextTurnResult.Ahead(NextTurn(m, "TURN_RIGHT", "강변로 방면"))

    private fun ev(id: String, type: String, severity: Severity = Severity.WARN) =
        ReceivedGroupEvent(GroupEvent(id, type, "veh_A", severity, "$type 문구", JsonObject(emptyMap())), envelopeTsMs = SERVER_NOW - 1_000, receivedAtMs = 0L)

    private class Rig(scope: TestScope, var cue: VoiceCue, var reading: NextTurnResult, var driving: Boolean = false) {
        val posted = mutableListOf<EventCard>()
        val notifier = EventCardNotifier(
            events = emptyFlow(), myVehicleId = { "veh_ME" }, serverNow = { SERVER_NOW }, mode = MutableStateFlow(SurfaceMode.CARDS_ONLY), groupName = { "시연 그룹" },
            poster = { c, _ -> posted += c; true },
            nextTurn = { reading },
            dispatcher = StandardTestDispatcher(scope.testScheduler),
            clock = { scope.testScheduler.currentTime },
            gate = TurnCardGate(dangerMaxDeferMs = TurnCardGate.DANGER_MAX_DEFER_MS),
            voiceCues = { cue },
            isDriving = { driving },
        )
    }

    @Test
    fun `cards mode - a brake whose voice will clearly be late skips the turn gate (B067)`() = runTest {
        val r = Rig(this, cue = VoiceCue(path = VoicePath.PLEOS_TTS, avgLatencyMs = 6_000), reading = ahead(250))
        assertEquals(CardOutcome.POSTED, r.notifier.onEvent(ev("e1", "HARD_BRAKE")))
        assertEquals(listOf("HARD_BRAKE"), r.posted.map { it.type })
        // 위험이 아닌 카드는 그대로 회전 뒤로
        assertEquals(CardOutcome.DEFERRED, r.notifier.onEvent(ev("e2", "LOW_ENERGY")))
        r.notifier.stop()
    }

    @Test
    fun `cards mode - a deferred brake is released when its voice has not started within 3 s`() = runTest {
        val r = Rig(this, cue = VoiceCue(path = VoicePath.ANDROID_TTS, avgLatencyMs = 800), reading = ahead(250))
        assertEquals(CardOutcome.DEFERRED, r.notifier.onEvent(ev("e1", "HARD_BRAKE")))
        advanceTimeBy(2_000); runCurrent()
        assertTrue(r.posted.isEmpty())
        advanceTimeBy(1_100); runCurrent()
        assertEquals("음성도 카드도 없는 시간이 3초를 넘지 않게", listOf("HARD_BRAKE"), r.posted.map { it.type })
        r.notifier.stop()
    }

    @Test
    fun `cards mode - a brake already being spoken stays behind the turn and is dropped after 15 s, not shown late`() = runTest {
        val r = Rig(this, cue = VoiceCue(path = VoicePath.ANDROID_TTS, avgLatencyMs = 800, starts = mapOf("e1" to 500L)), reading = ahead(250))
        assertEquals(CardOutcome.DEFERRED, r.notifier.onEvent(ev("e1", "HARD_BRAKE")))
        advanceTimeBy(5_000); runCurrent()
        assertTrue("음성이 나갔으니 회전을 가리지 않는다", r.posted.isEmpty())
        advanceTimeBy(11_000); runCurrent()
        assertEquals(1, r.notifier.state.value.expired)
        r.reading = ahead(900)
        advanceTimeBy(2_000); runCurrent()
        assertTrue("지난 급제동이 회전 뒤 늦게 뜨지 않는다", r.posted.isEmpty())
        r.notifier.stop()
    }

    @Test
    fun `a card posted while driving does not open the app on tap, a parked one does (B125)`() = runTest {
        val r = Rig(this, cue = VoiceCue(), reading = NextTurnResult.NoGuidance, driving = true)
        assertEquals(CardOutcome.POSTED, r.notifier.onEvent(ev("e1", "LOW_ENERGY")))
        assertFalse("주행 중 — 눌러도 순정 내비를 덮지 않는다", r.posted.single().openOnTap)
        r.driving = false
        assertEquals(CardOutcome.POSTED, r.notifier.onEvent(ev("e2", "MEMBER_JOINED", Severity.INFO)))
        assertTrue(r.posted.last().openOnTap)
        r.notifier.stop()
    }

    private companion object {
        const val SERVER_NOW = 1_700_000_000_000L
    }
}
