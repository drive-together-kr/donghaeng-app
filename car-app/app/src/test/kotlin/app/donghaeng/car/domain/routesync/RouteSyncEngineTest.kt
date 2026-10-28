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

    private fun engine(scheduler: kotlinx.coroutines.test.TestCoroutineScheduler, joinProposal: Boolean = false) = RouteSyncEngine(
        session = session, proposals = proposals, naviEvents = naviEvents, navi = navi,
        publishRouteChanged = { routeChanges += it; true }, publishAck = { acks += it; true },
        speak = { t, p -> spoken += t to p },
        proposeDestinationOnJoin = joinProposal,
        dispatcher = StandardTestDispatcher(scheduler), clock = { scheduler.currentTime },
    )

    @Test
    fun `a member that just joined is asked to navigate to the leader destination and accepting sends no ack`() = runTest {
        val e = engine(testScheduler, joinProposal = true); e.start(); runCurrent()
        try {
            session.value = memberSession.copy(joinedAtMs = testScheduler.currentTime); runCurrent()
            val pending = e.state.value.pending
            assertNotNull(pending)
            assertTrue(pending!!.local)
            assertEquals(dest, pending.destination)
            assertEquals(listOf("선두차 목적지 광주송정역까지 안내할까요?" to Priority.NORMAL), spoken)

            e.accept(); runCurrent()
            assertNull(e.state.value.pending)
            assertEquals(dest, navi.currentDestination())
            assertTrue("local proposal never acks", acks.isEmpty())
            assertEquals(1, e.state.value.accepted)

            // 같은 그룹의 세션 갱신(초대 갱신 등)에는 다시 묻지 않는다
            session.value = memberSession.copy(joinedAtMs = testScheduler.currentTime, name = "renamed"); runCurrent()
            assertNull(e.state.value.pending)

            // 새 그룹에 합류하면 다시 제안, 30초 무응답 → 자동 거절(ACK 없음)
            session.value = memberSession.copy(groupId = "grp_2", joinedAtMs = testScheduler.currentTime); runCurrent()
            assertNotNull(e.state.value.pending)
            advanceTimeBy(30_001); runCurrent()
            assertNull(e.state.value.pending)
            assertEquals(1, e.state.value.autoDeclined)
            assertTrue(acks.isEmpty())
        } finally { e.stop() }
    }

    @Test
    fun `restored sessions and leaders get no join proposal`() = runTest {
        val e = engine(testScheduler, joinProposal = true); e.start(); runCurrent()
        try {
            advanceTimeBy(120_000); runCurrent()
            session.value = memberSession   // joinedAtMs = 0 → 120초 전 합류 = 앱 재시작 복원
            runCurrent()
            assertNull(e.state.value.pending)
            session.value = leaderSession.copy(groupId = "grp_lead", joinedAtMs = testScheduler.currentTime); runCurrent()
            assertNull(e.state.value.pending)
            assertTrue(spoken.isEmpty())
        } finally { e.stop() }
    }

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

    @Test
    fun `active waypoint follows accepted and published routes and clears on arrival or group end`() = runTest {
        session.value = memberSession
        val e = engine(testScheduler); e.start(); runCurrent()
        try {
            assertNull(e.state.value.activeWaypoint)
            // 멤버: 경유지 있는 제안 수락 → 다음 정차지 = 첫 경유지
            proposals.emit(proposal()); runCurrent()
            e.accept(); runCurrent()
            assertEquals(waypoint, e.state.value.activeWaypoint)
            // 경유지 도착 → 지난 정차지
            naviEvents.emit(NaviEvent.WaypointArrived(index = 0, waypoint = waypoint)); runCurrent()
            assertNull(e.state.value.activeWaypoint)
            // 경유지 없는 경로 수락 → 없음 유지, 거절은 바꾸지 않는다
            proposals.emit(proposal("prp_2")); runCurrent()
            e.decline(); runCurrent()
            assertNull(e.state.value.activeWaypoint)
            proposals.emit(proposal("prp_3")); runCurrent()
            e.accept(); runCurrent()
            assertEquals(waypoint, e.state.value.activeWaypoint)
            proposals.emit(proposal("prp_4", wps = emptyList())); runCurrent()
            e.accept(); runCurrent()
            assertNull(e.state.value.activeWaypoint)

            // 선두: 발행한 경유지가 다음 정차지
            session.value = leaderSession.copy(groupId = "grp_lead"); runCurrent()
            assertTrue(e.publishTestRouteChange(RouteSyncEngine.DEMO_WAYPOINT))
            assertEquals(RouteSyncEngine.DEMO_WAYPOINT, e.state.value.activeWaypoint)
            // 그룹 종료 → 비움
            session.value = null; runCurrent()
            assertNull(e.state.value.activeWaypoint)
        } finally { e.stop() }
    }
}
