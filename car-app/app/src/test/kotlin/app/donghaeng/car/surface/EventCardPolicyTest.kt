package app.donghaeng.car.surface

import app.donghaeng.car.domain.session.ReceivedGroupEvent
import app.donghaeng.car.feature.drive.Tone
import app.donghaeng.protocol.GroupEvent
import app.donghaeng.protocol.Severity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 이벤트 알림 카드 판단 — 대상 종류·내 차 제외·신선도·카테고리·id 교체·5초 수명, 카드는 CARDS_ONLY 에서만 (TBT 게이트는 `TurnCardGateTest`) */
class EventCardPolicyTest {

    private val serverNow = 1_700_000_000_000L
    private var seq = 0

    private fun event(type: String, source: String? = "veh_A", ageMs: Long = 1_000, speech: String? = "$type 문구") =
        ReceivedGroupEvent(GroupEvent("e${seq++}", type, source, Severity.INFO, speech, JsonObject(emptyMap())), envelopeTsMs = serverNow - ageMs, receivedAtMs = 0L)

    private fun decide(e: ReceivedGroupEvent) = EventCardPolicy.decide(e, myVehicleId = "veh_ME", serverNowMs = serverNow)

    private fun post(e: ReceivedGroupEvent): EventCard = (decide(e) as CardDecision.Post).card

    @Test
    fun `only target types become cards - VOICE_MESSAGE is matched by name before the enum exists`() {
        listOf("LOW_ENERGY", "HARD_BRAKE", "AIRBAG_DEPLOYED", "WAYPOINT_ARRIVED", "DESTINATION_ARRIVED", "MEMBER_JOINED", "MEMBER_OFFLINE", "PASSENGER_MESSAGE", "VOICE_MESSAGE")
            .forEach { assertTrue(it, decide(event(it)) is CardDecision.Post) }
        listOf("STOPPED", "RESUMED", "ROUTE_CHANGED", "MEMBER_LEFT", "GROUP_ENDED", "REST_STOP_PROPOSAL", "SOMETHING_NEW")
            .forEach { assertEquals(it, CardDecision.Skip("not-target"), decide(event(it))) }
    }

    @Test
    fun `my own car events and events older than 60 s on the server clock are skipped`() {
        assertEquals(CardDecision.Skip("own"), decide(event("HARD_BRAKE", source = "veh_ME")))
        assertEquals(CardDecision.Skip("stale"), decide(event("HARD_BRAKE", ageMs = 60_001)))
        assertTrue(decide(event("HARD_BRAKE", ageMs = 60_000)) is CardDecision.Post)
        assertTrue("서버 생성 이벤트(출처 없음)는 대상", decide(event("MEMBER_OFFLINE", source = null)) is CardDecision.Post)
    }

    @Test
    fun `category is msg for messages and navigation otherwise - never status or car_warning (Pleos heads-up only for call, msg, navigation)`() {
        assertEquals("navigation", post(event("WAYPOINT_ARRIVED")).category)
        assertEquals("navigation", post(event("DESTINATION_ARRIVED")).category)
        assertEquals("msg", post(event("PASSENGER_MESSAGE")).category)
        assertEquals("msg", post(event("VOICE_MESSAGE")).category)
        listOf("LOW_ENERGY", "HARD_BRAKE", "AIRBAG_DEPLOYED", "MEMBER_JOINED", "MEMBER_OFFLINE").forEach { assertEquals(it, "navigation", post(event(it)).category) }
        EventCardPolicy.TARGET_TYPES.forEach {
            assertNotEquals("car_warning", EventCardPolicy.categoryOf(it))
            assertTrue(it, EventCardPolicy.categoryOf(it) in setOf("navigation", "msg"))
        }
    }

    @Test
    fun `same type reuses the same notification id so cards replace instead of stacking`() {
        val a = post(event("LOW_ENERGY", speech = "첫째"))
        val b = post(event("LOW_ENERGY", speech = "둘째"))
        val c = post(event("HARD_BRAKE"))
        assertEquals(a.notificationId, b.notificationId)
        assertNotEquals(a.notificationId, c.notificationId)
        assertEquals(EventCardPolicy.TARGET_TYPES.size, EventCardPolicy.TARGET_TYPES.map(EventCardPolicy::notificationIdOf).toSet().size)
        assertEquals("둘째", b.title)
        assertEquals(Tone.DANGER, c.tone)
        assertEquals("문구 없으면 종류 라벨", "저잔량", post(event("LOW_ENERGY", speech = null)).title)
        assertEquals("카드 표시 시간 5초(2026-10-28 사용자 지시, 8초 → 5초)", 5_000L, EventCardPolicy.TIMEOUT_MS)
    }

    @Test
    fun `notifier posts cards only in CARDS_ONLY - PANEL mode relies on the panel event line and voice, no group means nothing`() = runTest {
        val mode = MutableStateFlow(SurfaceMode.OFF)
        val posted = mutableListOf<EventCard>()
        var lookups = 0
        val notifier = EventCardNotifier(
            events = emptyFlow(), myVehicleId = { "veh_ME" }, serverNow = { serverNow }, mode = mode, groupName = { "강릉 가족 여행" },
            poster = { card, _ -> posted += card; true },
            nextTurn = { lookups++; app.donghaeng.car.domain.model.NextTurnResult.Unknown },
        )
        assertEquals(CardOutcome.SKIPPED, notifier.onEvent(event("HARD_BRAKE")))
        mode.value = SurfaceMode.CARDS_ONLY
        assertEquals(CardOutcome.POSTED, notifier.onEvent(event("HARD_BRAKE")))
        mode.value = SurfaceMode.PANEL
        assertEquals("패널 모드는 카드 없음", CardOutcome.SKIPPED, notifier.onEvent(event("WAYPOINT_ARRIVED")))
        assertEquals(CardOutcome.SKIPPED, notifier.onEvent(event("LOW_ENERGY", source = "veh_B")))
        assertEquals(CardOutcome.SKIPPED, notifier.onEvent(event("STOPPED")))
        assertEquals(listOf("HARD_BRAKE"), posted.map { it.type })
        assertEquals("패널 모드에서는 TBT 도 묻지 않는다", 1, lookups)
        val s = notifier.state.value
        assertEquals(1, s.posted)
        assertEquals(1, s.skipped["no-group"])
        assertEquals(2, s.skipped["panel-mode"])
        assertEquals(1, s.skipped["not-target"])
    }

    private fun ev(id: String, type: String, source: String? = "veh_A", severity: Severity = Severity.WARN) =
        ReceivedGroupEvent(GroupEvent(id, type, source, severity, "$type 문구", JsonObject(emptyMap())), envelopeTsMs = serverNow - 1_000, receivedAtMs = 0L)

    @Test
    fun `throttle drops duplicate event ids, repeats of the same type and source within 30 s, and more than two cards in 5 s`() {
        val t = CardThrottle()
        fun admit(e: ReceivedGroupEvent, at: Long): String? = t.check(e, at).also { if (it == null) t.record(e, at) }

        assertNull(admit(ev("e1", "MEMBER_OFFLINE", "veh_B"), 0))
        assertEquals("QoS1 중복 수신", "duplicate", admit(ev("e1", "MEMBER_OFFLINE", "veh_B"), 100))
        assertEquals("연결 들락날락", "same-recent", admit(ev("e2", "MEMBER_OFFLINE", "veh_B"), 20_000))
        assertNull("다른 차는 따로", admit(ev("e3", "MEMBER_OFFLINE", "veh_C"), 20_000))
        assertNull(admit(ev("e3b", "HARD_BRAKE", "veh_C"), 20_500))
        assertEquals("5초 안 세 번째 카드", "burst", admit(ev("e4", "LOW_ENERGY", "veh_D"), 21_000))
        assertNull("위험(에어백)은 한도와 상관없이", admit(ev("e5", "AIRBAG_DEPLOYED", "veh_D", Severity.ALERT), 21_500))
        assertEquals("창 = 카드 수명 5초 — 20.5 s·21.5 s 카드가 아직 창 안", "burst", t.check(ev("e5b", "LOW_ENERGY", "veh_E"), 25_400))
        assertNull("5초 지나면 다시(8초를 기다리지 않는다)", t.check(ev("e5c", "LOW_ENERGY", "veh_E"), 26_600))
        assertNull(admit(ev("e6", "LOW_ENERGY", "veh_D"), 30_000))
        assertNull("30초 지나면 같은 차·같은 종류도 다시", admit(ev("e7", "MEMBER_OFFLINE", "veh_B"), 31_000))
        // 메시지는 내용이 매번 달라 같은 차라도 eventId 만 거른다(전체 한도는 적용)
        assertNull(admit(ev("m1", "PASSENGER_MESSAGE", "veh_B", Severity.INFO), 60_000))
        assertNull(admit(ev("m2", "PASSENGER_MESSAGE", "veh_B", Severity.INFO), 61_000))
        assertEquals("duplicate", admit(ev("m2", "PASSENGER_MESSAGE", "veh_B", Severity.INFO), 70_000))
    }

    @Test
    fun `eventId memory is bounded and keeps the most recent ids`() {
        val t = CardThrottle(maxIds = 3, burstMax = 100)
        (1..5).forEach { t.record(ev("id$it", "PASSENGER_MESSAGE"), it * 10_000L) }
        assertNull("오래된 id 는 잊는다", t.check(ev("id1", "PASSENGER_MESSAGE"), 60_000))
        assertEquals("duplicate", t.check(ev("id5", "PASSENGER_MESSAGE"), 60_000))
    }

    @Test
    fun `notifier applies the throttle and a card that could not be posted does not use up the limit`() = runTest {
        var now = 0L
        var enabled = false
        val posted = mutableListOf<String>()
        val notifier = EventCardNotifier(
            events = emptyFlow(), myVehicleId = { "veh_ME" }, serverNow = { serverNow }, mode = MutableStateFlow(SurfaceMode.CARDS_ONLY), groupName = { null },
            poster = { card, _ -> if (enabled) { posted += card.type; true } else false }, clock = { now },
        )
        assertEquals("알림 꺼짐 → 못 올림", CardOutcome.SKIPPED, notifier.onEvent(ev("x1", "HARD_BRAKE")))
        enabled = true
        assertEquals("못 올린 건 기억하지 않아 같은 id 재전달은 올린다", CardOutcome.POSTED, notifier.onEvent(ev("x1", "HARD_BRAKE")))
        assertEquals(CardOutcome.SKIPPED, notifier.onEvent(ev("x1", "HARD_BRAKE")))
        now = 4_000
        assertEquals(CardOutcome.SKIPPED, notifier.onEvent(ev("x2", "HARD_BRAKE")))
        assertEquals(CardOutcome.POSTED, notifier.onEvent(ev("x3", "LOW_ENERGY")))
        assertEquals("5초 안 세 번째", CardOutcome.SKIPPED, notifier.onEvent(ev("x4", "MEMBER_JOINED", "veh_B")))
        assertEquals(listOf("HARD_BRAKE", "LOW_ENERGY"), posted)
        val skipped = notifier.state.value.skipped
        assertEquals(1, skipped["not-posted"])
        assertEquals(1, skipped["duplicate"])
        assertEquals(1, skipped["same-recent"])
        assertEquals(1, skipped["burst"])
    }
}

