package app.donghaeng.car.core.network.mqtt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** T32: ssl 8883 → wss 443 폴백 결정(연속 실패 횟수·첫 연결 시간 초과·https 여부·기억된 전송) */
class MqttTransportFallbackTest {

    private val base = "https://1-2-3-4.sslip.io"
    private val ssl = MqttUriPolicy.resolve("ssl://1-2-3-4.sslip.io:8883", base)
    private val wss = BrokerEndpoint("1-2-3-4.sslip.io", 443, tls = true, webSocket = true)

    private fun cloud(remembered: MqttTransport? = null) = MqttTransportFallback(MqttUriPolicy.candidates(ssl, base), remembered)

    @Test
    fun `two consecutive unreachable attempts switch ssl to wss`() {
        val f = cloud()
        assertEquals(ssl, f.current)
        assertNull(f.onAttemptFailed(reachedBroker = false))
        assertEquals(1, f.consecutiveFailures)
        assertEquals(wss, f.onAttemptFailed(reachedBroker = false))
        assertEquals(wss, f.current)
        assertEquals(0, f.consecutiveFailures)
        assertEquals(1, f.switches)
    }

    @Test
    fun `a failing wss goes back to ssl by the same rule`() {
        val f = cloud()
        f.onAttemptFailed(false)
        f.onAttemptFailed(false)
        assertEquals(wss, f.current)
        assertNull(f.onAttemptFailed(false))
        assertEquals(ssl, f.onAttemptFailed(false))
        assertEquals(2, f.switches)
    }

    @Test
    fun `broker answers such as auth rejection do not count and reset the streak`() {
        val f = cloud()
        f.onAttemptFailed(reachedBroker = false)
        assertNull(f.onAttemptFailed(reachedBroker = true))
        assertEquals(0, f.consecutiveFailures)
        assertNull(f.onAttemptFailed(reachedBroker = false))
        assertEquals(ssl, f.current)
    }

    @Test
    fun `connection success and loss reset the streak`() {
        val f = cloud()
        f.onAttemptFailed(false)
        assertEquals(ssl, f.onConnected())
        assertEquals(0, f.consecutiveFailures)
        f.onConnectionLost()
        assertNull(f.onAttemptFailed(false))
        f.onConnectionLost()
        assertNull(f.onAttemptFailed(false))
        assertEquals(ssl, f.current)
    }

    @Test
    fun `http server has no alternative and never switches`() {
        val tcp = MqttUriPolicy.resolve("tcp://10.0.2.2:1883", "http://10.0.2.2:8080")
        val f = MqttTransportFallback(MqttUriPolicy.candidates(tcp, "http://10.0.2.2:8080"))
        assertFalse(f.hasAlternative)
        repeat(5) { assertNull(f.onAttemptFailed(false)) }
        assertNull(f.onFirstConnectTimeout())
        assertEquals(tcp, f.current)
    }

    @Test
    fun `first connect timeout switches once and only before any switch or success`() {
        val f = cloud()
        assertEquals(wss, f.onFirstConnectTimeout())
        assertNull(f.onFirstConnectTimeout())
        // 실패 횟수로 이미 바꿨으면 시간 초과가 되돌리지 않는다
        val g = cloud()
        g.onAttemptFailed(false)
        g.onAttemptFailed(false)
        assertNull(g.onFirstConnectTimeout())
        assertEquals(wss, g.current)
        // 이미 붙었으면 시간 초과 없음
        val h = cloud()
        h.onConnected()
        assertNull(h.onFirstConnectTimeout())
        assertEquals(10_000L, MqttTransportFallback.FIRST_CONNECT_TIMEOUT_MS)
    }

    @Test
    fun `remembered transport is tried first and fails back the other way`() {
        val f = cloud(remembered = MqttTransport.WSS)
        assertEquals(listOf(wss, ssl), f.candidates)
        assertEquals(wss, f.current)
        f.onAttemptFailed(false)
        assertEquals(ssl, f.onAttemptFailed(false))
        // 후보에 없는 기억은 무시
        assertEquals(ssl, cloud(remembered = MqttTransport.TCP).current)
        assertEquals(listOf(ssl, wss), MqttTransportFallback.order(listOf(ssl, wss), null))
    }

    @Test
    fun `transport memory is keyed by server`() {
        val memory = InMemoryMqttTransportMemory()
        memory.remember("1-2-3-4.sslip.io", MqttTransport.WSS)
        assertEquals(MqttTransport.WSS, memory.recall("1-2-3-4.sslip.io"))
        assertNull(memory.recall("5-6-7-8.sslip.io"))
        memory.clear()
        assertNull(memory.recall("1-2-3-4.sslip.io"))
    }
}
