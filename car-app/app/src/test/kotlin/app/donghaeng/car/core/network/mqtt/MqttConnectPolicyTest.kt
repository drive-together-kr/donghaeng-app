package app.donghaeng.car.core.network.mqtt

import app.donghaeng.car.core.common.StoredCredentials
import org.junit.Assert.assertEquals
import org.junit.Test

/** HiveMqttGateway.connect 의 재연결 판단 — 닉네임만 바뀐 자격 증명은 연결을 끊지 않는다(2026-10-28) */
class MqttConnectPolicyTest {

    private val creds = StoredCredentials(
        vehicleAccessToken = "jwt-1", refreshToken = "r", vehicleId = "veh_B", nickname = "엄마 차", ownerId = "usr_B",
        mqttUri = "tcp://10.0.2.2:1883", mqttClientId = "veh_B", mqttUsername = "veh_B",
    )

    @Test
    fun `a nickname-only change keeps the connection`() {
        assertEquals(ConnectDecision.KEEP, MqttConnectPolicy.decide(connected = true, current = creds, next = creds.copy(nickname = "둘째 차")))
        assertEquals(ConnectDecision.KEEP, MqttConnectPolicy.decide(connected = true, current = creds, next = creds))
    }

    @Test
    fun `a refreshed token is kept for the next reconnect without dropping the connection`() {
        assertEquals(
            ConnectDecision.REFRESH_TOKEN,
            MqttConnectPolicy.decide(connected = true, current = creds, next = creds.copy(vehicleAccessToken = "jwt-2", nickname = "둘째 차")),
        )
    }

    @Test
    fun `no connection or another vehicle connects anew`() {
        assertEquals(ConnectDecision.CONNECT, MqttConnectPolicy.decide(connected = false, current = creds, next = creds))
        assertEquals(ConnectDecision.CONNECT, MqttConnectPolicy.decide(connected = true, current = null, next = creds))
        assertEquals(ConnectDecision.CONNECT, MqttConnectPolicy.decide(connected = true, current = creds, next = creds.copy(vehicleId = "veh_C")))
    }
}
