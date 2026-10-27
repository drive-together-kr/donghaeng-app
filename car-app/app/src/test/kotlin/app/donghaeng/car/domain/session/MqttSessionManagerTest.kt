package app.donghaeng.car.domain.session

import app.donghaeng.car.core.common.InMemoryTokenStore
import app.donghaeng.car.core.common.StoredCredentials
import app.donghaeng.car.core.network.mqtt.InMemoryMqttGateway
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
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T16: 페어링 ↔ 연결 ↔ cmd 처리 (브로커 없이 InMemory 게이트웨이로) */
@OptIn(ExperimentalCoroutinesApi::class)
class MqttSessionManagerTest {

    private val creds = StoredCredentials(
        vehicleAccessToken = "jwt-1", refreshToken = "r", vehicleId = "veh_B", nickname = "엄마 차", ownerId = "usr_B",
        mqttUri = "tcp://10.0.2.2:1883", mqttClientId = "veh_B", mqttUsername = "veh_B",
    )
    private val joined = GroupJoined("grp_1", "추석 귀성길", "veh_A", Place(35.1, 126.8, "광주"), GroupTopics("dh/v1/group/grp_1/state", "dh/v1/group/grp_1/event"))

    @Test
    fun `paired credentials connect and subscribe own cmd, unpairing disconnects and clears the session`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val tokens = InMemoryTokenStore()
        val gateway = InMemoryMqttGateway()
        val sessions = InMemoryGroupSessionStore()
        val manager = MqttSessionManager(tokens, gateway, sessions)
        manager.start(dispatcher)
        runCurrent()
        assertTrue(gateway.connectedWith.isEmpty())

        tokens.save(creds)
        runCurrent()
        assertEquals(listOf(creds), gateway.connectedWith)
        assertEquals(setOf(Topics.vehicleCmd("veh_B")), gateway.subscriptions)

        tokens.clear()
        runCurrent()
        assertEquals(1, gateway.disconnectCount)
    }

    @Test
    fun `GROUP_JOINED saves the session and subscribes group topics, GROUP_ENDED clears them, group messages are decoded`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val tokens = InMemoryTokenStore(creds)
        val gateway = InMemoryMqttGateway()
        val sessions = InMemoryGroupSessionStore()
        val manager = MqttSessionManager(tokens, gateway, sessions)
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
    }

    @Test
    fun `restart with a stored session resubscribes group topics, TOKEN_REVOKED clears credentials`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val tokens = InMemoryTokenStore(creds)
        val gateway = InMemoryMqttGateway()
        val sessions = InMemoryGroupSessionStore(GroupSession.from(joined, 1L))
        val manager = MqttSessionManager(tokens, gateway, sessions)
        manager.start(dispatcher)
        runCurrent()
        assertEquals(setOf(Topics.vehicleCmd("veh_B"), "dh/v1/group/grp_1/state", "dh/v1/group/grp_1/event"), gateway.subscriptions)

        gateway.emitInbound(Topics.vehicleCmd("veh_B"), CmdType.TOKEN_REVOKED.name, "srv_1", TokenRevoked, TokenRevoked.serializer(), seq = 9)
        runCurrent()
        assertNull(tokens.load())
        assertEquals(1, gateway.disconnectCount)
        assertNull(sessions.session.value)
    }
}
