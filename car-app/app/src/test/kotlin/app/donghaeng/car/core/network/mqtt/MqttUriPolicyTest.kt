package app.donghaeng.car.core.network.mqtt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** docs/08 T16: `ssl://` 이면 TLS, 서버가 https 인데 평문 브로커면 거부 */
class MqttUriPolicyTest {

    @Test
    fun `tcp and ssl schemes resolve with default ports`() {
        assertEquals(BrokerEndpoint("10.0.2.2", 1883, tls = false), MqttUriPolicy.resolve("tcp://10.0.2.2:1883", "http://10.0.2.2:8080"))
        assertEquals(BrokerEndpoint("10.0.2.2", 1883, tls = false), MqttUriPolicy.resolve("mqtt://10.0.2.2", "http://10.0.2.2:8080"))
        assertEquals(BrokerEndpoint("1-2-3-4.sslip.io", 8883, tls = true), MqttUriPolicy.resolve("ssl://1-2-3-4.sslip.io:8883", "https://1-2-3-4.sslip.io"))
        assertEquals(BrokerEndpoint("broker.example", 8883, tls = true), MqttUriPolicy.resolve("mqtts://broker.example", "https://x"))
        assertEquals(BrokerEndpoint("broker.example", 9999, tls = true), MqttUriPolicy.resolve(" tls://broker.example:9999 ", "http://x"))
    }

    @Test
    fun `plaintext broker is refused when the server is https`() {
        val e = assertThrows(MqttUriPolicy.PolicyViolation::class.java) { MqttUriPolicy.resolve("tcp://1-2-3-4.sslip.io:1883", "https://1-2-3-4.sslip.io") }
        assertEquals(true, e.message!!.contains("ssl://"))
        // https 서버 + ssl 브로커는 허용, http 서버 + ssl 브로커도 허용(더 안전한 쪽)
        assertEquals(true, MqttUriPolicy.resolve("ssl://1-2-3-4.sslip.io:8883", "https://1-2-3-4.sslip.io").tls)
        assertEquals(true, MqttUriPolicy.resolve("ssl://10.0.2.2:8883", "http://10.0.2.2:8080").tls)
    }

    @Test
    fun `malformed or unknown schemes are refused`() {
        assertThrows(MqttUriPolicy.PolicyViolation::class.java) { MqttUriPolicy.resolve("ws://10.0.2.2:8083", "http://x") }
        assertThrows(MqttUriPolicy.PolicyViolation::class.java) { MqttUriPolicy.resolve("10.0.2.2:1883", "http://x") }
        assertThrows(MqttUriPolicy.PolicyViolation::class.java) { MqttUriPolicy.resolve("", "http://x") }
        assertThrows(MqttUriPolicy.PolicyViolation::class.java) { MqttUriPolicy.resolve("tcp://", "http://x") }
    }
}
