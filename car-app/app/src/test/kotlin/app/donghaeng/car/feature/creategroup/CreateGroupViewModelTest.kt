package app.donghaeng.car.feature.creategroup

import app.donghaeng.car.adapter.navi.FakeNaviAdapter
import app.donghaeng.car.core.common.InMemoryTokenStore
import app.donghaeng.car.core.common.StoredCredentials
import app.donghaeng.car.core.network.GroupApi
import app.donghaeng.car.core.network.mqtt.InMemoryMqttGateway
import app.donghaeng.car.domain.group.GroupRepository
import app.donghaeng.car.domain.session.GroupSession
import app.donghaeng.car.domain.session.InMemoryGroupSessionStore
import app.donghaeng.car.domain.session.MqttSessionManager
import app.donghaeng.car.core.common.TokenClearReason
import app.donghaeng.car.testing.testRefresher
import app.donghaeng.protocol.GroupTopics
import app.donghaeng.protocol.Place
import app.donghaeng.protocol.rest.GroupCreateRequest
import app.donghaeng.protocol.rest.GroupCreateResponse
import app.donghaeng.protocol.rest.GroupDetailResponse
import app.donghaeng.protocol.rest.InviteDto
import app.donghaeng.protocol.rest.MemberDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

/** T17: 폼 → 생성 → QR(Active) → 멤버 폴링 → 종료. 서버는 가짜 [GroupApi] */
@OptIn(ExperimentalCoroutinesApi::class)
class CreateGroupViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val creds = StoredCredentials("jwt", "r", "veh_A", "아빠 차", "usr_A", "tcp://10.0.2.2:1883", "veh_A", "veh_A")
    private val topics = GroupTopics("dh/v1/group/grp_1/state", "dh/v1/group/grp_1/event")

    private class FakeGroupApi : GroupApi {
        var createCalls = 0
        var detailStatus = 200
        var members = listOf(MemberDto("veh_A", "아빠 차", "#2E7DFF", "LEADER", 1L))
        var ended = 0
        var reissued = 0
        lateinit var lastCreate: GroupCreateRequest
        override suspend fun createGroup(body: GroupCreateRequest): Response<GroupCreateResponse> {
            createCalls++; lastCreate = body
            return Response.success(201, GroupCreateResponse("grp_1", "veh_A", InviteDto("inv_x", "http://10.0.2.2:8080/j/inv_x", 1_000_000L + 600_000L), GroupTopics("dh/v1/group/grp_1/state", "dh/v1/group/grp_1/event")))
        }
        override suspend fun reissueInvite(groupId: String, body: Map<String, String>): Response<InviteDto> {
            reissued++; return Response.success(InviteDto("inv_y", "http://10.0.2.2:8080/j/inv_y", 1_000_000L + 1_200_000L))
        }
        var detailEnded = false

        /** GET /groups/{id} 호출 수(RA-6) */
        var detailCalls = 0
        override suspend fun getGroup(groupId: String): Response<GroupDetailResponse> {
            detailCalls++
            return if (detailStatus == 200) Response.success(GroupDetailResponse(groupId, "추석 귀성길", "veh_A", if (detailEnded) "ENDED" else "ACTIVE", members = members))
            else Response.error(detailStatus, """{"error":{"code":"NOT_FOUND","message":"그룹을 찾을 수 없어요.","traceId":"t"}}""".toResponseBody("application/json".toMediaType()))
        }
        override suspend fun leave(groupId: String, body: Map<String, String>): Response<Unit> = app.donghaeng.car.testing.noContent()
        override suspend fun end(groupId: String, body: Map<String, String>): Response<Unit> { ended++; return app.donghaeng.car.testing.noContent() }
    }

    private lateinit var api: FakeGroupApi
    private lateinit var tokens: InMemoryTokenStore
    private lateinit var sessions: InMemoryGroupSessionStore
    private lateinit var gateway: InMemoryMqttGateway
    private lateinit var manager: MqttSessionManager
    private var now = 1_000_000L

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        api = FakeGroupApi()
        tokens = InMemoryTokenStore(creds)
        sessions = InMemoryGroupSessionStore()
        gateway = InMemoryMqttGateway()
        manager = MqttSessionManager(tokens, gateway, sessions, testRefresher(tokens))
        manager.start(dispatcher)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(): CreateGroupViewModel {
        val repo = GroupRepository(api, sessions, manager)
        return CreateGroupViewModel(repo, FakeNaviAdapter(), tokens, manager).also { it.configureForTest({ now }, pollIntervalMs = 5_000, tickMs = 1_000) }
    }

    @Test
    fun `an immediately emitting driving monitor does not crash construction (T29 regression, T30)`() {
        // Main.immediate 에서 isDriving() 이 replay 값을 바로 내면 생성 중 update 가 불린다 — _state 가 먼저 초기화돼 있어야 한다
        Dispatchers.resetMain()
        Dispatchers.setMain(kotlinx.coroutines.test.UnconfinedTestDispatcher())
        val repo = GroupRepository(api, sessions, manager)
        val vm = CreateGroupViewModel(repo, FakeNaviAdapter(), tokens, manager, driving = app.donghaeng.car.core.common.AlwaysParkedDrivingStateMonitor())
        assertTrue(vm.state.value is CreateGroupState.Loading || vm.state.value is CreateGroupState.Form)
    }

    @Test
    fun `form asks for a destination when navi has none, create shows the invite QR and polls members`() = runTest(dispatcher) {
        val vm = viewModel()
        runCurrent()
        val form = vm.state.value as CreateGroupState.Form
        // A123: 시연용 첫 프리셋을 자동으로 고르지 않는다 — 목적지를 골라야 만들 수 있다
        assertNull(form.destination)
        assertTrue(!form.fromNavi && !form.canCreate && !form.notPaired)
        assertEquals("받침 없는 닉네임 → 와(공백 없이)", "아빠 차와 같이 가기", form.name)
        vm.create(); runCurrent()
        assertEquals("목적지 없이는 만들지 않는다", 0, api.createCalls)

        vm.setName("추석 귀성길"); vm.setMaxMembers(4); vm.pickPreset(DESTINATION_PRESETS[2])
        vm.create()
        runCurrent()
        assertEquals(1, api.createCalls)
        assertEquals(GroupCreateRequest("추석 귀성길", DESTINATION_PRESETS[2], 4), api.lastCreate)
        val active = vm.state.value as CreateGroupState.Active
        assertTrue(active.session.isLeader)
        assertEquals("http://10.0.2.2:8080/j/inv_x", active.session.invite?.url)
        assertEquals(600L, active.inviteRemainingS)
        // 생성 차량은 GROUP_JOINED 를 받지 않으므로 스스로 그룹 토픽을 구독한다
        assertTrue(gateway.subscriptions.containsAll(listOf("dh/v1/group/grp_1/state", "dh/v1/group/grp_1/event")))
        assertEquals(listOf("veh_A"), active.members.map { it.vehicleId })

        // 멤버가 늘면 5초 폴링으로 반영, 남은 시간은 1초마다 줄어든다
        api.members = api.members + MemberDto("veh_B", "엄마 차", "#FF6B2E", "MEMBER", 2L)
        now += 5_000; advanceTimeBy(5_001); runCurrent()
        val polled = vm.state.value as CreateGroupState.Active
        assertEquals(2, polled.members.size)
        assertEquals(595L, polled.inviteRemainingS)

        vm.reissueInvite(); runCurrent()
        assertEquals("inv_y", (vm.state.value as CreateGroupState.Active).session.invite?.token)

        vm.endOrLeave(); runCurrent()
        assertEquals(1, api.ended)
        assertNull(sessions.session.value)
        assertTrue(vm.state.value is CreateGroupState.Form)
        assertTrue(gateway.subscriptions.none { it.startsWith("dh/v1/group/") })
    }

    @Test
    fun `a member session (from GROUP_JOINED) shows Active without invite, and 404 on detail clears it`() = runTest(dispatcher) {
        sessions.save(GroupSession("grp_1", "추석 귀성길", "veh_L", Place(35.1, 126.8, "광주"), topics, 1L, isLeader = false))
        val vm = viewModel()
        runCurrent()
        val active = vm.state.value as CreateGroupState.Active
        assertTrue(!active.session.isLeader)
        assertNull(active.inviteRemainingS)
        assertNotNull(active.members)

        api.detailStatus = 404
        advanceTimeBy(5_001); runCurrent()
        assertNull(sessions.session.value)
        assertTrue(vm.state.value is CreateGroupState.Form)
    }

    /** 주행 여부를 테스트가 직접 바꾼다(값이 바뀔 때만 내는 실제 모니터와 같게 StateFlow) */
    private class DrivingFlag : app.donghaeng.car.core.common.DrivingStateMonitor {
        val flag = kotlinx.coroutines.flow.MutableStateFlow(false)
        override fun isDriving(): kotlinx.coroutines.flow.Flow<Boolean> = flag
        override val status = kotlinx.coroutines.flow.MutableStateFlow(app.donghaeng.car.core.common.DrivingStatus(source = "test"))
    }

    @Test
    fun `A008 - a session change while driving keeps the inputs disabled (rule 6)`() = runTest(dispatcher) {
        val driving = DrivingFlag()
        sessions.save(GroupSession("grp_1", "추석 귀성길", "veh_L", Place(35.1, 126.8, "광주"), topics, 1L, isLeader = false))
        val vm = CreateGroupViewModel(GroupRepository(api, sessions, manager), FakeNaviAdapter(), tokens, manager, driving).also { it.configureForTest({ now }, 5_000, 1_000) }
        runCurrent()
        driving.flag.value = true; runCurrent()
        assertTrue((vm.state.value as CreateGroupState.Active).isDriving)
        // 주행 중 방장이 그룹을 끝냄 → Form 으로 바뀌어도 주행 중이면 입력은 막힌 채
        manager.onGroupLeftLocally("grp_1"); runCurrent()
        val form = vm.state.value as CreateGroupState.Form
        assertTrue("새 Form 도 주행 중", form.isDriving)
        vm.pickPreset(DESTINATION_PRESETS[0])
        assertTrue(!(vm.state.value as CreateGroupState.Form).canCreate)
        // 주행 중 다른 그룹 합류(새 Active)도 같다
        sessions.save(GroupSession("grp_2", "새 그룹", "veh_L", Place(35.1, 126.8, "광주"), topics, 2L, isLeader = true))
        runCurrent()
        assertTrue((vm.state.value as CreateGroupState.Active).isDriving)
        vm.reissueInvite(); runCurrent()
        assertEquals("주행 중 초대 재발급 무시(A123)", 0, api.reissued)
        // 폴링 루프를 끝낸다(runTest 가 남은 무한 루프를 돌지 않게)
        manager.onGroupLeftLocally("grp_2"); runCurrent()
    }

    @Test
    fun `A118 - recent news leaves out my own car's events, A000 - an ENDED detail clears the ghost session`() = runTest(dispatcher) {
        sessions.save(GroupSession("grp_1", "추석 귀성길", "veh_L", Place(35.1, 126.8, "광주"), topics, 1L, isLeader = false))
        gateway.emitInbound(
            app.donghaeng.protocol.Topics.vehicleCmd("veh_A"), "GROUP_JOINED", "srv",
            app.donghaeng.protocol.GroupJoined("grp_1", "추석 귀성길", "veh_L", Place(35.1, 126.8, "광주"), topics), app.donghaeng.protocol.GroupJoined.serializer(),
        )
        runCurrent()
        val vm = viewModel()
        runCurrent()
        fun event(id: String, source: String, speech: String) = app.donghaeng.protocol.GroupEvent(id, "LOW_ENERGY", source, app.donghaeng.protocol.Severity.INFO, speech, kotlinx.serialization.json.JsonObject(emptyMap()))
        gateway.emitInbound(topics.event, app.donghaeng.protocol.MessageTypes.EVENT, "srv", event("e1", "veh_A", "아빠 차 배터리가 9% 남았어요."), app.donghaeng.protocol.GroupEvent.serializer(), seq = 2)
        gateway.emitInbound(topics.event, app.donghaeng.protocol.MessageTypes.EVENT, "srv", event("e2", "veh_C", "삼촌 차 배터리가 9% 남았어요."), app.donghaeng.protocol.GroupEvent.serializer(), seq = 3)
        runCurrent()
        assertEquals(listOf("삼촌 차 배터리가 9% 남았어요."), (vm.state.value as CreateGroupState.Active).recentSpeech)

        // 서버는 끝난 그룹에도 200 + ENDED 를 준다 → 유령 세션 정리
        api.detailEnded = true
        advanceTimeBy(5_001); runCurrent()
        assertNull(sessions.session.value)
        assertTrue(vm.state.value is CreateGroupState.Form)
    }

    /**
     * RA-6: 그룹 관리 화면을 열 때 replay 로 다시 받은 멤버 이벤트(최대 20개)마다 GET /groups/{id} 를 불렀다(10대 그룹이면 열 때마다 9회).
     * replay 는 상세를 다시 받지 않고(첫 상세는 폴링이 1회), 실시간으로 한꺼번에 온 멤버 이벤트는 한 번으로 묶는다.
     */
    @Test
    fun `RA-6 - replayed member events do not refetch the detail and a live burst is fetched once`() = runTest(dispatcher) {
        sessions.save(GroupSession("grp_1", "추석 귀성길", "veh_L", Place(35.1, 126.8, "광주"), topics, 1L, isLeader = false))
        gateway.emitInbound(
            app.donghaeng.protocol.Topics.vehicleCmd("veh_A"), "GROUP_JOINED", "srv",
            app.donghaeng.protocol.GroupJoined("grp_1", "추석 귀성길", "veh_L", Place(35.1, 126.8, "광주"), topics), app.donghaeng.protocol.GroupJoined.serializer(),
        )
        runCurrent()
        fun joinedEvent(id: String, source: String) = app.donghaeng.protocol.GroupEvent(id, "MEMBER_JOINED", source, app.donghaeng.protocol.Severity.INFO, "$source 합류", kotlinx.serialization.json.JsonObject(emptyMap()))
        repeat(20) { i ->
            gateway.emitInbound(topics.event, app.donghaeng.protocol.MessageTypes.EVENT, "srv", joinedEvent("r$i", "veh_R$i"), app.donghaeng.protocol.GroupEvent.serializer(), seq = 2L + i)
        }
        runCurrent()
        assertEquals(20, manager.groupEvents.replayCache.size)

        val vm = viewModel()
        runCurrent()
        try {
            advanceTimeBy(1_000); runCurrent()
            assertEquals("화면을 열 때 상세는 폴링 첫 1회뿐", 1, api.detailCalls)
            assertEquals("replay 도 최근 소식에는 남는다", 5, (vm.state.value as CreateGroupState.Active).recentSpeech.size)

            // 실시간 합류 3건이 한꺼번에 → 한 번
            repeat(3) { i ->
                gateway.emitInbound(topics.event, app.donghaeng.protocol.MessageTypes.EVENT, "srv", joinedEvent("l$i", "veh_L$i"), app.donghaeng.protocol.GroupEvent.serializer(), seq = 100L + i)
            }
            runCurrent()
            advanceTimeBy(CreateGroupViewModel.MEMBER_REFRESH_DEBOUNCE_MS + 100); runCurrent()
            assertEquals(2, api.detailCalls)
        } finally {
            // 폴링 루프를 끝낸다(단언이 실패해도 — 남은 무한 폴링을 runTest 가 끝없이 돌지 않게)
            manager.onGroupLeftLocally("grp_1"); runCurrent()
        }
    }

    @Test
    fun `navi destination - auto load keeps a picked preset, a nameless navi place gets a name`() {
        val base = CreateGroupState.Form(name = "같이 가기", destination = null, fromNavi = false, maxMembers = 10, notPaired = false, loadingNavi = true)
        val navi = Place(37.0, 127.0, "", address = null)
        // 홈 사진 줄에서 고른 목적지는 화면이 열릴 때의 자동 읽기가 덮지 않는다
        val picked = base.copy(destination = DESTINATION_PRESETS[0])
        val kept = mergeNaviDestination(picked, navi, auto = true)
        assertEquals(DESTINATION_PRESETS[0], kept.destination)
        assertTrue(!kept.fromNavi && !kept.loadingNavi)
        // [내비에서 불러오기]를 직접 누르면 바꾼다 — 이름이 없으면 주소 → 없으면 '내비 목적지'
        val loaded = mergeNaviDestination(picked, navi, auto = false)
        assertEquals(NAVI_DESTINATION_FALLBACK_NAME, loaded.destination?.name)
        assertTrue(loaded.fromNavi && loaded.canCreate)
        assertEquals("강릉시 창해로 514", mergeNaviDestination(base, navi.copy(address = "강릉시 창해로 514"), auto = true).destination?.name)
        // 아무것도 고르지 않았으면 자동 읽기가 채운다, 내비 목적지가 없으면 비운 채
        assertEquals("경포해변", mergeNaviDestination(base, navi.copy(name = " 경포해변 "), auto = true).destination?.name)
        assertNull(mergeNaviDestination(base, null, auto = true).destination)
        assertEquals(60, mergeNaviDestination(base, navi.copy(name = "가".repeat(80)), auto = true).destination?.name?.length)
    }

    @Test
    fun `without pairing the form is blocked`() = runTest(dispatcher) {
        tokens.clear(TokenClearReason.USER_SETTINGS_DISCONNECT); runCurrent()
        val vm = viewModel()
        runCurrent()
        val form = vm.state.value as CreateGroupState.Form
        assertTrue(form.notPaired)
        assertTrue(!form.canCreate)
    }
}
