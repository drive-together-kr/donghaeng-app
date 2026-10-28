package app.donghaeng.car.core.network.mqtt

import app.donghaeng.car.core.network.mqtt.AttemptFailure.BROKER_ANSWERED
import app.donghaeng.car.core.network.mqtt.AttemptFailure.NETWORK_DOWN
import app.donghaeng.car.core.network.mqtt.AttemptFailure.UNREACHABLE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T32: ssl 8883 → wss 443 폴백 결정(실패 종류·연속 실패 횟수·첫 연결 시간 초과·한 바퀴·https 여부·기억된 전송) */
class MqttTransportFallbackTest {

    private val base = "https://1-2-3-4.sslip.io"
    private val ssl = MqttUriPolicy.resolve("ssl://1-2-3-4.sslip.io:8883", base)
    private val wss = BrokerEndpoint("1-2-3-4.sslip.io", 443, tls = true, webSocket = true)

    private fun cloud(remembered: MqttTransport? = null) = MqttTransportFallback(MqttUriPolicy.candidates(ssl, base), remembered)

    @Test
    fun `two consecutive unreachable attempts switch ssl to wss right away`() {
        val f = cloud()
        assertEquals(ssl, f.current)
        assertNull(f.onAttemptFailed(UNREACHABLE, ssl))
        assertEquals(1, f.consecutiveFailures)
        assertEquals(MqttTransportFallback.Switch(wss, decidedNow = true, quick = true), f.onAttemptFailed(UNREACHABLE, ssl))
        assertEquals(wss, f.current)
        assertEquals(0, f.consecutiveFailures)
        assertEquals(1, f.switches)
        assertFalse(f.cycledWithoutSuccess)
    }

    @Test
    fun `a failing wss goes back to ssl by the same rule but with normal backoff after a full cycle`() {
        val f = cloud()
        f.onAttemptFailed(UNREACHABLE, ssl)
        f.onAttemptFailed(UNREACHABLE, ssl)
        assertEquals(wss, f.current)
        assertNull(f.onAttemptFailed(UNREACHABLE, wss))
        // 두 후보를 다 돌았다 — 전송 문제가 아니라 망·서버 문제일 가능성: 계속 번갈아 시도하되 곧바로(quick) 시도하지 않는다
        assertEquals(MqttTransportFallback.Switch(ssl, decidedNow = true, quick = false), f.onAttemptFailed(UNREACHABLE, wss))
        assertTrue(f.cycledWithoutSuccess)
        assertEquals(2, f.switches)
        f.onAttemptFailed(UNREACHABLE, ssl)
        assertEquals(MqttTransportFallback.Switch(wss, decidedNow = true, quick = false), f.onAttemptFailed(UNREACHABLE, ssl))
        // 붙으면 한 바퀴 계산을 다시 시작한다
        assertNull("한 바퀴를 다 돈 뒤 붙은 전송은 기억하지 않는다", f.onConnected(wss))
        assertFalse(f.cycledWithoutSuccess)
        f.onConnectionLost()
        f.onAttemptFailed(UNREACHABLE, wss)
        assertEquals(MqttTransportFallback.Switch(ssl, decidedNow = true, quick = true), f.onAttemptFailed(UNREACHABLE, wss))
    }

    @Test
    fun `broker answers such as auth rejection do not count and reset the streak`() {
        val f = cloud()
        f.onAttemptFailed(UNREACHABLE, ssl)
        assertNull(f.onAttemptFailed(BROKER_ANSWERED, ssl))
        assertEquals(0, f.consecutiveFailures)
        assertNull(f.onAttemptFailed(UNREACHABLE, ssl))
        assertEquals(ssl, f.current)
    }

    @Test
    fun `network down (dns failure, ENETUNREACH) never counts so an offline car does not flap between transports`() {
        val f = cloud()
        // 터널·시동 직후: 몇 번이고 망 없음 → 전환 없음
        repeat(10) { assertNull(f.onAttemptFailed(NETWORK_DOWN, ssl)) }
        assertEquals(ssl, f.current)
        assertEquals(0, f.switches)
        // 망 없음 사이의 실패는 이어 세지 않는다(망이 바뀌었다)
        f.onAttemptFailed(UNREACHABLE, ssl)
        f.onAttemptFailed(NETWORK_DOWN, ssl)
        assertNull(f.onAttemptFailed(UNREACHABLE, ssl))
        assertEquals(ssl, f.current)
        // 첫 연결이 늦은 것도 망 탓 — 시간 초과로 바꾸지 않는다
        assertNull(f.onFirstConnectTimeout())
        // 망이 돌아와 ssl 로 붙으면 기억을 바꾸지 않는다
        assertNull(f.onConnected(ssl))
    }

    @Test
    fun `auth rejection then the 10 s first-connect timeout does not switch (broker was reached)`() {
        // 서버 재시작 뒤 EMQX JWKS 갱신 전 NOT_AUTHORIZED, 만료 토큰 갱신이 10 s 넘게 걸림, MQTT 발 회전 백오프 — 8883 은 멀쩡하다
        val f = cloud()
        assertNull(f.onAttemptFailed(BROKER_ANSWERED, ssl))
        assertNull(f.onFirstConnectTimeout())
        assertEquals(ssl, f.current)
        assertEquals(0, f.switches)
    }

    @Test
    fun `connection success and loss reset the streak`() {
        val f = cloud()
        f.onAttemptFailed(UNREACHABLE, ssl)
        assertNull("다른 전송이 실패하지 않았으면 기억하지 않는다", f.onConnected(ssl))
        assertEquals(0, f.consecutiveFailures)
        f.onConnectionLost()
        assertNull(f.onAttemptFailed(UNREACHABLE, ssl))
        f.onConnectionLost()
        assertNull(f.onAttemptFailed(UNREACHABLE, ssl))
        assertEquals(ssl, f.current)
    }

    @Test
    fun `http server has no alternative and never switches`() {
        val tcp = MqttUriPolicy.resolve("tcp://10.0.2.2:1883", "http://10.0.2.2:8080")
        val f = MqttTransportFallback(MqttUriPolicy.candidates(tcp, "http://10.0.2.2:8080"))
        assertFalse(f.hasAlternative)
        repeat(5) { assertNull(f.onAttemptFailed(UNREACHABLE, tcp)) }
        assertNull(f.onFirstConnectTimeout())
        assertEquals(tcp, f.current)
    }

    @Test
    fun `first connect timeout pre-decides the next transport once — applied when the hung attempt fails`() {
        val f = cloud()
        assertEquals(wss, f.onFirstConnectTimeout())
        assertEquals("다음 시도에 쓸 전송", wss, f.current)
        assertEquals(1, f.switches)
        assertNull(f.onFirstConnectTimeout())
        // 매달렸던 ssl 시도가 결국 실패(소켓 시간 초과) → 미리 정한 wss 를 지금 적용(이번 실패로 정한 것은 아님), 곧바로 시도
        assertEquals(MqttTransportFallback.Switch(wss, decidedNow = false, quick = true), f.onAttemptFailed(UNREACHABLE, ssl))
        assertEquals(1, f.switches)
        assertEquals("새 전송의 실패는 처음부터 센다", 0, f.consecutiveFailures)
        // wss 로 붙으면 기억한다(ssl 이 실제로 실패했다)
        assertEquals(wss, f.onConnected(wss))

        // 실패 횟수로 이미 바꿨으면 시간 초과가 되돌리지 않는다
        val g = cloud()
        g.onAttemptFailed(UNREACHABLE, ssl)
        g.onAttemptFailed(UNREACHABLE, ssl)
        assertNull(g.onFirstConnectTimeout())
        assertEquals(wss, g.current)
        // 이미 붙었으면 시간 초과 없음
        val h = cloud()
        h.onConnected(ssl)
        assertNull(h.onFirstConnectTimeout())
        assertEquals(10_000L, MqttTransportFallback.FIRST_CONNECT_TIMEOUT_MS)
    }

    @Test
    fun `timeout switch is undone when the hung attempt connects late, the broker answers, or the network was down`() {
        // 늦게 붙음 — 전환을 무르고, 기억도 바꾸지 않는다
        val f = cloud()
        f.onFirstConnectTimeout()
        assertNull(f.onConnected(ssl))
        assertEquals(ssl, f.current)
        assertEquals(0, f.switches)
        // 매달린 시도가 CONNACK 거부로 끝남 — 전송은 멀쩡했다
        val g = cloud()
        g.onFirstConnectTimeout()
        assertNull(g.onAttemptFailed(BROKER_ANSWERED, ssl))
        assertEquals(ssl, g.current)
        assertEquals(0, g.switches)
        // 매달린 시도가 DNS 실패로 끝남 — 망 탓
        val h = cloud()
        h.onFirstConnectTimeout()
        assertNull(h.onAttemptFailed(NETWORK_DOWN, ssl))
        assertEquals(ssl, h.current)
        assertEquals(0, h.switches)
    }

    @Test
    fun `transport is remembered only when another transport really failed within one cycle`() {
        val f = cloud()
        f.onAttemptFailed(UNREACHABLE, ssl)
        f.onAttemptFailed(UNREACHABLE, ssl)
        assertEquals(wss, f.onConnected(wss))
        // 기억된 wss 가 먼저 — 실패하면 ssl 로 돌아가고, ssl 로 붙으면 ssl 을 기억한다(옛 wss 기억을 고친다)
        val g = cloud(remembered = MqttTransport.WSS)
        g.onAttemptFailed(UNREACHABLE, wss)
        g.onAttemptFailed(UNREACHABLE, wss)
        assertEquals(ssl, g.onConnected(ssl))
        // 바로 붙음 → 기억 그대로
        assertNull(cloud(remembered = MqttTransport.WSS).onConnected(wss))
    }

    @Test
    fun `remembered transport is tried first and fails back the other way`() {
        val f = cloud(remembered = MqttTransport.WSS)
        assertEquals(listOf(wss, ssl), f.candidates)
        assertEquals(wss, f.current)
        f.onAttemptFailed(UNREACHABLE, wss)
        assertEquals(ssl, f.onAttemptFailed(UNREACHABLE, wss)?.to)
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
