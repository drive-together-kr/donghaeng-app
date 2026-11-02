package app.donghaeng.car.domain.group

import app.donghaeng.car.core.common.InMemoryTokenStore
import app.donghaeng.car.core.common.StoredCredentials
import app.donghaeng.car.core.network.ApiResult
import app.donghaeng.car.core.network.GroupApi
import app.donghaeng.car.core.network.VehicleSelfApi
import app.donghaeng.car.core.network.mqtt.InMemoryMqttGateway
import app.donghaeng.car.domain.session.GroupCheck
import app.donghaeng.car.domain.session.GroupSession
import app.donghaeng.car.domain.session.InMemoryGroupSessionStore
import app.donghaeng.car.domain.session.MqttSessionManager
import app.donghaeng.car.domain.session.SessionClearReason
import app.donghaeng.car.domain.session.ServerMembership
import app.donghaeng.car.testing.testRefresher
import app.donghaeng.protocol.DhJson
import app.donghaeng.protocol.GroupTopics
import app.donghaeng.protocol.Place
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.create

/**
 * 실제 Retrofit 2.11 + MockWebServer 로 본문 없는 204 를 받는다(A002) — 가짜 `Response.success(204, Unit)` 은 본문이 있어 버그를 가렸다.
 * 계약 1(`GET /v1/vehicles/me/group`) 응답 분류도 실제 HTTP 로 본다(A000/A001).
 */
class GroupRepositoryHttpTest {

    private lateinit var server: MockWebServer
    private lateinit var groupApi: GroupApi
    private lateinit var selfApi: VehicleSelfApi

    private val creds = StoredCredentials("jwt", "r", "veh_B", "엄마 차", "usr_B", "tcp://10.0.2.2:1883", "veh_B", "veh_B")
    private val topics = GroupTopics("dh/v1/group/grp_1/state", "dh/v1/group/grp_1/event")
    private val session = GroupSession("grp_1", "추석 귀성길", "veh_A", Place(35.1, 126.8, "광주"), topics, 1L)

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient())
            .addConverterFactory(DhJson.json.asConverterFactory("application/json".toMediaType()))
            .build()
        groupApi = retrofit.create()
        selfApi = retrofit.create()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun repository(sessions: InMemoryGroupSessionStore): GroupRepository {
        val tokens = InMemoryTokenStore(creds)
        val manager = MqttSessionManager(tokens, InMemoryMqttGateway(), sessions, testRefresher(tokens))
        return GroupRepository(groupApi, sessions, manager)
    }

    @Test
    fun `A002 - leave and end answered with a real 204 are successes and clear the local session at once`() = runTest {
        val sessions = InMemoryGroupSessionStore(session)
        val repo = repository(sessions)
        server.enqueue(MockResponse().setResponseCode(204))
        val left = repo.leave()
        assertTrue("204 는 성공: $left", left is ApiResult.Success)
        assertNull(sessions.session.value)
        assertEquals(listOf(SessionClearReason.LEFT_LOCALLY), sessions.clearReasons)
        assertEquals("/v1/groups/grp_1/leave", server.takeRequest().path)

        sessions.save(session.copy(isLeader = true))
        server.enqueue(MockResponse().setResponseCode(204))
        assertTrue(repo.end() is ApiResult.Success)
        assertNull(sessions.session.value)
    }

    /** RA-5: 나가기 요청이 오가는 사이 다른 그룹에 합류(GROUP_JOINED 적용)했으면 204 를 받아도 새 그룹 세션은 지우지 않는다 */
    @Test
    fun `RA-5 - a leave answered after the car moved to another group does not clear the new group`() = runTest {
        val sessions = InMemoryGroupSessionStore(session)
        val repo = repository(sessions)
        val g2 = session.copy(groupId = "grp_2", name = "새 그룹", topics = GroupTopics("dh/v1/group/grp_2/state", "dh/v1/group/grp_2/event"))
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                kotlinx.coroutines.runBlocking { sessions.save(g2) }   // 응답 전에 새 그룹이 적용됐다
                return MockResponse().setResponseCode(204)
            }
        }
        assertTrue(repo.leave() is ApiResult.Success)
        assertEquals("grp_2", sessions.session.value?.groupId)
        assertTrue(sessions.clearReasons.isEmpty())
    }

    @Test
    fun `A004 - end answered 403 (no longer leader) falls back to leave`() = runTest {
        val sessions = InMemoryGroupSessionStore(session.copy(isLeader = true))
        val repo = repository(sessions)
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"code":"FORBIDDEN","message":"방장만 할 수 있어요.","traceId":"t"}}"""))
        server.enqueue(MockResponse().setResponseCode(204))
        assertTrue(repo.end() is ApiResult.Success)
        assertEquals("/v1/groups/grp_1/end", server.takeRequest().path)
        assertEquals("/v1/groups/grp_1/leave", server.takeRequest().path)
        assertNull(sessions.session.value)
    }

    @Test
    fun `contract 1 - my group 200, 204, and an old server's 404 or 405 are classified`() = runTest {
        val source = RestGroupMembershipSource(selfApi, groupApi)
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"groupId":"grp_1","name":"추석 귀성길","leaderVehicleId":"veh_A","destination":{"lat":35.1,"lon":126.8,"name":"광주"},"topics":{"state":"dh/v1/group/grp_1/state","event":"dh/v1/group/grp_1/event"}}""",
            ),
        )
        val active = source.myActiveGroup()
        assertTrue("$active", active is ServerMembership.Active && active.group.groupId == "grp_1" && active.group.topics == topics)
        assertEquals("/v1/vehicles/me/group", server.takeRequest().path)

        server.enqueue(MockResponse().setResponseCode(204))
        assertEquals(ServerMembership.None, source.myActiveGroup())
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(ServerMembership.Unsupported, source.myActiveGroup())
        server.enqueue(MockResponse().setResponseCode(405))
        assertEquals(ServerMembership.Unsupported, source.myActiveGroup())
        server.enqueue(MockResponse().setResponseCode(500))
        assertTrue(source.myActiveGroup() is ServerMembership.Unknown)
    }

    @Test
    fun `fallback detail check - ENDED or not a member is GONE, active member is kept, network is unknown`() = runTest {
        val source = RestGroupMembershipSource(selfApi, groupApi)
        fun detail(status: String, members: String) = MockResponse().setResponseCode(200).setBody(
            """{"groupId":"grp_1","name":"n","leaderVehicleId":"veh_A","status":"$status","members":[$members]}""",
        )
        val me = """{"vehicleId":"veh_B","nickname":"엄마 차","color":"#FF6B2E","role":"MEMBER","joinedAt":1}"""
        server.enqueue(detail("ACTIVE", me))
        assertEquals(GroupCheck.ACTIVE_MEMBER, source.checkGroup("grp_1", "veh_B"))
        server.enqueue(detail("ENDED", me))
        assertEquals("서버는 끝난 그룹에도 200 을 준다", GroupCheck.GONE, source.checkGroup("grp_1", "veh_B"))
        server.enqueue(detail("ACTIVE", ""))
        assertEquals(GroupCheck.GONE, source.checkGroup("grp_1", "veh_B"))
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(GroupCheck.GONE, source.checkGroup("grp_1", "veh_B"))
        server.enqueue(MockResponse().setResponseCode(503))
        assertEquals(GroupCheck.UNKNOWN, source.checkGroup("grp_1", "veh_B"))
    }
}
