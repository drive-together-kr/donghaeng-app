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
 * 알림 카드 TBT 게이트 (docs/05 §5.2, 사용자 지시 2026-10-28) — 회전 300 m 이내면 미루고 다음 회전이 300 m 밖이 되면(또는 안내 끝) 올림,
 * 연속 회전은 계속 미룸, 미룬 카드가 없을 때 모르면 즉시·있으면 '모름' 3번 연달아 와야 놓음, 120초 폐기, 미룬 동안만 1초마다 TBT 재조회,
 * 줄이 있으면 새 카드도 줄 끝(빈도 한도에 걸려도 버리지 않음), PANEL 모드면 카드 없음. 게이트(순수) + 알림기(가상 시간) + 조회기(디버그 덮어쓰기).
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
        assertTrue(g.isHolding("e1"))
        val u = g.observe(ahead(301), 3_000)
        assertEquals(listOf("HARD_BRAKE"), u.released.map { it.card.type })
        assertEquals("far", u.reason)
        assertEquals(0, g.heldCount)
        assertFalse(g.isHolding("e1"))
    }

    @Test
    fun `a passed turn followed by another turn within 300 m keeps holding - only a far next turn releases`() {
        // impl_03 처럼 회전이 이어지는 길: 80 m 좌회전 → (지남) 다음 280 m → (지남) 다음 130 m → 그다음 900 m
        val g = TurnCardGate()
        g.observe(ahead(80, "TURN_LEFT", "기업지원허브 방면"), 0)
        g.hold(card("HARD_BRAKE"), ev("e1", "HARD_BRAKE"), 0)

        val chained = g.observe(ahead(280, "TURN_LEFT", "강릉항 방면"), 1_000)
        assertTrue("회전이 바뀌었어도 다음 회전이 300 m 안이면 그 TBT 를 가리지 않게 계속 붙잡는다", chained.released.isEmpty())
        assertNull(chained.reason)
        assertTrue("연속 회전은 로그로 알린다", chained.turnChanged)
        assertFalse("같은 회전에 다가가는 중이면 바뀐 게 아니다", g.observe(ahead(200, "TURN_LEFT", "강릉항 방면"), 2_000).turnChanged)

        val jump = g.observe(ahead(260, "TURN_LEFT", "강릉항 방면"), 3_000)
        assertTrue("같은 문구의 연속 회전으로 거리가 늘어도 300 m 안이면 계속", jump.released.isEmpty())
        assertTrue(g.observe(ahead(130, "TURN_RIGHT", "창해로 방면"), 4_000).released.isEmpty())
        assertEquals(1, g.heldCount)

        val far = g.observe(ahead(900, "TURN_STRAIGHT", "경포 방면"), 5_000)
        assertEquals(listOf("HARD_BRAKE"), far.released.map { it.card.type })
        assertEquals("far", far.reason)
        assertFalse(far.turnChanged)
    }

    @Test
    fun `guidance ending releases at once, but an unknown TBT releases only after 3 in a row`() {
        val g = TurnCardGate()
        g.observe(ahead(100), 0)
        g.hold(card("HARD_BRAKE"), ev("e1", "HARD_BRAKE"), 0)
        assertEquals("no-guidance", g.observe(NextTurnResult.NoGuidance, 1_000).reason)
        assertEquals(0, g.heldCount)

        g.observe(ahead(100), 2_000)
        g.hold(card("HARD_BRAKE"), ev("e2", "HARD_BRAKE"), 2_000)
        assertTrue("한 번 늦은 응답으로 회전 직전에 놓지 않는다", g.observe(NextTurnResult.Unknown, 3_000).released.isEmpty())
        assertTrue(g.observe(NextTurnResult.Unknown, 4_000).released.isEmpty())
        assertTrue("사이에 값이 오면 다시 센다", g.observe(ahead(90), 5_000).released.isEmpty())
        assertTrue(g.observe(NextTurnResult.Unknown, 6_000).released.isEmpty())
        assertTrue(g.observe(NextTurnResult.Unknown, 7_000).released.isEmpty())
        val u = g.observe(NextTurnResult.Unknown, 8_000)
        assertEquals("unknown", u.reason)
        assertEquals(listOf("HARD_BRAKE"), u.released.map { it.card.type })
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
        assertFalse(g.isHolding("e1"))
        assertTrue(g.isHolding("e3"))
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
    fun `chained turns keep the card held - the TBT of the next near turn is not covered`() = runTest {
        // 리뷰 재현: 80 m 회전 앞에서 미룬 카드가, 회전을 지나 다음 회전 280 m 를 읽었을 때 그 TBT 위로 올라가면 안 된다
        val r = Rig(this, reading = ahead(80, "TURN_LEFT", "기업지원허브 방면"))
        assertEquals(CardOutcome.DEFERRED, r.notifier.onEvent(ev("e1", "HARD_BRAKE")))
        r.reading = ahead(280, "TURN_LEFT", "강릉항 방면")
        advanceTimeBy(1_000); runCurrent()
        assertTrue(r.posted.isEmpty())
        assertEquals("같은 읽기에서 새 카드도 미룬다 — 옛 카드만 올리는 모순 없음", CardOutcome.DEFERRED, r.notifier.onEvent(ev("e2", "LOW_ENERGY", source = "veh_B")))
        assertTrue(r.posted.isEmpty())
        assertEquals(2, r.notifier.state.value.held)

        r.reading = ahead(700, "TURN_STRAIGHT", "경포 방면")
        advanceTimeBy(1_000); runCurrent()
        assertEquals("미룬 순서대로", listOf("HARD_BRAKE", "LOW_ENERGY"), r.posted.map { it.type })
        assertEquals(0, r.notifier.state.value.held)
        assertEquals(2, r.notifier.state.value.released)
    }

    @Test
    fun `an unknown, missing or far TBT posts the card at once when nothing is held`() = runTest {
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
    fun `while a card is held one slow TBT answer neither posts the held card nor the new one - three in a row do`() = runTest {
        val r = Rig(this, reading = ahead(120))
        assertEquals(CardOutcome.DEFERRED, r.notifier.onEvent(ev("e1", "HARD_BRAKE")))
        r.reading = NextTurnResult.Unknown // 교차로 재탐색 등으로 0.8 s 안에 답이 없다
        assertEquals("미룬 카드가 있으면 모름이어도 줄 끝에", CardOutcome.DEFERRED, r.notifier.onEvent(ev("e2", "LOW_ENERGY", source = "veh_B")))
        advanceTimeBy(1_000); runCurrent()
        assertTrue("모름 2번 — 아직 회전 직전일 수 있다", r.posted.isEmpty())
        advanceTimeBy(1_000); runCurrent()
        assertEquals("모름 3번 연달아 → 놓는다", listOf("HARD_BRAKE", "LOW_ENERGY"), r.posted.map { it.type })
        assertEquals(0, r.notifier.state.value.held)
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
    fun `after the turn held cards go out first, a new card queues behind them instead of being lost to the 5 s burst limit`() = runTest {
        val r = Rig(this, reading = ahead(200))
        assertEquals(CardOutcome.DEFERRED, r.notifier.onEvent(ev("e1", "LOW_ENERGY", speech = "첫째")))
        assertEquals("같은 종류는 바꿔 넣는다", CardOutcome.DEFERRED, r.notifier.onEvent(ev("e2", "LOW_ENERGY", speech = "둘째")))
        assertEquals(CardOutcome.DEFERRED, r.notifier.onEvent(ev("e3", "HARD_BRAKE")))
        assertEquals(CardOutcome.DEFERRED, r.notifier.onEvent(ev("e4", "WAYPOINT_ARRIVED")))
        assertEquals(3, r.notifier.state.value.held)

        // 회전을 지난 뒤 새 소식이 오면: 미룬 카드가 먼저 나가고(5초에 2장), 한도에 걸린 미룬 카드와 새 카드는 줄에서 기다린다
        r.reading = ahead(800, "TURN_LEFT", "다음 길")
        assertEquals(CardOutcome.DEFERRED, r.notifier.onEvent(ev("e5", "MEMBER_JOINED", source = "veh_B")))
        assertEquals(listOf("둘째", "HARD_BRAKE 문구"), r.posted.map { it.title })
        assertEquals("버린 카드 없음", null, r.notifier.state.value.skipped["burst"])
        assertEquals(2, r.notifier.state.value.held)

        // 줄이 있는 동안 한도에 걸린 또 다른 새 카드도 버리지 않고 줄 끝에
        assertEquals(CardOutcome.DEFERRED, r.notifier.onEvent(ev("e6", "DESTINATION_ARRIVED", source = "veh_C")))
        assertEquals(3, r.notifier.state.value.held)

        advanceTimeBy(4_000); runCurrent()
        assertEquals(2, r.posted.size)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(listOf("LOW_ENERGY", "HARD_BRAKE", "WAYPOINT_ARRIVED", "MEMBER_JOINED"), r.posted.map { it.type })
        advanceTimeBy(5_000); runCurrent()
        assertEquals("미룬 순서대로 모두 나간다", listOf("LOW_ENERGY", "HARD_BRAKE", "WAYPOINT_ARRIVED", "MEMBER_JOINED", "DESTINATION_ARRIVED"), r.posted.map { it.type })
        assertEquals(0, r.notifier.state.value.held)
        assertTrue(r.notifier.state.value.skipped.isEmpty())
    }

    @Test
    fun `a same-type card arriving at release time replaces the held one, so the newest is shown`() = runTest {
        val r = Rig(this, reading = ahead(150))
        assertEquals(CardOutcome.DEFERRED, r.notifier.onEvent(ev("e1", "LOW_ENERGY", source = "veh_B", speech = "20%")))
        r.reading = ahead(900)
        assertEquals(CardOutcome.POSTED, r.notifier.onEvent(ev("e2", "LOW_ENERGY", source = "veh_B", speech = "12%")))
        assertEquals(listOf("12%"), r.posted.map { it.title })
        assertEquals("새 카드는 미룬 적이 없으니 released 로 세지 않는다", 0, r.notifier.state.value.released)
        assertEquals(0, r.notifier.state.value.held)
    }

    @Test
    fun `with nothing held a burst-limited card is still skipped (5 s, 2 cards)`() = runTest {
        val r = Rig(this, reading = ahead(900))
        assertEquals(CardOutcome.POSTED, r.notifier.onEvent(ev("e1", "LOW_ENERGY")))
        assertEquals(CardOutcome.POSTED, r.notifier.onEvent(ev("e2", "HARD_BRAKE")))
        assertEquals(CardOutcome.SKIPPED, r.notifier.onEvent(ev("e3", "MEMBER_JOINED", source = "veh_B")))
        assertEquals(1, r.notifier.state.value.skipped["burst"])
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
