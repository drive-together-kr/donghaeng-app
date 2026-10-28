package app.donghaeng.car.surface

import app.donghaeng.car.domain.session.ReceivedGroupEvent
import app.donghaeng.car.feature.drive.Tone
import app.donghaeng.protocol.GroupEvent
import app.donghaeng.protocol.Severity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 이벤트 알림 카드 판단 — 대상 종류·내 차 제외·신선도·카테고리·id 교체 */
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
    fun `category is navigation for arrivals, msg for messages, status otherwise - never car_warning`() {
        assertEquals("navigation", post(event("WAYPOINT_ARRIVED")).category)
        assertEquals("navigation", post(event("DESTINATION_ARRIVED")).category)
        assertEquals("msg", post(event("PASSENGER_MESSAGE")).category)
        assertEquals("msg", post(event("VOICE_MESSAGE")).category)
        listOf("LOW_ENERGY", "HARD_BRAKE", "AIRBAG_DEPLOYED", "MEMBER_JOINED", "MEMBER_OFFLINE").forEach { assertEquals(it, "status", post(event(it)).category) }
        EventCardPolicy.TARGET_TYPES.forEach { assertNotEquals("car_warning", EventCardPolicy.categoryOf(it)) }
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
        assertEquals(8_000L, EventCardPolicy.TIMEOUT_MS)
    }

    @Test
    fun `notifier posts in PANEL and CARDS_ONLY modes but not without a group`() {
        val mode = MutableStateFlow(SurfaceMode.OFF)
        val posted = mutableListOf<EventCard>()
        val notifier = EventCardNotifier(
            events = emptyFlow(), myVehicleId = { "veh_ME" }, serverNow = { serverNow }, mode = mode, groupName = { "강릉 가족 여행" },
            poster = { card, _ -> posted += card; true },
        )
        assertFalse(notifier.onEvent(event("HARD_BRAKE")))
        mode.value = SurfaceMode.CARDS_ONLY
        assertTrue(notifier.onEvent(event("HARD_BRAKE")))
        mode.value = SurfaceMode.PANEL
        assertTrue(notifier.onEvent(event("WAYPOINT_ARRIVED")))
        assertFalse(notifier.onEvent(event("STOPPED")))
        assertEquals(listOf("HARD_BRAKE", "WAYPOINT_ARRIVED"), posted.map { it.type })
        val s = notifier.state.value
        assertEquals(2, s.posted)
        assertEquals(1, s.skipped["no-group"])
        assertEquals(1, s.skipped["not-target"])
    }
}
