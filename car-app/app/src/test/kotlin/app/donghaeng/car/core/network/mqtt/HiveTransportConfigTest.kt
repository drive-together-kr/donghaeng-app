package app.donghaeng.car.core.network.mqtt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T32: BrokerEndpoint → HiveMQ 전송 설정 — wss 는 TLS + WebSocket 경로 mqtt · 서브프로토콜 mqtt, ssl 은 TLS 만 */
class HiveTransportConfigTest {

    @Test
    fun `wss endpoint becomes tls websocket on mqtt path`() {
        val cfg = transportConfigFor(BrokerEndpoint("1-2-3-4.sslip.io", 443, tls = true, webSocket = true, path = "/mqtt"))
        assertEquals("1-2-3-4.sslip.io", cfg.serverAddress.hostString)
        assertEquals(443, cfg.serverAddress.port)
        assertTrue(cfg.sslConfig.isPresent)
        val ws = cfg.webSocketConfig.get()
        assertEquals("mqtt", ws.serverPath)   // HiveMQ 가 앞 '/' 를 지운다
        assertEquals("mqtt", ws.subprotocol)
    }

    @Test
    fun `ssl and tcp endpoints have no websocket`() {
        val ssl = transportConfigFor(BrokerEndpoint("h", 8883, tls = true))
        assertTrue(ssl.sslConfig.isPresent)
        assertFalse(ssl.webSocketConfig.isPresent)
        val tcp = transportConfigFor(BrokerEndpoint("10.0.2.2", 1883, tls = false))
        assertFalse(tcp.sslConfig.isPresent)
        assertFalse(tcp.webSocketConfig.isPresent)
        assertEquals(1883, tcp.serverAddress.port)
    }
}
