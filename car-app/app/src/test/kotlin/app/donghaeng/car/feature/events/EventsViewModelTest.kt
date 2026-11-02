package app.donghaeng.car.feature.events

import app.donghaeng.car.core.common.InMemoryTokenStore
import app.donghaeng.car.core.common.StoredCredentials
import app.donghaeng.car.core.network.GroupApi
import app.donghaeng.car.core.network.mqtt.InMemoryMqttGateway
import app.donghaeng.car.domain.group.GroupRepository
import app.donghaeng.car.domain.session.GroupSession
import app.donghaeng.car.domain.session.InMemoryGroupSessionStore
import app.donghaeng.car.domain.session.MqttSessionManager
import app.donghaeng.car.domain.session.ReceivedGroupEvent
import app.donghaeng.car.feature.drive.Tone
import app.donghaeng.car.surface.SurfaceFeed
import app.donghaeng.car.surface.SurfaceMode
import app.donghaeng.car.testing.testRefresher
import app.donghaeng.protocol.GroupEvent
import app.donghaeng.protocol.GroupEventType
import app.donghaeng.protocol.GroupTopics
import app.donghaeng.protocol.Place
import app.donghaeng.protocol.Severity
import app.donghaeng.protocol.rest.GroupCreateRequest
import app.donghaeng.protocol.rest.GroupCreateResponse
import app.donghaeng.protocol.rest.GroupDetailResponse
import app.donghaeng.protocol.rest.InviteDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

/**
 * 이벤트 피드(E042·4-5·1-3·E119·4-6): 나이는 서버 시계 − 봉투 ts(화면을 연 시각이 아님), 5초마다 다시 계산, "방금"은 1분 안만,
 * 요약은 알려진 종류만 사람 말로(원문 key=value·차량 id 없음), 내 차 소식은 "(내 차)", 부제는 제목과 겹치는 요약을 뺀다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EventsViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val creds = StoredCredentials("jwt", "r", "veh_ME", "내 차", "usr_1", "tcp://10.0.2.2:1883", "veh_ME", "veh_ME")
    private val session = GroupSession("grp_1", "강릉 가족 여행", "veh_A", Place(37.7, 128.9, "경포해변"), GroupTopics("s", "e"), 0L)
    private var serverNow = 1_700_000_000_000L

    private class FakeGroupApi : GroupApi {
        override suspend fun createGroup(body: GroupCreateRequest): Response<GroupCreateResponse> = error("unused")
        override suspend fun reissueInvite(groupId: String, body: Map<String, String>): Response<InviteDto> = error("unused")
        override suspend fun getGroup(groupId: String): Response<GroupDetailResponse> = error("서버 조회 없음(테스트)")
        override suspend fun leave(groupId: String, body: Map<String, String>): Response<Unit> = error("unused")
        override suspend fun end(groupId: String, body: Map<String, String>): Response<Unit> = error("unused")
    }

    private val recent = MutableStateFlow<List<ReceivedGroupEvent>>(emptyList())
    private val surface = object : SurfaceFeed {
        override val mode = MutableStateFlow(SurfaceMode.PANEL)
        override val recentEvents = recent
        override fun serverNow(): Long = serverNow
    }

    private lateinit var manager: MqttSessionManager
    private lateinit var sessions: InMemoryGroupSessionStore

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val tokens = InMemoryTokenStore(creds)
        sessions = InMemoryGroupSessionStore(session)
        manager = MqttSessionManager(tokens, InMemoryMqttGateway(), sessions, testRefresher(tokens))
        manager.start(dispatcher)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun event(id: String, type: String, source: String?, speech: String?, payload: Map<String, Any> = emptyMap(), ageMs: Long) =
        ReceivedGroupEvent(GroupEvent(id, type, source, Severity.INFO, speech, json(payload)), envelopeTsMs = serverNow - ageMs, receivedAtMs = serverNow)

    private fun json(m: Map<String, Any>): JsonObject = JsonObject(m.mapValues { (_, v) ->
        when (v) {
            is String -> JsonPrimitive(v)
            is Number -> JsonPrimitive(v)
            is JsonObject -> v
            is JsonArray -> v
            else -> JsonPrimitive(v.toString())
        }
    })

    private fun viewModel() = EventsViewModel(manager, GroupRepository(FakeGroupApi(), sessions, manager), surface)

    @Test
    fun `age comes from the envelope on the server clock - an old event opened now is not just now`() = runTest(dispatcher) {
        // 30분 전 급제동과 20초 전 메시지 — 둘 다 방금 화면을 열었다(예전엔 둘 다 "0초 전 · 실시간")
        recent.value = listOf(
            event("e1", "PASSENGER_MESSAGE", "veh_A", "아빠 차에서 온 메시지. 휴게소 들렀다 가요", mapOf("text" to "휴게소 들렀다 가요"), ageMs = 20_000),
            event("e0", "HARD_BRAKE", "veh_A", "앞쪽 아빠 차가 급제동했어요.", ageMs = 30 * 60_000L),
        )
        val vm = viewModel().also { it.configureForTest(tickMs = 5_000) }
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        val rows = vm.uiState.value.events
        assertEquals(listOf("e1", "e0"), rows.map { it.eventId })
        assertEquals("20초 전", rows[0].ageText)
        assertTrue("1분 안 = 방금", rows[0].live)
        assertEquals("30분 전", rows[1].ageText)
        assertFalse("오래된 것은 '방금' 아님", rows[1].live)

        // 화면을 열어 둔 채 시간이 흐르면 5초마다 다시 계산한다
        serverNow += 45_000
        advanceTimeBy(5_001)
        runCurrent()
        assertEquals("1분 전", vm.uiState.value.events[0].ageText)
        assertFalse(vm.uiState.value.events[0].live)
    }

    @Test
    fun `my own car news carries the mine tag and duplicate details are left out of the subtitle`() = runTest(dispatcher) {
        recent.value = listOf(
            event("m1", "LOW_ENERGY", "veh_ME", "내 차 배터리가 12% 남았어요.", mapOf("pct" to 12), ageMs = 1_000),
            event("p1", "PASSENGER_MESSAGE", "veh_A", "아빠 차에서 온 메시지. 휴게소 들렀다 가요", mapOf("text" to "휴게소 들렀다 가요"), ageMs = 2_000),
        )
        val vm = viewModel()
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        val (mine, msg) = vm.uiState.value.events
        assertTrue(mine.mine)
        assertFalse(msg.mine)
        assertEquals("메시지 본문이 제목에 이미 있으면 부제에 다시 쓰지 않는다", "동승자 메시지", msg.subtitle)
        assertEquals("제목에 이미 12% 가 있어 요약은 빠진다", "저잔량", mine.subtitle)
        assertEquals(Tone.CAUTION, mine.tone)
    }

    @Test
    fun `details are human summaries for known kinds only - never raw key=value or vehicle ids`() {
        val samples = mapOf(
            GroupEventType.MEMBER_JOINED to mapOf("vehicleId" to "veh_01HABC", "nickname" to "둘째 차", "color" to "#2E7DFF", "role" to "MEMBER"),
            GroupEventType.MEMBER_LEFT to mapOf("vehicleId" to "veh_01HABC", "nickname" to "둘째 차", "reason" to "LEFT"),
            GroupEventType.LEADER_CHANGED to mapOf("leaderVehicleId" to "veh_01HABC", "previousLeaderVehicleId" to "veh_01HDEF"),
            GroupEventType.VOICE_MESSAGE to mapOf("voiceId" to "vm_01HXYZ", "durationMs" to 7_200, "mime" to "audio/mp4", "sizeBytes" to 12_000),
            GroupEventType.MEMBER_OFFLINE to mapOf("vehicleId" to "veh_01HABC"),
            GroupEventType.DEVIATION to mapOf("reason" to "OFF_ROUTE"),
            GroupEventType.GROUP_ENDED to mapOf("reason" to "LEADER_ENDED"),
            GroupEventType.STOPPED to mapOf("durationS" to 80),
            GroupEventType.RESUMED to mapOf("durationS" to 60),
            GroupEventType.ROUTE_CHANGED to mapOf(
                "proposalId" to "prp_01H", "destination" to JsonObject(mapOf("name" to JsonPrimitive("경포해변"))),
                "waypoints" to JsonArray(listOf(JsonObject(mapOf("name" to JsonPrimitive("덕평휴게소"))))),
            ),
        )
        GroupEventType.entries.forEach { type ->
            val e = GroupEvent("e_$type", type.name, "veh_A", Severity.INFO, null, json(samples[type] ?: mapOf("vehicleId" to "veh_01HABC", "x" to "y")))
            val d = EventsViewModel.detailOf(e)
            if (d != null) {
                assertFalse("$type: '=' 원문 없음 ($d)", d.contains('='))
                assertFalse("$type: 차량 id 없음 ($d)", d.contains("veh_"))
                assertFalse("$type: 무전 id 없음 ($d)", d.contains("vm_"))
            }
        }
        fun detail(type: GroupEventType) = EventsViewModel.detailOf(GroupEvent("e", type.name, "veh_A", Severity.INFO, null, json(samples.getValue(type))))
        assertEquals("0:07", detail(GroupEventType.VOICE_MESSAGE))
        assertEquals("1분 20초째 정차", detail(GroupEventType.STOPPED))
        assertEquals("1분 쉬고 출발", detail(GroupEventType.RESUMED))
        assertEquals("경포해변 · 경유지 1곳", detail(GroupEventType.ROUTE_CHANGED))
        assertNull("합류는 선두일 때만 '선두'", detail(GroupEventType.MEMBER_JOINED))
        assertEquals("선두", EventsViewModel.detailOf(GroupEvent("e", "MEMBER_JOINED", "veh_A", Severity.INFO, null, json(mapOf("role" to "LEADER")))))
        assertNull(detail(GroupEventType.GROUP_ENDED))
    }
}
