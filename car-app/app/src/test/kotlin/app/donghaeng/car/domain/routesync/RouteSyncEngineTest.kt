package app.donghaeng.car.domain.routesync

import app.donghaeng.car.adapter.navi.FakeNaviAdapter
import app.donghaeng.car.domain.model.NaviEvent
import app.donghaeng.car.domain.model.Priority
import app.donghaeng.car.domain.session.GroupSession
import app.donghaeng.car.domain.session.ReceivedProposal
import app.donghaeng.protocol.Ack
import app.donghaeng.protocol.GroupTopics
import app.donghaeng.protocol.Place
import app.donghaeng.protocol.RouteChanged
import app.donghaeng.protocol.RouteFollowProposal
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RouteSyncEngineTest {

    private val dest = Place(35.1595, 126.8526, "광주송정역")
    private val waypoint = Place(37.2963, 127.4120, "덕평휴게소")
    private val memberSession = GroupSession("grp_1", "귀성길", "veh_A", dest, GroupTopics("s", "e"), 0L, isLeader = false)
    private val leaderSession = memberSession.copy(isLeader = true)

    private val session = MutableStateFlow<GroupSession?>(null)
    private val proposals = MutableSharedFlow<ReceivedProposal>(extraBufferCapacity = 8)
    private val naviEvents = MutableSharedFlow<NaviEvent>(extraBufferCapacity = 8)
    private val navi = FakeNaviAdapter()
    private val routeChanges = mutableListOf<RouteChanged>()
    private val acks = mutableListOf<Ack>()
    private val spoken = mutableListOf<Pair<String, Priority>>()

    private fun engine(scheduler: kotlinx.coroutines.test.TestCoroutineScheduler) = RouteSyncEngine(
        session = session, proposals = proposals, naviEvents = naviEvents, navi = navi,
        publishRouteChanged = { routeChanges += it; true }, publishAck = { acks += it; true },
        speak = { t, p -> spoken += t to p },
        dispatcher = StandardTestDispatcher(scheduler), clock = { scheduler.currentTime },
    )

    private fun proposal(id: String = "prp_1", wps: List<Place> = listOf(waypoint)) =
        ReceivedProposal(RouteFollowProposal(id, dest, wps, "선두차가 덕평휴게소를 경유지로 추가했어요. 따라갈까요?"), receivedAtMs = 0L)

    @Test
    fun `member hears the proposal, accepting routes the navi with waypoints and acks true`() = runTest {
        session.value = memberSession
        val e = engine(testScheduler); e.start(); runCurrent()
        try {
            proposals.emit(proposal()); runCurrent()
            assertNotNull(e.state.value.pending)
            val pending = e.state.value.pending!!
            assertEquals("prp_1", pending.proposalId)
            assertEquals(30_000L, pending.expiresAtMs - pending.receivedAtMs)
            assertEquals(listOf("선두차가 덕평휴게소를 경유지로 추가했어요. 따라갈까요?" to Priority.NORMAL), spoken)

            e.accept(); runCurrent()
            assertNull(e.state.value.pending)
            assertEquals(listOf(Ack("prp_1", true)), acks)
            assertEquals(dest, navi.currentDestination())
            assertEquals(1, e.state.value.accepted)

            // 30초 무응답 → 자동 거절
            proposals.emit(proposal("prp_2")); runCurrent()
            advanceTimeBy(30_001); runCurrent()
            assertNull(e.state.value.pending)
            assertEquals(Ack("prp_2", false), acks.last())
            assertEquals(1, e.state.value.autoDeclined)

            // 수동 거절
            proposals.emit(proposal("prp_3")); runCurrent()
            e.decline(); runCurrent()
            assertEquals(Ack("prp_3", false), acks.last())
            assertEquals(1, e.state.value.declined)

            // 자동 따라가기
            e.setAutoFollow(true)
            proposals.emit(proposal("prp_4", wps = emptyList())); runCurrent()
            assertEquals(Ack("prp_4", true), acks.last())
            assertEquals("auto-follow does not ask", 3, spoken.size)   // 세 번째까지만 읽었다
        } finally { e.stop() }
    }

    @Test
    fun `proposals without a group are ignored and a group end clears the pending one`() = runTest {
        val e = engine(testScheduler); e.start(); runCurrent()
        try {
            proposals.emit(proposal()); runCurrent()
            assertNull(e.state.value.pending)
            session.value = memberSession; runCurrent()
            proposals.emit(proposal("prp_9")); runCurrent()
            assertNotNull(e.state.value.pending)
            session.value = null; runCurrent()
            assertNull(e.state.value.pending)
            advanceTimeBy(31_000); runCurrent()
            assertTrue("no ack after the group ended", acks.isEmpty())
        } finally { e.stop() }
    }

    @Test
    fun `leader publishes ROUTE_CHANGED once per debounced change, members never do`() = runTest {
        session.value = leaderSession
        val e = engine(testScheduler); e.start(); runCurrent()
        try {
            // 구독 직후 현재 목적지 = 그룹 목적지, 경유지 없음 → 변화 아님
            naviEvents.emit(NaviEvent.DestinationChanged(dest)); runCurrent()
            advanceTimeBy(2_500); runCurrent()
            assertTrue(routeChanges.isEmpty())
            // 경유지 추가 + 같은 목적지 재통지 → 2초 디바운스 뒤 1건
            naviEvents.emit(NaviEvent.WaypointChanged(listOf(waypoint))); runCurrent()
            naviEvents.emit(NaviEvent.DestinationChanged(dest)); runCurrent()
            advanceTimeBy(2_500); runCurrent()
            assertEquals(1, routeChanges.size)
            assertEquals(listOf(waypoint), routeChanges.single().waypoints)
            // 같은 내용 반복 → 발행 없음
            naviEvents.emit(NaviEvent.WaypointChanged(listOf(waypoint))); runCurrent()
            advanceTimeBy(2_500); runCurrent()
            assertEquals(1, routeChanges.size)
            // 목적지 변경 → 발행
            val newDest = Place(37.7719, 128.9479, "경포해변")
            naviEvents.emit(NaviEvent.DestinationChanged(newDest)); runCurrent()
            advanceTimeBy(2_500); runCurrent()
            assertEquals(2, routeChanges.size)
            assertEquals(newDest, routeChanges.last().destination)
            assertEquals(2, e.state.value.routeChangedSent)
            // 테스트 발행
            assertTrue(e.publishTestRouteChange(RouteSyncEngine.DEMO_WAYPOINT))
            assertEquals(3, routeChanges.size)

            // 멤버는 발행하지 않는다
            session.value = memberSession; runCurrent()
            naviEvents.emit(NaviEvent.WaypointChanged(emptyList())); runCurrent()
            advanceTimeBy(2_500); runCurrent()
            assertEquals(3, routeChanges.size)
            assertTrue(!e.publishTestRouteChange(RouteSyncEngine.DEMO_WAYPOINT))
        } finally { e.stop() }
    }
}
