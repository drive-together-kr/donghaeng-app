package app.donghaeng.car.domain.session

import app.donghaeng.car.core.common.InMemoryTokenStore
import app.donghaeng.car.core.common.StoredCredentials
import app.donghaeng.car.core.common.TokenClearReason
import app.donghaeng.car.core.common.TokenStoreState
import app.donghaeng.car.core.network.mqtt.InMemoryMqttGateway
import app.donghaeng.car.testing.FakeIdentityApi
import app.donghaeng.car.testing.fakeJwt
import app.donghaeng.car.testing.testRefresher
import app.donghaeng.protocol.CmdType
import app.donghaeng.protocol.GroupEnded
import app.donghaeng.protocol.GroupEvent
import app.donghaeng.protocol.GroupJoined
import app.donghaeng.protocol.GroupTopics
import app.donghaeng.protocol.MessageTypes
import app.donghaeng.protocol.Place
import app.donghaeng.protocol.Severity
import app.donghaeng.protocol.TokenRevoked
import app.donghaeng.protocol.Topics
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T16: 페어링 ↔ 연결 ↔ cmd 처리 (브로커 없이 InMemory 게이트웨이로). T33: Unavailable·삭제 사유·인증 거부 → 갱신 */
@OptIn(ExperimentalCoroutinesApi::class)
class MqttSessionManagerTest {

    private val creds = StoredCredentials(
        vehicleAccessToken = "jwt-1", refreshToken = "r", vehicleId = "veh_B", nickname = "엄마 차", ownerId = "usr_B",
        mqttUri = "tcp://10.0.2.2:1883", mqttClientId = "veh_B", mqttUsername = "veh_B",
    )
    private val joined = GroupJoined("grp_1", "추석 귀성길", "veh_A", Place(35.1, 126.8, "광주"), GroupTopics("dh/v1/group/grp_1/state", "dh/v1/group/grp_1/event"))

    private fun manager(tokens: InMemoryTokenStore, gateway: InMemoryMqttGateway, sessions: InMemoryGroupSessionStore, api: FakeIdentityApi = FakeIdentityApi()) =
        MqttSessionManager(tokens, gateway, sessions, testRefresher(tokens, api))

    @Test
    fun `paired credentials connect and subscribe own cmd, unpairing disconnects and clears the session`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val tokens = InMemoryTokenStore()
        val gateway = InMemoryMqttGateway()
        val sessions = InMemoryGroupSessionStore()
        val manager = manager(tokens, gateway, sessions)
        manager.start(dispatcher)
        runCurrent()
        assertTrue(gateway.connectedWith.isEmpty())

        tokens.save(creds)
        runCurrent()
        assertEquals(listOf(creds), gateway.connectedWith)
        assertEquals(setOf(Topics.vehicleCmd("veh_B")), gateway.subscriptions)

        tokens.clear(TokenClearReason.USER_SETTINGS_DISCONNECT)
        runCurrent()
        assertEquals(1, gateway.disconnectCount)
        assertTrue("옛 cmd 토픽은 구독 목록에서 빠진다", gateway.subscriptions.isEmpty())
    }

    @Test
    fun `GROUP_JOINED saves the session and subscribes group topics, GROUP_ENDED clears them, group messages are decoded`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val tokens = InMemoryTokenStore(creds)
        val gateway = InMemoryMqttGateway()
        val sessions = InMemoryGroupSessionStore()
        val manager = manager(tokens, gateway, sessions)
        manager.start(dispatcher)
        runCurrent()

        gateway.emitInbound(Topics.vehicleCmd("veh_B"), CmdType.GROUP_JOINED.name, "srv_1", joined, GroupJoined.serializer())
        runCurrent()
        val session = sessions.session.value
        assertEquals("grp_1", session?.groupId)
        assertEquals("veh_A", session?.leaderVehicleId)
        assertEquals(setOf(Topics.vehicleCmd("veh_B"), "dh/v1/group/grp_1/state", "dh/v1/group/grp_1/event"), gateway.subscriptions)
        assertEquals("GROUP_JOINED", manager.lastCommand.value?.type)

        // 그룹 이벤트 수신 → groupEvents
        val event = GroupEvent("evt_1", "MEMBER_JOINED", "veh_C", Severity.INFO, "삼촌 차가 그룹에 들어왔어요.", buildJsonObject { put("vehicleId", "veh_C") })
        gateway.emitInbound("dh/v1/group/grp_1/event", MessageTypes.EVENT, "srv_1", event, GroupEvent.serializer(), seq = 2)
        runCurrent()
        assertEquals(event, manager.groupEvents.first())

        // 남의 cmd 토픽·모르는 타입은 무시
        gateway.emitInbound(Topics.vehicleCmd("veh_X"), CmdType.GROUP_ENDED.name, "srv_1", GroupEnded("grp_1", "LEADER_ENDED"), GroupEnded.serializer(), seq = 3)
        runCurrent()
        assertEquals("grp_1", sessions.session.value?.groupId)

        gateway.emitInbound(Topics.vehicleCmd("veh_B"), CmdType.GROUP_ENDED.name, "srv_1", GroupEnded("grp_1", "LEADER_ENDED"), GroupEnded.serializer(), seq = 4)
        runCurrent()
        assertNull(sessions.session.value)
        assertEquals(setOf(Topics.vehicleCmd("veh_B")), gateway.subscriptions)
        assertEquals("GROUP_ENDED", manager.lastCommand.value?.type)
        assertEquals(listOf(SessionClearReason.GROUP_ENDED), sessions.clearReasons)
    }

    @Test
    fun `my own MEMBER_LEFT clears the session, another car leaving does not (T30)`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val tokens = InMemoryTokenStore(creds)
        val gateway = InMemoryMqttGateway()
        val sessions = InMemoryGroupSessionStore()
        val manager = manager(tokens, gateway, sessions)
        manager.start(dispatcher)
        runCurrent()
        gateway.emitInbound(Topics.vehicleCmd("veh_B"), CmdType.GROUP_JOINED.name, "srv_1", joined, GroupJoined.serializer())
        runCurrent()
        assertEquals("grp_1", sessions.session.value?.groupId)

        val otherLeft = GroupEvent("evt_l1", "MEMBER_LEFT", "veh_C", Severity.INFO, "삼촌 차가 그룹에서 나갔어요.", buildJsonObject { put("reason", "LEFT") })
        gateway.emitInbound("dh/v1/group/grp_1/event", MessageTypes.EVENT, "srv_1", otherLeft, GroupEvent.serializer(), seq = 2)
        runCurrent()
        assertEquals("other car leaving keeps my session", "grp_1", sessions.session.value?.groupId)

        val meLeft = GroupEvent("evt_l2", "MEMBER_LEFT", "veh_B", Severity.INFO, null, buildJsonObject { put("reason", "LEFT") })
        gateway.emitInbound("dh/v1/group/grp_1/event", MessageTypes.EVENT, "srv_1", meLeft, GroupEvent.serializer(), seq = 3)
        runCurrent()
        assertNull(sessions.session.value)
        assertEquals(setOf(Topics.vehicleCmd("veh_B")), gateway.subscriptions)
        assertEquals(listOf(SessionClearReason.MEMBER_LEFT_ME), sessions.clearReasons)
    }

    @Test
    fun `restart with a stored session resubscribes group topics, TOKEN_REVOKED clears credentials with its reason`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val tokens = InMemoryTokenStore(creds)
        val gateway = InMemoryMqttGateway()
        val sessions = InMemoryGroupSessionStore(GroupSession.from(joined, 1L))
        val manager = manager(tokens, gateway, sessions)
        manager.start(dispatcher)
        runCurrent()
        assertEquals(setOf(Topics.vehicleCmd("veh_B"), "dh/v1/group/grp_1/state", "dh/v1/group/grp_1/event"), gateway.subscriptions)

        gateway.emitInbound(Topics.vehicleCmd("veh_B"), CmdType.TOKEN_REVOKED.name, "srv_1", TokenRevoked, TokenRevoked.serializer(), seq = 9)
        runCurrent()
        assertNull(tokens.load())
        assertEquals(1, gateway.disconnectCount)
        assertNull(sessions.session.value)
        assertEquals(listOf(TokenClearReason.TOKEN_REVOKED_CMD), tokens.clearReasons)
        assertEquals("TOKEN_REVOKED_CMD", tokens.lastClear.value?.reason)
        assertEquals("Empty 는 UNPAIRED 사유로 그룹 세션까지 지운다", listOf(SessionClearReason.UNPAIRED), sessions.clearReasons)
    }

    @Test
    fun `T33 - token store Unavailable keeps the group session, does not connect, and retries every 30 s`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val tokens = InMemoryTokenStore(creds).apply { unavailableReason = "SecurityException<AEADBadTagException" }
        val gateway = InMemoryMqttGateway()
        val sessions = InMemoryGroupSessionStore(GroupSession.from(joined, 1L))
        val manager = manager(tokens, gateway, sessions)
        manager.start(dispatcher)
        runCurrent()
        assertTrue(tokens.state.value is TokenStoreState.Unavailable)
        assertEquals("세션 유지", "grp_1", sessions.session.value?.groupId)
        assertTrue("연결하지 않는다", gateway.connectedWith.isEmpty())
        assertTrue(sessions.clearReasons.isEmpty())
        val loadsAtStart = tokens.loadCalls

        advanceTimeBy(MqttSessionManager.STORE_RETRY_INTERVAL_MS + 1)
        runCurrent()
        assertEquals(loadsAtStart + 1, tokens.loadCalls)
        assertEquals(1, manager.storeRetry.value.attempts)

        // 저장소가 살아나면 다음 재시도에서 Present → 세션 그대로 접속
        tokens.unavailableReason = null
        advanceTimeBy(MqttSessionManager.STORE_RETRY_INTERVAL_MS + 1)
        runCurrent()
        assertTrue(tokens.state.value is TokenStoreState.Present)
        assertEquals(listOf(creds), gateway.connectedWith)
        assertEquals(setOf(Topics.vehicleCmd("veh_B"), "dh/v1/group/grp_1/state", "dh/v1/group/grp_1/event"), gateway.subscriptions)
        assertEquals("grp_1", sessions.session.value?.groupId)
        assertEquals(0, manager.storeRetry.value.attempts)
    }

    @Test
    fun `T33 - Unavailable retries stop after 10 attempts, home retry starts over`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val tokens = InMemoryTokenStore(creds).apply { unavailableReason = "KeyStoreException" }
        val gateway = InMemoryMqttGateway()
        val sessions = InMemoryGroupSessionStore(GroupSession.from(joined, 1L))
        val manager = manager(tokens, gateway, sessions)
        manager.start(dispatcher)
        runCurrent()
        val loadsAtStart = tokens.loadCalls
        advanceTimeBy(MqttSessionManager.STORE_RETRY_INTERVAL_MS * (MqttSessionManager.STORE_RETRY_MAX + 3))
        runCurrent()
        assertEquals(loadsAtStart + MqttSessionManager.STORE_RETRY_MAX, tokens.loadCalls)
        assertTrue(manager.storeRetry.value.exhausted)
        assertEquals("grp_1", sessions.session.value?.groupId)

        tokens.unavailableReason = null
        manager.retryTokenStore()
        runCurrent()
        assertTrue(tokens.state.value is TokenStoreState.Present)
        assertEquals(listOf(creds), gateway.connectedWith)
        assertTrue(!manager.storeRetry.value.exhausted)
    }

    @Test
    fun `T33 - MQTT auth rejection triggers a token refresh and reconnects with the rotated token`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val tokens = InMemoryTokenStore(creds)
        val gateway = InMemoryMqttGateway()
        val sessions = InMemoryGroupSessionStore()
        val api = FakeIdentityApi().apply { refreshHandler = { FakeIdentityApi.ok("jwt-2", "r2") } }
        val manager = manager(tokens, gateway, sessions, api)
        manager.start(dispatcher)
        runCurrent()
        assertEquals(listOf(creds), gateway.connectedWith)

        gateway.emitAuthFailure(accessToken = "jwt-1", reasonCode = "BAD_USER_NAME_OR_PASSWORD")
        runCurrent()
        assertEquals("refresh 는 저장된 refresh 토큰으로 한 번", listOf("r"), api.refreshCalls.map { it.refreshToken })
        val rotated = creds.copy(vehicleAccessToken = "jwt-2", refreshToken = "r2")
        assertEquals(TokenStoreState.Present(rotated), tokens.state.value)
        assertEquals("새 토큰으로 다시 connect", rotated, gateway.connectedWith.last())
        assertEquals(1, gateway.status.value.authFailures)
    }

    @Test
    fun `T33 - refresh rejected (401) after MQTT auth failure clears credentials as REFRESH_REJECTED`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val tokens = InMemoryTokenStore(creds)
        val gateway = InMemoryMqttGateway()
        val sessions = InMemoryGroupSessionStore(GroupSession.from(joined, 1L))
        val api = FakeIdentityApi().apply { refreshHandler = { FakeIdentityApi.unauthorized() } }
        val manager = manager(tokens, gateway, sessions, api)
        manager.start(dispatcher)
        runCurrent()

        gateway.emitAuthFailure(accessToken = "jwt-1")
        runCurrent()
        assertEquals(TokenStoreState.Empty, tokens.state.value)
        assertEquals(listOf(TokenClearReason.REFRESH_REJECTED), tokens.clearReasons)
        assertEquals(listOf(SessionClearReason.UNPAIRED), sessions.clearReasons)
        assertEquals(1, gateway.disconnectCount)
    }

    @Test
    fun `T33 - TOKEN_REVOKED published before the current token was issued is ignored (stale persistent-session message)`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val iat = 1_700_000_100L
        val fresh = creds.copy(vehicleAccessToken = fakeJwt(expSec = iat + 86_400, iatSec = iat))
        val tokens = InMemoryTokenStore(fresh)
        val gateway = InMemoryMqttGateway()
        val sessions = InMemoryGroupSessionStore()
        val manager = manager(tokens, gateway, sessions)
        manager.start(dispatcher)
        runCurrent()

        // 봉투 ts(서버 시계) 가 토큰 iat 와 같은 초 이전 → 재페어링 전 것 → 무시
        gateway.emitInbound(Topics.vehicleCmd("veh_B"), CmdType.TOKEN_REVOKED.name, "srv_1", TokenRevoked, TokenRevoked.serializer(), seq = 1, ts = (iat - 5) * 1000)
        gateway.emitInbound(Topics.vehicleCmd("veh_B"), CmdType.TOKEN_REVOKED.name, "srv_1", TokenRevoked, TokenRevoked.serializer(), seq = 2, ts = iat * 1000 + 400)
        runCurrent()
        assertTrue(tokens.state.value is TokenStoreState.Present)
        assertTrue(tokens.clearReasons.isEmpty())

        // 발급 뒤에 발행된 폐기 → 처리
        gateway.emitInbound(Topics.vehicleCmd("veh_B"), CmdType.TOKEN_REVOKED.name, "srv_1", TokenRevoked, TokenRevoked.serializer(), seq = 3, ts = (iat + 60) * 1000)
        runCurrent()
        assertEquals(TokenStoreState.Empty, tokens.state.value)
        assertEquals(listOf(TokenClearReason.TOKEN_REVOKED_CMD), tokens.clearReasons)
        assertNotNull(tokens.lastClear.value)
    }
}
