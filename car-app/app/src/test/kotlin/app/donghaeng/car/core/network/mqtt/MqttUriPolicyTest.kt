package app.donghaeng.car.core.network.mqtt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** docs/08 T16: `ssl://` 이면 TLS, 서버가 https 인데 평문 브로커면 거부. T32: `ws://` · `wss://` 와 베이스 URL 파생 WSS 폴백 */
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
        assertThrows(MqttUriPolicy.PolicyViolation::class.java) { MqttUriPolicy.resolve("http://10.0.2.2:8083", "http://x") }
        assertThrows(MqttUriPolicy.PolicyViolation::class.java) { MqttUriPolicy.resolve("10.0.2.2:1883", "http://x") }
        assertThrows(MqttUriPolicy.PolicyViolation::class.java) { MqttUriPolicy.resolve("", "http://x") }
        assertThrows(MqttUriPolicy.PolicyViolation::class.java) { MqttUriPolicy.resolve("tcp://", "http://x") }
    }

    @Test
    fun `wss resolves to TLS websocket on 443 with default path mqtt`() {
        val e = MqttUriPolicy.resolve("wss://1-2-3-4.sslip.io", "https://1-2-3-4.sslip.io")
        assertEquals(BrokerEndpoint("1-2-3-4.sslip.io", 443, tls = true, webSocket = true, path = "/mqtt"), e)
        assertEquals(MqttTransport.WSS, e.transport)
        assertEquals("wss://1-2-3-4.sslip.io:443/mqtt", e.displayUri())
        // 포트·경로를 주면 그대로
        assertEquals(BrokerEndpoint("broker.example", 8084, tls = true, webSocket = true, path = "/ws"), MqttUriPolicy.resolve("WSS://broker.example:8084/ws", "https://x"))
    }

    @Test
    fun `ws is plaintext websocket allowed only for http servers`() {
        val e = MqttUriPolicy.resolve("ws://10.0.2.2:8083", "http://10.0.2.2:8080")
        assertEquals(BrokerEndpoint("10.0.2.2", 8083, tls = false, webSocket = true, path = "/mqtt"), e)
        assertEquals(MqttTransport.WS, e.transport)
        assertEquals(80, MqttUriPolicy.resolve("ws://10.0.2.2", "http://x").port)
        val refused = assertThrows(MqttUriPolicy.PolicyViolation::class.java) { MqttUriPolicy.resolve("ws://1-2-3-4.sslip.io/mqtt", "https://1-2-3-4.sslip.io") }
        assertEquals(true, refused.message!!.contains("wss://"))
    }

    @Test
    fun `tcp and ssl endpoints keep the three-field equality and transport labels`() {
        assertEquals(MqttTransport.TCP, MqttUriPolicy.resolve("tcp://10.0.2.2:1883", "http://x").transport)
        assertEquals(MqttTransport.TLS, MqttUriPolicy.resolve("ssl://h:8883", "https://h").transport)
        assertEquals("ssl://h:8883", MqttUriPolicy.resolve("ssl://h", "https://h").displayUri())
        assertEquals(listOf("tcp", "tls", "ws", "wss"), MqttTransport.entries.map { it.label })
        assertEquals(MqttTransport.WSS, MqttTransport.fromLabel("wss"))
        assertEquals(null, MqttTransport.fromLabel("quic"))
    }

    @Test
    fun `websocket fallback is derived from an https base url only`() {
        assertEquals(BrokerEndpoint("1-2-3-4.sslip.io", 443, tls = true, webSocket = true, path = "/mqtt"), MqttUriPolicy.webSocketFallback("https://1-2-3-4.sslip.io"))
        assertEquals(BrokerEndpoint("1-2-3-4.sslip.io", 443, tls = true, webSocket = true), MqttUriPolicy.webSocketFallback(" HTTPS://1-2-3-4.sslip.io/ "))
        // 베이스 URL 에 포트가 있으면 같은 포트(같은 Caddy)
        assertEquals(8443, MqttUriPolicy.webSocketFallback("https://h.example:8443")!!.port)
        assertEquals(null, MqttUriPolicy.webSocketFallback("http://10.0.2.2:8080"))
        assertEquals(null, MqttUriPolicy.webSocketFallback("not a url"))
    }

    @Test
    fun `candidates are primary then derived wss without duplicates`() {
        val ssl = MqttUriPolicy.resolve("ssl://1-2-3-4.sslip.io:8883", "https://1-2-3-4.sslip.io")
        val wss = BrokerEndpoint("1-2-3-4.sslip.io", 443, tls = true, webSocket = true)
        assertEquals(listOf(ssl, wss), MqttUriPolicy.candidates(ssl, "https://1-2-3-4.sslip.io"))
        // 로컬(http)은 파생 없음 — 1883 직결
        val tcp = MqttUriPolicy.resolve("tcp://10.0.2.2:1883", "http://10.0.2.2:8080")
        assertEquals(listOf(tcp), MqttUriPolicy.candidates(tcp, "http://10.0.2.2:8080"))
        // 이미 같은 wss 면 하나만
        assertEquals(listOf(wss), MqttUriPolicy.candidates(wss, "https://1-2-3-4.sslip.io"))
    }
}
