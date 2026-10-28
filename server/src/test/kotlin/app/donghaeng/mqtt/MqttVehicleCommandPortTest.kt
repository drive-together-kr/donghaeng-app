package app.donghaeng.mqtt

import app.donghaeng.protocol.CmdType
import app.donghaeng.protocol.DhJson
import app.donghaeng.protocol.Topics
import kotlinx.serialization.KSerializer
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** T33: cmd `TOKEN_REVOKED` 는 이전 vehicleId 의 cmd 토픽에 QoS 1, retain 없이, 본문 `{}` 로 나간다 (docs/03 §5.5) */
class MqttVehicleCommandPortTest {

    private class RecordingPublisher : MqttPublisher {
        data class Sent(val topic: String, val type: String, val json: String, val qos: Int, val retain: Boolean)
        val sent = mutableListOf<Sent>()
        override val connected = true
        override fun <T> publish(topic: String, type: String, data: T, serializer: KSerializer<T>, qos: Int, retain: Boolean): Boolean {
            sent += Sent(topic, type, DhJson.json.encodeToString(serializer, data), qos, retain)
            return true
        }
        override fun clearRetained(topic: String) = true
    }

    @Test
    fun `tokenRevoked publishes an empty TOKEN_REVOKED on the vehicle cmd topic with QoS 1 and no retain`() {
        val publisher = RecordingPublisher()
        MqttVehicleCommandPort(publisher).tokenRevoked("veh_01HTEST")
        assertEquals(
            listOf(RecordingPublisher.Sent(Topics.vehicleCmd("veh_01HTEST"), CmdType.TOKEN_REVOKED.name, "{}", qos = 1, retain = false)),
            publisher.sent,
        )
    }
}
