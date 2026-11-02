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

    /** ACK 를 보낸 가상 시각(RA-2 상한 확인) */
    private val ackTimes = mutableListOf<Long>()
    private val spoken = mutableListOf<Pair<String, Priority>>()

    private val driving = MutableStateFlow(false)
    private val asked = mutableListOf<String>()

    private fun engine(
        scheduler: kotlinx.coroutines.test.TestCoroutineScheduler,
        joinProposal: Boolean = false,
        withDriving: Boolean = false,
        ask: (suspend (String) -> VoiceAnswer?)? = null,
    ) = RouteSyncEngine(
        session = session, proposals = proposals, naviEvents = naviEvents, navi = navi,
        publishRouteChanged = { routeChanges += it; true }, publishAck = { acks += it; ackTimes += scheduler.currentTime; true },
        speak = { t, p -> spoken += t to p },
        proposeDestinationOnJoin = joinProposal,
        dispatcher = StandardTestDispatcher(scheduler), clock = { scheduler.currentTime },
        drivingFlow = if (withDriving) driving else null,
        askYesNo = ask?.let { a ->
            val wrapped: suspend (String) -> VoiceAnswer? = { q -> asked += q; a(q) }
            wrapped
        },
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

    // ---------------------------------------------------------------- A006/A034 주행 중 제안

    @Test
    fun `A034 - while driving the proposal is asked by voice, yes follows and no declines`() = runTest {
        session.value = memberSession
        driving.value = true
        var answer: VoiceAnswer? = VoiceAnswer.YES
        val e = engine(testScheduler, withDriving = true, ask = { answer }); e.start(); runCurrent()
        try {
            proposals.emit(proposal("prp_y")); runCurrent()
            assertEquals("질문 + '네/아니' 안내", listOf("선두차가 덕평휴게소를 경유지로 추가했어요. 따라갈까요? ${RouteSyncEngine.ASK_SUFFIX}"), asked)
            assertNull(e.state.value.pending)
            assertEquals(Ack("prp_y", true), acks.last())
            assertEquals(dest, navi.currentDestination())
            assertEquals(1, e.state.value.voiceAnswered)

            answer = VoiceAnswer.NO
            proposals.emit(proposal("prp_n")); runCurrent()
            assertNull(e.state.value.pending)
            assertEquals(Ack("prp_n", false), acks.last())
            assertEquals(1, e.state.value.declined)
        } finally { e.stop() }
    }

    @Test
    fun `A006 - no answer while driving holds the proposal instead of a silent 30 s decline, parking re-exposes it`() = runTest {
        session.value = memberSession
        driving.value = true
        val e = engine(testScheduler, withDriving = true, ask = { VoiceAnswer.NO_ANSWER }); e.start(); runCurrent()
        try {
            proposals.emit(proposal("prp_h")); runCurrent()
            val held = e.state.value.pending
            assertNotNull(held)
            assertTrue(held!!.heldUntilParked)
            assertEquals(1, e.state.value.held)
            assertTrue(spoken.any { it.first == RouteSyncEngine.SPEECH_HOLD })
            // 예전처럼 30초에 자동 거절하지 않는다
            advanceTimeBy(60_000); runCurrent()
            assertNotNull(e.state.value.pending)
            assertTrue(acks.isEmpty())

            // 정차 → (정차가 5 s 이어지면) 다시 보이고 다시 읽는다, 그때부터 30초
            spoken.clear()
            driving.value = false; runCurrent()
            assertTrue("정차 직후에는 아직 보류(RA-2)", e.state.value.pending!!.heldUntilParked)
            advanceTimeBy(RouteSyncEngine.PARK_SETTLE_MS); runCurrent()
            val again = e.state.value.pending!!
            assertTrue(!again.heldUntilParked)
            assertEquals(30_000L, again.expiresAtMs - testScheduler.currentTime)
            assertEquals(listOf(held.speech to Priority.NORMAL), spoken)
            e.accept(); runCurrent()
            assertEquals(Ack("prp_h", true), acks.single())
        } finally { e.stop() }
    }

    @Test
    fun `A006 - a held proposal expires after 10 minutes without parking, an unavailable voice ask reads the question and holds`() = runTest {
        session.value = memberSession
        driving.value = true
        val e = engine(testScheduler, withDriving = true, ask = { null }); e.start(); runCurrent()
        try {
            proposals.emit(proposal("prp_x")); runCurrent()
            assertEquals("물을 수 없으면 질문을 그대로 읽는다", "선두차가 덕평휴게소를 경유지로 추가했어요. 따라갈까요?", spoken.first().first)
            assertTrue(e.state.value.pending!!.heldUntilParked)
            advanceTimeBy(RouteSyncEngine.HOLD_MAX_MS + 1); runCurrent()
            assertNull(e.state.value.pending)
            assertEquals(Ack("prp_x", false), acks.single())
            assertEquals(1, e.state.value.autoDeclined)
            assertEquals(RouteSyncEngine.SPEECH_HOLD_EXPIRED, spoken.last().first)
        } finally { e.stop() }
    }

    /**
     * RA-2(리뷰 프로브): 정차 20 s·주행 60 s 를 15번(20분) — 예전엔 정차마다 질문 전체·출발마다 '정차하면 다시 물어볼게요'(음성 30회),
     * 상한이 매번 다시 10분으로 늘어 ACK 가 끝내 가지 않았다. 이제 상한은 처음 보류한 때부터, 다시 읽기는 한 번, '정차하면…'도 한 번.
     */
    @Test
    fun `RA-2 - stop and go keeps the first hold deadline, re-reads once and acks false within the cap`() = runTest {
        session.value = memberSession
        driving.value = true
        val e = engine(testScheduler, withDriving = true, ask = { VoiceAnswer.NO_ANSWER }); e.start(); runCurrent()
        try {
            proposals.emit(proposal("prp_sg")); runCurrent()
            val speech = e.state.value.pending!!.speech
            assertTrue(e.state.value.pending!!.heldUntilParked)
            repeat(15) {
                advanceTimeBy(60_000); runCurrent()          // 주행 60 s
                driving.value = false; runCurrent()
                advanceTimeBy(20_000); runCurrent()          // 신호 대기 20 s(카드는 보이지만 답하지 않음)
                driving.value = true; runCurrent()
            }
            assertNull("상한이 지나 끝났다", e.state.value.pending)
            assertEquals(listOf(Ack("prp_sg", false)), acks)
            assertTrue("ACK false 는 처음 보류한 때부터 10분(+카드 30초) 안 — 실제 ${ackTimes.single()}ms", ackTimes.single() <= RouteSyncEngine.HOLD_MAX_MS + RouteSyncEngine.AUTO_DECLINE_MS)
            assertEquals("'정차하면 다시 물어볼게요'는 처음 한 번", 1, spoken.count { it.first == RouteSyncEngine.SPEECH_HOLD })
            assertEquals("정차해 다시 읽기는 한 번", 1, spoken.count { it.first == speech })
            assertTrue("음성 상한(보류·다시 읽기·넘어감 3줄): $spoken", spoken.size <= 3)
            assertEquals(1, e.state.value.held)
            assertEquals(1, e.state.value.autoDeclined)
        } finally { e.stop() }
    }

    @Test
    fun `RA-2 - a stop shorter than the settle time does not re-expose, and driving on does not repeat the hold line`() = runTest {
        session.value = memberSession
        driving.value = true
        val e = engine(testScheduler, withDriving = true, ask = { VoiceAnswer.NO_ANSWER }); e.start(); runCurrent()
        try {
            proposals.emit(proposal("prp_short")); runCurrent()
            val deadline = e.state.value.pending!!.expiresAtMs
            spoken.clear()
            driving.value = false; runCurrent()
            advanceTimeBy(RouteSyncEngine.PARK_SETTLE_MS - 2_000); runCurrent()   // 정체 속 3 s 멈춤
            driving.value = true; runCurrent()
            advanceTimeBy(30_000); runCurrent()
            val p = e.state.value.pending!!
            assertTrue("다시 보이지 않았다", p.heldUntilParked)
            assertEquals("상한 그대로", deadline, p.expiresAtMs)
            assertTrue("다시 읽거나 '정차하면…'을 되풀이하지 않는다: $spoken", spoken.isEmpty())
            // 다시 보인 뒤 답 없이 출발 → 다시 보류해도 상한은 처음 그대로
            driving.value = false; runCurrent()
            advanceTimeBy(RouteSyncEngine.PARK_SETTLE_MS); runCurrent()
            assertTrue(!e.state.value.pending!!.heldUntilParked)
            driving.value = true; runCurrent()
            assertEquals(deadline, e.state.value.pending!!.expiresAtMs)
            assertEquals(1, e.state.value.held)
        } finally { e.stop() }
    }

    @Test
    fun `A006 - driving off before answering a parked proposal holds it until the next stop`() = runTest {
        session.value = memberSession
        driving.value = false
        val e = engine(testScheduler, withDriving = true); e.start(); runCurrent()
        try {
            proposals.emit(proposal("prp_p")); runCurrent()
            assertTrue(!e.state.value.pending!!.heldUntilParked)
            advanceTimeBy(10_000); runCurrent()
            driving.value = true; runCurrent()
            advanceTimeBy(25_000); runCurrent()
            assertNotNull("30초 자동 거절 대신 보류", e.state.value.pending)
            assertTrue(e.state.value.pending!!.heldUntilParked)
            assertTrue(acks.isEmpty())
        } finally { e.stop() }
    }

    @Test
    fun `A057 - a superseded server proposal is answered false, and a double decline acks once`() = runTest {
        session.value = memberSession
        val e = engine(testScheduler); e.start(); runCurrent()
        try {
            proposals.emit(proposal("prp_1")); runCurrent()
            proposals.emit(proposal("prp_2")); runCurrent()
            assertEquals(listOf(Ack("prp_1", false)), acks)
            assertEquals(1, e.state.value.superseded)
            e.decline(); e.decline(); runCurrent()
            assertEquals(listOf(Ack("prp_1", false), Ack("prp_2", false)), acks)
            assertEquals(1, e.state.value.declined)
        } finally { e.stop() }
    }

    @Test
    fun `A069 - a car that is now the leader ignores late proposals`() = runTest {
        session.value = leaderSession
        val e = engine(testScheduler); e.start(); runCurrent()
        try {
            proposals.emit(proposal("prp_old")); runCurrent()
            assertNull(e.state.value.pending)
            assertTrue(spoken.isEmpty())
        } finally { e.stop() }
    }

    @Test
    fun `A058 - after an app restart the leader's first navi route is a baseline, not a new ROUTE_CHANGED`() = runTest {
        advanceTimeBy(600_000)
        session.value = leaderSession.copy(joinedAtMs = 1_000L)   // 앱 재시작 전부터 있던(복원된) 세션
        val e = engine(testScheduler); e.start(); runCurrent()
        try {
            val x = Place(37.7719, 128.9479, "경포해변")
            // 구독 직후 어댑터가 당겨 온 현재값(snapshot) — 경유지는 당겨 오지 않아 빠져 있다
            naviEvents.emit(NaviEvent.DestinationChanged(x, snapshot = true)); runCurrent()
            advanceTimeBy(2_500); runCurrent()
            assertTrue("재시작 직후 현재값은 다시 보내지 않는다", routeChanges.isEmpty())
            // 그 뒤 진짜 변화는 보낸다
            naviEvents.emit(NaviEvent.WaypointChanged(listOf(waypoint))); runCurrent()
            advanceTimeBy(2_500); runCurrent()
            assertEquals(1, routeChanges.size)
            assertEquals(listOf(waypoint), routeChanges.single().waypoints)
        } finally { e.stop() }
    }

    /** RA-1(리뷰 프로브): 복원된 방장 — 첫 값이 그룹 목적지·경유지 없음이면 기준선이 안 잡혀, 9.5 s 뒤 실제 휴게소 추가가 '기준선'으로 버려졌다 */
    @Test
    fun `RA-1 - after a restart the first bundle is the only baseline, a waypoint added seconds later is published`() = runTest {
        for (snapshot in listOf(true, false)) {
            routeChanges.clear()
            session.value = leaderSession.copy(joinedAtMs = testScheduler.currentTime)   // 엔진 시작 전 합류 = 복원된 세션
            advanceTimeBy(60_000)
            val e = engine(testScheduler); e.start(); runCurrent()
            try {
                naviEvents.emit(NaviEvent.DestinationChanged(dest, snapshot = snapshot)); runCurrent()   // 그룹 목적지 그대로
                advanceTimeBy(9_500); runCurrent()
                naviEvents.emit(NaviEvent.WaypointChanged(listOf(waypoint))); runCurrent()
                advanceTimeBy(2_500); runCurrent()
                assertEquals("snapshot=$snapshot — 시작 10초 안의 실제 경유지 추가도 보낸다", 1, routeChanges.size)
                assertEquals(listOf(waypoint), routeChanges.single().waypoints)
            } finally { e.stop() }
            session.value = null; runCurrent()
        }
    }

    @Test
    fun `RA-1 - no destination at restart, a destination set right after is published`() = runTest {
        advanceTimeBy(600_000)
        session.value = leaderSession.copy(joinedAtMs = 1_000L)
        val e = engine(testScheduler); e.start(); runCurrent()
        try {
            naviEvents.emit(NaviEvent.DestinationChanged(null, snapshot = true)); runCurrent()   // 안내 중 아님
            advanceTimeBy(5_000); runCurrent()
            val y = Place(37.7719, 128.9479, "경포해변")
            naviEvents.emit(NaviEvent.DestinationChanged(y)); runCurrent()
            advanceTimeBy(2_500); runCurrent()
            assertEquals(listOf(y), routeChanges.map { it.destination })
        } finally { e.stop() }
    }

    @Test
    fun `RA-1 - when the navi gave no current value, the first change notification right after a restart is not swallowed`() = runTest {
        advanceTimeBy(600_000)
        session.value = leaderSession.copy(joinedAtMs = 1_000L)
        val e = engine(testScheduler); e.start(); runCurrent()
        try {
            // 안내 중이 아니면 구독 직후 응답이 없다 → 첫 경로 이벤트가 곧 운전자의 실제 변경(onDestinationChanged)
            advanceTimeBy(8_000)
            val y = Place(37.7719, 128.9479, "경포해변")
            naviEvents.emit(NaviEvent.DestinationChanged(y)); runCurrent()
            advanceTimeBy(2_500); runCurrent()
            assertEquals(listOf(y), routeChanges.map { it.destination })
        } finally { e.stop() }
    }

    @Test
    fun `A056 - the next stop clears when the navi drops the followed waypoint, A005 - the followed destination is exposed`() = runTest {
        session.value = memberSession
        val e = engine(testScheduler); e.start(); runCurrent()
        try {
            proposals.emit(proposal()); runCurrent()
            e.accept(); runCurrent()
            assertEquals(waypoint, e.state.value.activeWaypoint)
            assertEquals(dest, e.state.value.followedDestination)
            // 가짜 내비는 수락 즉시 경유지 목록을 낸다(W 포함) → 유지
            naviEvents.emit(NaviEvent.WaypointChanged(listOf(waypoint))); runCurrent()
            assertEquals(waypoint, e.state.value.activeWaypoint)
            // 운전자가 내비에서 W 를 지움 → 다음 정차 없음
            naviEvents.emit(NaviEvent.WaypointChanged(emptyList())); runCurrent()
            assertNull(e.state.value.activeWaypoint)
            // 다시 생기면 돌아온다
            naviEvents.emit(NaviEvent.WaypointChanged(listOf(waypoint))); runCurrent()
            assertEquals(waypoint, e.state.value.activeWaypoint)
            // 그룹이 바뀌면 비운다
            session.value = memberSession.copy(groupId = "grp_9"); runCurrent()
            assertNull(e.state.value.followedDestination)
        } finally { e.stop() }
    }

    @Test
    fun `A056 - a waypoint the navi never listed (test publish) stays as the next stop`() = runTest {
        session.value = leaderSession
        val e = engine(testScheduler); e.start(); runCurrent()
        try {
            assertTrue(e.publishTestRouteChange(RouteSyncEngine.DEMO_WAYPOINT))
            naviEvents.emit(NaviEvent.WaypointChanged(emptyList())); runCurrent()
            assertEquals(RouteSyncEngine.DEMO_WAYPOINT, e.state.value.activeWaypoint)
        } finally { e.stop() }
    }
}
