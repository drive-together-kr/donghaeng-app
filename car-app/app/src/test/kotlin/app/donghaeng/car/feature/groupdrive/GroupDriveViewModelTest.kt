package app.donghaeng.car.feature.groupdrive

import app.donghaeng.car.core.common.AlwaysParkedDrivingStateMonitor
import app.donghaeng.car.core.common.InMemoryTokenStore
import app.donghaeng.car.core.common.StoredCredentials
import app.donghaeng.car.core.network.GroupApi
import app.donghaeng.car.core.network.mqtt.InMemoryMqttGateway
import app.donghaeng.car.domain.group.GroupRepository
import app.donghaeng.car.domain.location.MyFixSource
import app.donghaeng.car.domain.model.Fix
import app.donghaeng.car.domain.session.GroupSession
import app.donghaeng.car.domain.session.InMemoryGroupSessionStore
import app.donghaeng.car.domain.session.MqttSessionManager
import app.donghaeng.protocol.Energy
import app.donghaeng.protocol.Eta
import app.donghaeng.protocol.GroupEvent
import app.donghaeng.protocol.GroupState
import app.donghaeng.protocol.GroupTopics
import app.donghaeng.protocol.LocSource
import app.donghaeng.protocol.MemberState
import app.donghaeng.protocol.MemberStatus
import app.donghaeng.protocol.MessageTypes
import app.donghaeng.protocol.Place
import app.donghaeng.protocol.Severity
import app.donghaeng.protocol.rest.GroupCreateRequest
import app.donghaeng.protocol.rest.GroupCreateResponse
import app.donghaeng.protocol.rest.GroupDetailResponse
import app.donghaeng.protocol.rest.InviteDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

/** T25: 스냅샷 → 멤버 UI, 이벤트 스트립, 내 위치, 나가기 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupDriveViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val creds = StoredCredentials("jwt", "r", "veh_B", "엄마 차", "usr_B", "tcp://10.0.2.2:1883", "veh_B", "veh_B")
    private val topics = GroupTopics("dh/v1/group/grp_1/state", "dh/v1/group/grp_1/event")
    private val session = GroupSession("grp_1", "추석 귀성길", "veh_A", Place(35.1595, 126.8526, "광주송정역"), topics, 0L, isLeader = false)

    private class FakeGroupApi : GroupApi {
        var left = 0
        override suspend fun createGroup(body: GroupCreateRequest): Response<GroupCreateResponse> = error("unused")
        override suspend fun reissueInvite(groupId: String, body: Map<String, String>): Response<InviteDto> = error("unused")
        override suspend fun getGroup(groupId: String): Response<GroupDetailResponse> = error("unused")
        override suspend fun leave(groupId: String, body: Map<String, String>): Response<Unit> { left++; return Response.success(204, Unit) }
        override suspend fun end(groupId: String, body: Map<String, String>): Response<Unit> = Response.success(204, Unit)
    }

    private class FakeFixes : MyFixSource {
        private val flow = MutableSharedFlow<Fix>(replay = 1)
        override val fixes: SharedFlow<Fix> = flow
        suspend fun push(fix: Fix) = flow.emit(fix)
    }

    private lateinit var api: FakeGroupApi
    private lateinit var tokens: InMemoryTokenStore
    private lateinit var sessions: InMemoryGroupSessionStore
    private lateinit var gateway: InMemoryMqttGateway
    private lateinit var manager: MqttSessionManager
    private lateinit var fixes: FakeFixes
    private var now = 1_700_000_000_000L

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        api = FakeGroupApi()
        tokens = InMemoryTokenStore(creds)
        sessions = InMemoryGroupSessionStore(session)
        gateway = InMemoryMqttGateway()
        manager = MqttSessionManager(tokens, gateway, sessions)
        manager.start(dispatcher)
        fixes = FakeFixes()
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = GroupDriveViewModel(manager, GroupRepository(api, sessions, manager), tokens, gateway, fixes, AlwaysParkedDrivingStateMonitor())
        .also { it.configureForTest(tickMs = 1_000, clock = { now }) }

    private fun member(vid: String, nick: String, status: MemberStatus, online: Boolean = true, lat: Double? = 36.35, lon: Double? = 127.38, speed: Float? = 80f, dist: Int? = 500) = MemberState(
        vehicleId = vid, nickname = nick, color = "#2E7DFF", online = online, lat = lat, lon = lon, heading = 90f, speedKph = speed, ageMs = 820,
        eta = Eta(destTs = null, remainM = 12_300, remainS = 900), energy = Energy(fuelPct = 34), distanceToLeaderM = dist, status = status,
    )

    /** InMemoryMqttGateway 는 수신 시각을 1_700_000_000_000 으로 고정한다 → 테스트 시계도 거기서 출발 */
    private fun deliverState(state: GroupState) = gateway.emitInbound(topics.state, MessageTypes.STATE, "srv_1", state, GroupState.serializer(), seq = 1)

    private fun deliverEvent(event: GroupEvent) = gateway.emitInbound(topics.event, MessageTypes.EVENT, "srv_1", event, GroupEvent.serializer(), seq = 2)

    @Test
    fun `snapshot members become ui rows with me and leader flags, eta and energy text`() = runTest(dispatcher) {
        val vm = viewModel()
        val job = vm.uiState.onEach { }.launchIn(this)
        runCurrent()
        assertFalse(vm.uiState.value.hasSnapshot)
        assertNull(vm.uiState.value.snapshotAgeS)
        assertEquals(ConnState.CONNECTED, vm.uiState.value.connection)   // InMemory 게이트웨이는 접속 즉시 CONNECTED

        deliverState(GroupState("grp_1", "veh_A", listOf(member("veh_A", "아빠 차", MemberStatus.DRIVING, dist = 0), member("veh_B", "엄마 차", MemberStatus.STOPPED, speed = 0f), member("veh_C", "삼촌 차", MemberStatus.OFFLINE, online = false, lat = null, lon = null, speed = null, dist = null))))
        runCurrent()
        val s = vm.uiState.value
        assertEquals(3, s.members.size)
        val a = s.members[0]; val b = s.members[1]; val c = s.members[2]
        assertTrue(a.isLeader); assertFalse(a.isMe); assertEquals(s.leader, a)
        assertTrue(b.isMe); assertEquals(s.me, b); assertEquals(MemberStatus.STOPPED, b.status)
        assertEquals("12.3 km · 15분", b.etaText)
        assertEquals("연료 34%", b.energyText)
        assertEquals(0L, b.ageS)
        assertEquals(0xFF2E7DFFL, b.colorArgb)
        assertFalse(c.online); assertNull(c.lat); assertNull(c.speedKph)
        assertEquals(0L, s.snapshotAgeS)

        now += 5_000
        advanceTimeBy(5_000); runCurrent()
        assertEquals(5L, vm.uiState.value.snapshotAgeS)
        job.cancel()
    }

    @Test
    fun `group events are kept newest first up to 20 and my fix feeds the map`() = runTest(dispatcher) {
        val vm = viewModel()
        val job = vm.uiState.onEach { }.launchIn(this)
        runCurrent()
        repeat(25) { i -> deliverEvent(GroupEvent("evt_$i", "LOW_ENERGY", "veh_A", Severity.WARN, "아빠 차 연료가 ${30 - i}% 남았어요.", JsonObject(emptyMap()))) }
        runCurrent()
        val events = vm.uiState.value.events
        assertEquals(20, events.size)
        assertEquals("evt_24", events.first().eventId)
        assertEquals(Severity.WARN, events.first().severity)

        fixes.push(Fix(37.5, 127.0, 45f, 5f, now, LocSource.NAVI))
        runCurrent()
        assertEquals(37.5, vm.uiState.value.myLat!!, 1e-9)
        assertEquals(45f, vm.uiState.value.myHeading)
        job.cancel()
    }

    @Test
    fun `leaving clears the session so the screen shows the ended pane`() = runTest(dispatcher) {
        val vm = viewModel()
        val job = vm.uiState.onEach { }.launchIn(this)
        runCurrent()
        assertNotNull(vm.uiState.value.session)
        vm.endOrLeave(dispatcher)
        runCurrent()
        assertEquals(1, api.left)
        assertNull(vm.uiState.value.session)
        assertFalse(vm.uiState.value.busy)
        assertNull(vm.uiState.value.error)
        job.cancel()
    }
}
