package app.donghaeng.car.core.network.mqtt

import app.donghaeng.car.core.common.DhLog
import app.donghaeng.car.core.common.StoredCredentials
import app.donghaeng.car.core.network.InMemoryServerOverrideStorage
import app.donghaeng.car.core.network.ServerConfig
import com.hivemq.client.mqtt.exceptions.ConnectionFailedException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.InetAddress
import java.net.NoRouteToHostException
import java.net.ServerSocket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLHandshakeException
import kotlin.concurrent.thread

/**
 * T32 리뷰: 게이트웨이 배선 — 실패 분류([mqttFailureKind])와, 실제 HiveMQ 클라이언트가 브로커에 닿지 못할 때
 * `onDisconnected` → [MqttTransportFallback] → `reconnector.transportConfig` 로 같은 클라이언트에서 ssl → wss 로 바꾸는지(로컬 소켓, EMQX 없음).
 */
class HiveMqttGatewayFallbackTest {

    @Test
    fun `failure kinds — dns failure and ENETUNREACH are network down, refusal timeout and EHOSTUNREACH count`() {
        assertEquals(AttemptFailure.NETWORK_DOWN, mqttFailureKind(ConnectionFailedException(UnknownHostException("1-2-3-4.sslip.io"))))
        // Netty AnnotatedConnectException(ConnectException) 문구 / Android errno 문구
        assertEquals(AttemptFailure.NETWORK_DOWN, mqttFailureKind(ConnectionFailedException(ConnectException("Network is unreachable: /1.2.3.4:8883"))))
        assertEquals(AttemptFailure.NETWORK_DOWN, mqttFailureKind(SocketException("connect failed: ENETUNREACH (Network is unreachable)")))
        assertEquals(AttemptFailure.UNREACHABLE, mqttFailureKind(ConnectionFailedException(ConnectException("Connection refused: /1.2.3.4:8883"))))
        assertEquals(AttemptFailure.UNREACHABLE, mqttFailureKind(ConnectionFailedException(SocketTimeoutException("connection timed out"))))
        assertEquals(AttemptFailure.UNREACHABLE, mqttFailureKind(ConnectionFailedException(SSLHandshakeException("handshake timed out"))))
        // 방화벽의 ICMP host-prohibited 거절도 EHOSTUNREACH — 8883 차단 신호일 수 있어 센다
        assertEquals(AttemptFailure.UNREACHABLE, mqttFailureKind(ConnectionFailedException(NoRouteToHostException("No route to host"))))
        assertEquals(AttemptFailure.UNREACHABLE, mqttFailureKind(IllegalStateException("x")))
    }

    // ---------------------------------------------------------------- 실제 HiveMQ 클라이언트

    /** 받자마자 닫는 TCP 서버 — TLS 핸드셰이크가 곧바로 실패한다(브로커에 닿지 못한 시도, 빠르다) */
    private class ClosingServer : AutoCloseable {
        val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val accepted = AtomicInteger()

        /** 처음 연결을 받은 시각(ms), 아직 없으면 0 */
        @Volatile
        var firstAcceptAtMs = 0L
        private val worker = thread(isDaemon = true, name = "closing-server") {
            while (!socket.isClosed) {
                runCatching {
                    socket.accept().use {
                        if (accepted.incrementAndGet() == 1) firstAcceptAtMs = System.currentTimeMillis()
                    }
                }
            }
        }
        val port: Int get() = socket.localPort

        override fun close() {
            socket.close()
            worker.join(1_000)
        }
    }

    /** 받은 연결을 [holdMs] 동안 아무 말 없이 붙잡았다가 닫는 서버 — TLS 핸드셰이크가 매달린 8883(DPI·느린 망) 흉내 */
    private class HangingServer(private val holdMs: Long) : AutoCloseable {
        val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val accepted = AtomicInteger()

        /** 마지막으로 붙잡은 연결을 닫은 시각(ms) */
        @Volatile
        var releasedAtMs = 0L
        private val worker = thread(isDaemon = true, name = "hanging-server") {
            while (!socket.isClosed) {
                runCatching {
                    socket.accept().use {
                        accepted.incrementAndGet()
                        Thread.sleep(holdMs)
                        releasedAtMs = System.currentTimeMillis()
                    }
                }
            }
        }
        val port: Int get() = socket.localPort

        override fun close() {
            socket.close()
            worker.join(holdMs + 1_000)
        }
    }

    private val sslSide = ClosingServer()
    private val wssSide = ClosingServer()
    private var hanging: HangingServer? = null
    private var gateway: HiveMqttGateway? = null

    @After
    fun tearDown() {
        runBlocking { gateway?.disconnect() }
        sslSide.close()
        wssSide.close()
        hanging?.close()
    }

    private fun creds(mqttUri: String) = StoredCredentials(
        vehicleAccessToken = "jwt", refreshToken = "ref", vehicleId = "veh_ABCDEF12", nickname = "n", ownerId = "usr_1",
        mqttUri = mqttUri, mqttClientId = "veh_ABCDEF12", mqttUsername = "veh_ABCDEF12",
    )

    private fun waitUntil(timeoutMs: Long, what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("시간 안에 안 됨: $what")
            Thread.sleep(50)
        }
    }

    @Test
    fun `two unreachable ssl attempts switch the same client to the derived wss endpoint`() {
        // https 서버(베이스 포트 = wss 쪽 서버) + ssl 브로커(다른 포트) → 후보 [ssl://127.0.0.1:A, wss://127.0.0.1:B/mqtt]
        val config = ServerConfig("https://127.0.0.1:${wssSide.port}", InMemoryServerOverrideStorage())
        val memory = InMemoryMqttTransportMemory()
        val gw = HiveMqttGateway(config, memory).also { gateway = it }
        runBlocking { gw.connect(creds("ssl://127.0.0.1:${sslSide.port}")) }
        assertEquals(MqttTransport.TLS, gw.status.value.transport)

        waitUntil(15_000, "ssl 2회 실패 뒤 wss 로 전환") { gw.status.value.transport == MqttTransport.WSS }
        val s = gw.status.value
        assertEquals(1, s.transportFallbacks)
        assertEquals("wss://127.0.0.1:${wssSide.port}/mqtt", s.brokerUri)
        assertTrue("ssl 쪽 시도 수 ${sslSide.accepted.get()}", sslSide.accepted.get() >= 2)
        // 같은 클라이언트의 다음 시도가 실제로 wss 쪽(베이스 URL 포트)으로 간다 — 곧바로(1 s) 시도
        waitUntil(5_000, "wss 쪽 연결 시도") { wssSide.accepted.get() >= 1 }
        assertTrue(
            "전환 로그: ${DhLog.recent(30)}",
            DhLog.recent(30).any { it.contains("mqtt transport fallback → wss (from tls: 2 failed attempts") },
        )
        // 붙은 적이 없으니 기억은 그대로
        assertNull(memory.recall("127.0.0.1"))
    }

    @Test
    fun `first-connect timeout decides wss but the same client tries it only after the hung ssl attempt ended`() {
        // 클라이언트를 새로 만들지 않으므로 같은 clientId 로 두 연결이 겹치지 않는다 — 매달린 시도가 끝나기 전에는 wss 로 가지 않는다(T32 리뷰)
        val hang = HangingServer(holdMs = 3_000).also { hanging = it }
        val config = ServerConfig("https://127.0.0.1:${wssSide.port}", InMemoryServerOverrideStorage())
        val gw = HiveMqttGateway(config, InMemoryMqttTransportMemory()).also { gateway = it }
        gw.firstConnectTimeoutMs = 1_000
        runBlocking { gw.connect(creds("ssl://127.0.0.1:${hang.port}")) }

        waitUntil(5_000, "시간 초과가 다음 전송을 정함") { gw.status.value.transportFallbacks == 1 }
        // 정하기만 했다 — 매달린 ssl 시도가 아직 진행 중이라 상태 전송은 그대로, wss 쪽 시도도 없다
        assertEquals(MqttTransport.TLS, gw.status.value.transport)
        assertEquals(0, wssSide.accepted.get())
        assertEquals(1, hang.accepted.get())

        waitUntil(10_000, "매달린 시도가 끝난 뒤 wss 적용") { gw.status.value.transport == MqttTransport.WSS }
        waitUntil(5_000, "wss 쪽 연결 시도") { wssSide.accepted.get() >= 1 }
        assertTrue(
            "ssl 연결이 닫힌 뒤에야 wss 를 시도한다 (released=${hang.releasedAtMs}, wssFirst=${wssSide.firstAcceptAtMs})",
            hang.releasedAtMs > 0 && wssSide.firstAcceptAtMs >= hang.releasedAtMs,
        )
        assertEquals("ssl 은 한 번만(시간 초과 뒤 ssl 로 다시 가지 않는다)", 1, hang.accepted.get())
        assertEquals(1, gw.status.value.transportFallbacks)
        val logs = DhLog.recent(40)
        assertTrue("로그: $logs", logs.any { it.contains("mqtt transport fallback → wss (from tls: not connected in 1s") })
        assertTrue("로그: $logs", logs.any { it.contains("mqtt transport wss applied after the hung tls attempt ended") })
    }
}
