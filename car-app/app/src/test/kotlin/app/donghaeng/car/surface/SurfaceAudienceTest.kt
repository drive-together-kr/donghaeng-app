package app.donghaeng.car.surface

import app.donghaeng.car.domain.session.GroupSession
import app.donghaeng.car.domain.session.ReceivedGroupEvent
import app.donghaeng.protocol.GroupEvent
import app.donghaeng.protocol.GroupState
import app.donghaeng.protocol.GroupTopics
import app.donghaeng.protocol.MemberState
import app.donghaeng.protocol.MemberStatus
import app.donghaeng.protocol.Place
import app.donghaeng.protocol.Severity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 급제동 수신 대상 — 알림 카드·패널 소식 줄·정차 대시보드(띠·지난 소식) 경로 (docs/03 §5.4 audience, docs/05 §5.2).
 * impl_02 상황: 선두 A(0) — B(2.1 km 뒤). B 의 급제동은 앞차 A 에게 '앞쪽 급제동'이 아니다 → A 는 카드·소식 줄 없음, 뒤차 B 는 A 의 급제동을 본다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SurfaceAudienceTest {

    private val serverNow = 1_700_000_000_000L
    private var seq = 0

    private fun member(vid: String, dist: Int?) = MemberState(vid, vid, "#2E7DFF", true, 37.0, 127.0, 0f, 80f, 0, null, null, dist, MemberStatus.DRIVING)

    /** 선두 A(0) — B(2.1 km 뒤) */
    private val pair = GroupState("grp_1", "veh_A", listOf(member("veh_A", 0), member("veh_B", 2_100)))

    private fun brake(source: String, audience: List<String>?, type: String = "HARD_BRAKE", ageMs: Long = 1_000) =
        ReceivedGroupEvent(GroupEvent("e${seq++}", type, source, Severity.WARN, "앞쪽 $source 급제동", JsonObject(emptyMap()), audience), envelopeTsMs = serverNow - ageMs, receivedAtMs = serverNow - ageMs)

    // ---------------------------------------------------------------- 알림 카드

    private fun card(e: ReceivedGroupEvent, me: String, state: GroupState?) = EventCardPolicy.decide(e, myVehicleId = me, serverNowMs = serverNow, groupState = state)

    @Test
    fun `card - front car gets no card, rear car does (server audience)`() {
        assertEquals("앞차 A", CardDecision.Skip("not-audience"), card(brake("veh_B", audience = emptyList()), "veh_A", pair))
        assertTrue("뒤차 B", card(brake("veh_A", audience = listOf("veh_B")), "veh_B", pair) is CardDecision.Post)
        assertEquals("audience 에 없으면 스냅샷과 무관하게 없음", CardDecision.Skip("not-audience"), card(brake("veh_A", audience = listOf("veh_C")), "veh_B", pair))
    }

    @Test
    fun `card - empty audience reaches nobody`() {
        assertEquals(CardDecision.Skip("not-audience"), card(brake("veh_B", audience = emptyList()), "veh_A", null))
        assertEquals(CardDecision.Skip("not-audience"), card(brake("veh_B", audience = emptyList()), "veh_C", pair))
    }

    @Test
    fun `card - no audience falls back to the snapshot, unknown posts`() {
        assertEquals("앞차 A", CardDecision.Skip("not-audience"), card(brake("veh_B", audience = null), "veh_A", pair))
        assertTrue("뒤차 B", card(brake("veh_A", audience = null), "veh_B", pair) is CardDecision.Post)
        assertTrue("스냅샷 없음", card(brake("veh_B", audience = null), "veh_A", null) is CardDecision.Post)
        assertTrue("저잔량은 앞뒤 무관", card(brake("veh_B", audience = null, type = "LOW_ENERGY"), "veh_A", pair) is CardDecision.Post)
    }

    @Test
    fun `card notifier reads the live snapshot`() = runTest {
        val state = MutableStateFlow<GroupState?>(pair)
        val posted = mutableListOf<EventCard>()
        val notifier = EventCardNotifier(
            events = emptyFlow(), myVehicleId = { "veh_A" }, serverNow = { serverNow }, mode = MutableStateFlow(SurfaceMode.CARDS_ONLY),
            groupName = { "강릉 가족 여행" }, poster = { c, _ -> posted += c; true }, groupState = { state.value },
        )
        assertEquals("앞차 A 에게 뒤차 B 의 급제동 카드 없음", CardOutcome.SKIPPED, notifier.onEvent(brake("veh_B", audience = null)))
        assertEquals(1, notifier.state.value.skipped["not-audience"])
        state.value = null
        // 카드+음성 모드 + TBT 모름(기본) → 게이트 없이 바로 게시
        assertEquals("스냅샷을 모르면 띄운다", CardOutcome.POSTED, notifier.onEvent(brake("veh_B", audience = null)))
        assertEquals(1, posted.size)
    }

    // ---------------------------------------------------------------- 패널 소식 줄·대시보드 띠·지난 소식 (SurfaceEvents)

    @Test
    fun `panel line and dashboard - events not addressed to me are hidden`() {
        val toRear = brake("veh_A", audience = listOf("veh_B"))
        val toNobody = brake("veh_B", audience = emptyList(), ageMs = 500)
        val events = listOf(toNobody, toRear)
        // 앞차 A: B 의 급제동(audience []) 은 소식 줄·띠·지난 소식 어디에도 없다. A 자신의 급제동은 내 차 출처라 원래 빠진다
        assertNull("앞차 A 소식 줄", FloatingPanelMapper.recentEvent(events, "veh_A", nowMs = serverNow, serverNowMs = serverNow))
        assertTrue("앞차 A 지난 소식", SurfaceEvents.recent(events, "veh_A", 5).isEmpty())
        // 뒤차 B: A 의 급제동(audience [B]) 이 보인다
        assertEquals("앞쪽 veh_A 급제동", FloatingPanelMapper.recentEvent(events, "veh_B", nowMs = serverNow, serverNowMs = serverNow)?.text)
        assertEquals(listOf(toRear.event.eventId), SurfaceEvents.recent(events, "veh_B", 5).map { it.event.eventId })
        // 다른 멤버 C 에게도 빈 목록은 안 보인다
        assertTrue(SurfaceEvents.recent(listOf(toNobody), "veh_C", 5).isEmpty())
    }

    // ---------------------------------------------------------------- SurfaceState: audience 없으면 받는 순간의 스냅샷으로 한 번 판정

    private val group = GroupSession("grp_1", "강릉 가족 여행", "veh_A", Place(37.7, 128.9, "경포해변"), GroupTopics("s", "e"), 0L)

    private fun surfaceFor(me: String, state: MutableStateFlow<GroupState?>, scheduler: kotlinx.coroutines.test.TestCoroutineScheduler) = SurfaceState(
        session = MutableStateFlow(group), groupState = state, events = MutableSharedFlow(), myVehicleId = { me }, connected = MutableStateFlow(true),
        driving = MutableStateFlow(true), canDrawOverlays = MutableStateFlow(true), appVisible = MutableStateFlow(false), positions = InMemoryPanelPositionStore(PanelCorner.TOP_END),
        dispatcher = StandardTestDispatcher(scheduler), clock = { serverNow }, serverClock = { serverNow },
    )

    @Test
    fun `panel - front car never shows the rear car's brake, rear car shows the front car's (snapshot fallback)`() = runTest {
        val state = MutableStateFlow<GroupState?>(pair)
        val front = surfaceFor("veh_A", state, testScheduler).also { it.start() }
        val rear = surfaceFor("veh_B", state, testScheduler).also { it.start() }
        runCurrent()
        try {
            assertFalse("앞차 A: 뒤차 B 의 급제동은 기록하지 않음", front.noteEvent(brake("veh_B", audience = null)))
            runCurrent()
            assertTrue(front.recentEvents.value.isEmpty())
            assertNull(front.panelUi.value!!.event)

            assertTrue("뒤차 B: 앞차 A 의 급제동 기록", rear.noteEvent(brake("veh_A", audience = null)))
            runCurrent()
            assertEquals(1, rear.recentEvents.value.size)
            assertEquals("앞쪽 veh_A 급제동", rear.panelUi.value!!.event?.text)

            // 판정은 받는 순간 한 번 — 뒤에 순서가 바뀌어도(B 가 A 를 앞지름) 이미 받은 소식은 그대로
            state.value = GroupState("grp_1", "veh_A", listOf(member("veh_A", 0), member("veh_B", 0)))
            runCurrent()
            assertEquals(1, rear.recentEvents.value.size)
            assertEquals("앞쪽 veh_A 급제동", rear.panelUi.value!!.event?.text)

            assertFalse("audience 빈 목록은 누구도 기록하지 않음", rear.noteEvent(brake("veh_A", audience = emptyList())))
        } finally {
            front.stop(); rear.stop()
        }
    }
}
