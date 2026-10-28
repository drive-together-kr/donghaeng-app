package app.donghaeng.car.domain.session

import app.donghaeng.car.core.common.InMemoryTokenStore
import app.donghaeng.car.core.common.StoredCredentials
import app.donghaeng.car.core.common.TokenClearReason
import app.donghaeng.car.core.common.TokenStoreState
import app.donghaeng.car.core.network.InMemoryServerOverrideStorage
import app.donghaeng.car.core.network.ServerConfig
import app.donghaeng.car.core.network.ServerOverrides
import app.donghaeng.car.core.network.mqtt.InMemoryMqttGateway
import app.donghaeng.car.core.network.mqtt.InMemoryMqttTransportMemory
import app.donghaeng.car.core.network.mqtt.MqttTransport
import app.donghaeng.car.domain.time.ClockSync
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T32: 숨은 메뉴 저장 — 서버가 바뀌면 토큰 삭제(SERVER_CHANGED), MQTT 주소만 바뀌면 같은 토큰으로 다시 연결, 전송 기억은 지운다 */
class ServerSwitcherTest {

    private val creds = StoredCredentials(
        vehicleAccessToken = "acc", refreshToken = "ref", vehicleId = "veh_ABCDEF12", nickname = "n", ownerId = "usr_1",
        mqttUri = "tcp://10.0.2.2:1883", mqttClientId = "veh_ABCDEF12", mqttUsername = "veh_ABCDEF12",
    )

    private class Fixture(initial: StoredCredentials?) {
        val config = ServerConfig("http://10.0.2.2:8080", "tcp://10.0.2.2:1883", InMemoryServerOverrideStorage())
        val tokens = InMemoryTokenStore(initial)
        val gateway = InMemoryMqttGateway()
        val memory = InMemoryMqttTransportMemory().apply { remember("10.0.2.2", MqttTransport.TCP) }
        var timeCalls = 0
        val clock = ClockSync(fetchServerTimeMs = { timeCalls++; 0L })
        val switcher = ServerSwitcher(config, tokens, gateway, memory, clock)
    }

    @Test
    fun `changing the server clears paired credentials with SERVER_CHANGED`() = runTest {
        val f = Fixture(creds)
        f.tokens.load()
        val change = f.switcher.apply(ServerOverrides(publicBaseUrl = "https://1-2-3-4.sslip.io"))
        assertTrue(change.baseUrlChanged)
        assertEquals(listOf(TokenClearReason.SERVER_CHANGED), f.tokens.clearReasons)
        assertEquals(TokenStoreState.Empty, f.tokens.state.value)
        assertEquals("https://1-2-3-4.sslip.io", f.config.publicBaseUrl)
        assertNull(f.memory.recall("10.0.2.2"))
        // MQTT 끊기·그룹 정리는 MqttSessionManager 가 토큰 Empty 를 보고 한다 — 여기서 직접 다시 붙지 않는다
        assertTrue(f.gateway.connectedWith.isEmpty())
    }

    @Test
    fun `mqtt-only change reconnects with the same credentials`() = runTest {
        val f = Fixture(creds)
        f.tokens.load()
        val change = f.switcher.apply(ServerOverrides(mqttUri = "ws://10.0.2.2:8083"))
        assertEquals(false, change.baseUrlChanged)
        assertTrue(change.mqttUriChanged)
        assertTrue(f.tokens.clearReasons.isEmpty())
        assertEquals(1, f.gateway.disconnectCount)
        assertEquals(listOf(creds), f.gateway.connectedWith)
        assertEquals("ws://10.0.2.2:8083", f.config.brokerUriFor(creds.mqttUri))
    }

    @Test
    fun `nothing happens when the value is unchanged or nothing is paired`() = runTest {
        val f = Fixture(creds)
        f.tokens.load()
        assertEquals(false, f.switcher.apply(ServerOverrides(publicBaseUrl = "http://10.0.2.2:8080/")).any)
        assertTrue(f.tokens.clearReasons.isEmpty())
        assertEquals(0, f.gateway.disconnectCount)
        assertEquals(MqttTransport.TCP, f.memory.recall("10.0.2.2"))

        // 미페어링(Empty)이면 지울 토큰이 없다 — 삭제 기록을 남기지 않는다
        val g = Fixture(null)
        g.tokens.load()
        assertTrue(g.switcher.apply(ServerOverrides(publicBaseUrl = "https://1-2-3-4.sslip.io")).baseUrlChanged)
        assertTrue(g.tokens.clearReasons.isEmpty())
        // 되돌리기
        assertTrue(g.switcher.reset().baseUrlChanged)
        assertEquals("http://10.0.2.2:8080", g.config.publicBaseUrl)
    }
}
