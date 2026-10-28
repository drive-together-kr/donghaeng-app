package app.donghaeng.car.surface

import app.donghaeng.car.domain.model.NextTurn
import app.donghaeng.car.domain.model.NextTurnResult
import app.donghaeng.car.domain.session.ReceivedGroupEvent
import app.donghaeng.car.feature.drive.Tone
import app.donghaeng.protocol.GroupEvent
import app.donghaeng.protocol.Severity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 알림 카드 TBT 게이트 (docs/05 §5.2, 사용자 지시 2026-10-28) — 회전 300 m 이내면 미루고 회전 후 올림, 모르면 즉시, 120초 폐기,
 * 미룬 동안만 1초마다 TBT 재조회, PANEL 모드면 카드 없음. 게이트(순수) + 알림기(가상 시간) + 조회기(디버그 덮어쓰기).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TurnCardGateTest {

    private fun ahead(m: Int, kind: String = "TURN_RIGHT", desc: String = "강변로 방면") = NextTurnResult.Ahead(NextTurn(m, kind, desc))

    private fun card(type: String, title: String = "$type 문구") =
        EventCard(type, EventCardPolicy.notificationIdOf(type), EventCardPolicy.categoryOf(type), title, Tone.INFO)

    private fun ev(id: String, type: String, source: String = "veh_A", severity: Severity = Severity.WARN, speech: String = "$type 문구") =
        ReceivedGroupEvent(GroupEvent(id, type, source, severity, speech, JsonObject(emptyMap())), envelopeTsMs = SERVER_NOW - 1_000, receivedAtMs = 0L)

    // ------------------------------------------------------------------ 게이트(순수)

    @Test
    fun `within 300 m the card is held and it is released once the next reading is beyond 300 m`() {
        val g = TurnCardGate()
        assertTrue(g.isNear(ahead(300)))
        assertFalse(g.isNear(ahead(301)))
        assertFalse(g.isNear(NextTurnResult.NoGuidance))
        assertFalse(g.isNear(NextTurnResult.Unknown))

        assertTrue(g.observe(ahead(250), 0).released.isEmpty())
        assertNull(g.hold(card("HARD_BRAKE"), ev("e1", "HARD_BRAKE"), 0))
        assertTrue("같은 회전에 다가가는 중", g.observe(ahead(180), 1_000).released.isEmpty())
        assertTrue(g.observe(ahead(40), 2_000).released.isEmpty())
        assertEquals(1, g.heldCount)
        val u = g.observe(ahead(301), 3_000)
        assertEquals(listOf("HARD_BRAKE"), u.released.map { it.card.type })
        assertEquals("far", u.reason)
        assertEquals(0, g.heldCount)
    }

    @Test
    fun `a changed turn kind or description means the turn was passed, distance jitter does not but a jump does`() {
        val g = TurnCardGate()
        g.observe(ahead(200, "TURN_RIGHT", "A 방면"), 0)
        g.hold(card("LOW_ENERGY"), ev("e1", "LOW_ENERGY"), 0)
        val changed = g.observe(ahead(150, "TURN_LEFT", "B 방면"), 1_000)
        assertEquals("turn-changed", changed.reason)
        assertEquals(1, changed.released.size)

        // 바뀐 회전도 300 m 안 → 새 카드는 그 회전을 기준으로 붙잡는다
        g.hold(card("HARD_BRAKE"), ev("e2", "HARD_BRAKE"), 1_000)
        assertTrue(g.observe(ahead(120, "TURN_LEFT", "B 방면"), 2_000).released.isEmpty())
        assertTrue("거리 흔들림(+50 m 이하)은 같은 회전", g.observe(ahead(170, "TURN_LEFT", "B 방면"), 3_000).released.isEmpty())
        val jump = g.observe(ahead(260, "TURN_LEFT", "B 방면"), 4_000)
        assertEquals("같은 문구의 연속 회전 — 거리가 크게 늘면 앞 회전을 지났다", "distance-jump", jump.reason)
        assertEquals(listOf("HARD_BRAKE"), jump.released.map { it.card.type })
    }

    @Test
    fun `guidance ending and an unknown TBT both release held cards`() {
        val g = TurnCardGate()
        g.observe(ahead(100), 0)
        g.hold(card("HARD_BRAKE"), ev("e1", "HARD_BRAKE"), 0)
        assertEquals("no-guidance", g.observe(NextTurnResult.NoGuidance, 1_000).reason)
        g.observe(ahead(100), 2_000)
        g.hold(card("HARD_BRAKE"), ev("e2", "HARD_BRAKE"), 2_000)
        assertEquals("unknown", g.observe(NextTurnResult.Unknown, 3_000).reason)
        assertEquals(0, g.heldCount)
    }

    @Test
    fun `same type keeps only the newest card, other types keep their order, put-back keeps the first hold time`() {
        val g = TurnCardGate()
        g.observe(ahead(100), 0)
        g.hold(card("LOW_ENERGY", "첫째"), ev("e1", "LOW_ENERGY"), 0)
        g.hold(card("HARD_BRAKE"), ev("e2", "HARD_BRAKE"), 500)
        val replaced = g.hold(card("LOW_ENERGY", "둘째"), ev("e3", "LOW_ENERGY"), 1_000)
        assertEquals("첫째", replaced!!.card.title)
        assertEquals(listOf("LOW_ENERGY", "HARD_BRAKE"), g.heldTypes())
        val out = g.observe(ahead(900), 2_000).released
        assertEquals(listOf("둘째", "HARD_BRAKE 문구"), out.map { it.card.title })

        g.putBack(out[1])
        g.putBack(out[1].copy(sinceMs = 9_999))
        assertEquals("이미 있으면 그대로", 500L, g.observe(ahead(100), 3_000).let { g.clear().single().sinceMs })
    }

    @Test
    fun `cards deferred for more than 120 s are dropped`() {
        val g = TurnCardGate()
        g.observe(ahead(200), 0)
        g.hold(card("HARD_BRAKE"), ev("e1", "HARD_BRAKE"), 0)
        assertTrue(g.observe(ahead(200), 120_000).expired.isEmpty())
        val u = g.observe(ahead(200), 120_001)
        assertEquals(listOf("HARD_BRAKE"), u.expired.map { it.card.type })
        assertTrue(u.released.isEmpty())
        assertEquals(0, g.heldCount)
    }

    // ------------------------------------------------------------------ 알림기 + 게이트(가상 시간)

    private class Rig(scope: TestScope, mode: SurfaceMode = SurfaceMode.CARDS_ONLY, var reading: NextTurnResult = NextTurnResult.Unknown) {
        val mode = MutableStateFlow(mode)
        val posted = mutableListOf<EventCard>()
        var lookups = 0
        val notifier = EventCardNotifier(
            events = emptyFlow(), myVehicleId = { "veh_ME" }, serverNow = { SERVER_NOW }, mode = this.mode, groupName = { "시연 그룹" },
            poster = { c, _ -> posted += c; true },
            nextTurn = { lookups++; reading },
            dispatcher = StandardTestDispatcher(scope.testScheduler),
            clock = { scope.testScheduler.currentTime },
        )
    }

    @Test
    fun `CARDS_ONLY defers a card within 300 m of the next turn, asks again every second only while it is held, and posts it after the turn`() = runTest {
        val r = Rig(this, reading = ahead(250))
        assertEquals(CardOutcome.DEFERRED, r.notifier.onEvent(ev("e1", "HARD_BRAKE")))
        assertTrue("음성만 나가고 카드는 아직", r.posted.isEmpty())
        assertEquals(1, r.lookups)
        assertEquals(1, r.notifier.state.value.held)
        assertEquals("250m/TURN_RIGHT", r.notifier.state.value.lastTbt)

        r.reading = ahead(120)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(2, r.lookups)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(3, r.lookups)
        assertTrue(r.posted.isEmpty())

        r.reading = ahead(1_100, "TURN_LEFT", "다음 길") // 회전을 지났다
        advanceTimeBy(1_000); runCurrent()
        assertEquals(listOf("HARD_BRAKE"), r.posted.map { it.type })
        val s = r.notifier.state.value
        assertEquals(0, s.held)
        assertEquals(1, s.deferred)
        assertEquals(1, s.released)

        val after = r.lookups
        advanceTimeBy(30_000); runCurrent()
        assertEquals("미룬 카드가 없으면 TBT 를 묻지 않는다", after, r.lookups)
    }

    @Test
    fun `an unknown, missing or far TBT posts the card at once`() = runTest {
        val r = Rig(this)
        assertEquals("타임아웃·미지원 → 즉시", CardOutcome.POSTED, r.notifier.onEvent(ev("e1", "HARD_BRAKE")))
        advanceTimeBy(6_000)
        r.reading = NextTurnResult.NoGuidance
        assertEquals("안내 중 아님 → 즉시", CardOutcome.POSTED, r.notifier.onEvent(ev("e2", "LOW_ENERGY")))
        advanceTimeBy(6_000)
        r.reading = ahead(301)
        assertEquals(CardOutcome.POSTED, r.notifier.onEvent(ev("e3", "WAYPOINT_ARRIVED")))
        assertEquals(3, r.posted.size)
        assertEquals(0, r.notifier.state.value.deferred)
        advanceTimeBy(10_000); runCurrent()
        assertEquals("미룬 게 없으면 재조회 없음", 3, r.lookups)
    }

    @Test
    fun `a card held through 120 s of turns is dropped, never posted`() = runTest {
        val r = Rig(this, reading = ahead(200))
        assertEquals(CardOutcome.DEFERRED, r.notifier.onEvent(ev("e1", "LOW_ENERGY")))
        advanceTimeBy(120_000); runCurrent()
        assertEquals(1, r.notifier.state.value.held)
        advanceTimeBy(1_000); runCurrent()
        assertTrue(r.posted.isEmpty())
        assertEquals(0, r.notifier.state.value.held)
        assertEquals(1, r.notifier.state.value.expired)
        val after = r.lookups
        advanceTimeBy(5_000); runCurrent()
        assertEquals(after, r.lookups)
    }

    @Test
    fun `PANEL mode never posts a card - and held cards are dropped when the overlay permission appears`() = runTest {
        val panel = Rig(this, mode = SurfaceMode.PANEL, reading = ahead(900))
        assertEquals(CardOutcome.SKIPPED, panel.notifier.onEvent(ev("p1", "HARD_BRAKE")))
        assertEquals(CardOutcome.SKIPPED, panel.notifier.onEvent(ev("p2", "AIRBAG_DEPLOYED", severity = Severity.ALERT)))
        assertTrue(panel.posted.isEmpty())
        assertEquals(0, panel.lookups)
        assertEquals(2, panel.notifier.state.value.skipped["panel-mode"])

        val r = Rig(this, reading = ahead(100))
        assertEquals(CardOutcome.DEFERRED, r.notifier.onEvent(ev("e1", "HARD_BRAKE")))
        r.mode.value = SurfaceMode.PANEL
        r.reading = ahead(900)
        advanceTimeBy(1_000); runCurrent()
        assertTrue("패널이 생겼으면 미룬 카드도 버린다(소식은 패널 줄·음성으로)", r.posted.isEmpty())
        assertEquals(0, r.notifier.state.value.held)
        assertEquals(1, r.notifier.state.value.skipped["mode-PANEL"])
    }

    @Test
    fun `held cards go out before a new one after the turn, same type keeps the newest, and the 5 s burst limit still applies`() = runTest {
        val r = Rig(this, reading = ahead(200))
        assertEquals(CardOutcome.DEFERRED, r.notifier.onEvent(ev("e1", "LOW_ENERGY", speech = "첫째")))
        assertEquals("같은 종류는 바꿔 넣는다", CardOutcome.DEFERRED, r.notifier.onEvent(ev("e2", "LOW_ENERGY", speech = "둘째")))
        assertEquals(CardOutcome.DEFERRED, r.notifier.onEvent(ev("e3", "HARD_BRAKE")))
        assertEquals(CardOutcome.DEFERRED, r.notifier.onEvent(ev("e4", "WAYPOINT_ARRIVED")))
        assertEquals(3, r.notifier.state.value.held)

        // 회전을 지난 뒤 새 소식이 오면: 미룬 카드가 먼저 나가고(5초에 2장), 새 카드는 한도에 걸린다
        r.reading = ahead(800, "TURN_LEFT", "다음 길")
        assertEquals(CardOutcome.SKIPPED, r.notifier.onEvent(ev("e5", "MEMBER_JOINED", source = "veh_B")))
        assertEquals(listOf("둘째", "HARD_BRAKE 문구"), r.posted.map { it.title })
        assertEquals(1, r.notifier.state.value.skipped["burst"])
        assertEquals("한도에 걸린 미룬 카드는 다시 기다린다", 1, r.notifier.state.value.held)

        advanceTimeBy(4_000); runCurrent()
        assertEquals(2, r.posted.size)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(listOf("LOW_ENERGY", "HARD_BRAKE", "WAYPOINT_ARRIVED"), r.posted.map { it.type })
        assertEquals(0, r.notifier.state.value.held)
    }

    // ------------------------------------------------------------------ 조회기

    @Test
    fun `probe override wins for its duration, then the navi answers again - failures and slow answers are unknown`() = runTest {
        var answer: suspend () -> NextTurnResult = { ahead(500) }
        val probe = TurnProbe(lookup = { answer() }, clock = { testScheduler.currentTime }, timeoutMs = 800)
        assertEquals(ahead(500), probe.read())

        probe.setOverride(120, 10_000)
        val o = probe.read() as NextTurnResult.Ahead
        assertEquals(120, o.turn.distanceM)
        assertEquals(TurnProbe.OVERRIDE_KIND, o.turn.kind)
        assertEquals("덮어쓰는 동안은 내비에 묻지 않는다", 1, probe.lookups)
        assertTrue(probe.describe().startsWith("override=120m/10s"))

        advanceTimeBy(10_001)
        assertEquals(ahead(500), probe.read())
        probe.setOverride(50, 60_000)
        probe.clearOverride()
        assertEquals(ahead(500), probe.read())

        answer = { throw IllegalStateException("sdk") }
        assertEquals(NextTurnResult.Unknown, probe.read())
        answer = { delay(5_000); ahead(1) }
        assertEquals("응답이 늦으면 모름(카드는 바로)", NextTurnResult.Unknown, probe.read())
    }

    private companion object {
        const val SERVER_NOW = 1_700_000_000_000L
    }
}
