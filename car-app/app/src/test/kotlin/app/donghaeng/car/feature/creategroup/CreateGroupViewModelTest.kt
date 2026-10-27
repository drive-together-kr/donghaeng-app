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
        override suspend fun getGroup(groupId: String): Response<GroupDetailResponse> =
            if (detailStatus == 200) Response.success(GroupDetailResponse(groupId, "추석 귀성길", "veh_A", "ACTIVE", members = members))
            else Response.error(detailStatus, """{"error":{"code":"NOT_FOUND","message":"그룹을 찾을 수 없어요.","traceId":"t"}}""".toResponseBody("application/json".toMediaType()))
        override suspend fun leave(groupId: String, body: Map<String, String>): Response<Unit> = Response.success(204, Unit)
        override suspend fun end(groupId: String, body: Map<String, String>): Response<Unit> { ended++; return Response.success(204, Unit) }
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
        manager = MqttSessionManager(tokens, gateway, sessions)
        manager.start(dispatcher)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(): CreateGroupViewModel {
        val repo = GroupRepository(api, sessions, manager)
        return CreateGroupViewModel(repo, FakeNaviAdapter(), tokens, manager).also { it.configureForTest({ now }, pollIntervalMs = 5_000, tickMs = 1_000) }
    }

    @Test
    fun `form offers a preset when navi has no destination, create shows the invite QR and polls members`() = runTest(dispatcher) {
        val vm = viewModel()
        runCurrent()
        val form = vm.state.value as CreateGroupState.Form
        assertEquals(DESTINATION_PRESETS.first().name, form.destination?.name)
        assertTrue(!form.fromNavi && form.canCreate && !form.notPaired)
        assertTrue(form.name.contains("아빠 차"))

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

    @Test
    fun `without pairing the form is blocked`() = runTest(dispatcher) {
        tokens.clear(); runCurrent()
        val vm = viewModel()
        runCurrent()
        val form = vm.state.value as CreateGroupState.Form
        assertTrue(form.notPaired)
        assertTrue(!form.canCreate)
    }
}
