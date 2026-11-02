package app.donghaeng.car.core.network.mqtt

import app.donghaeng.car.core.common.StoredCredentials
import app.donghaeng.car.core.network.InMemoryServerOverrideStorage
import app.donghaeng.car.core.network.ServerConfig
import app.donghaeng.car.testing.fakeJwt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * RA-7: 게이트웨이 **배선**을 실제 HiveMQ 클라이언트 + 최소 MQTT 5 브로커(로컬 소켓, EMQX 없음)로 고정한다 — 순수 정책 테스트만으로는
 * 적용부(keepAlive 30 s·재시작 창 인증 거부 처리·SUBACK 거부 재시도/알림)를 지워도 통과했다.
 */
class HiveMqttGatewayWiringTest {

    /**
     * 테스트 전용 최소 MQTT 5 브로커 — CONNECT/SUBSCRIBE/UNSUBSCRIBE/PUBLISH(QoS 1)/PINGREQ/DISCONNECT 만 처리한다.
     * 응답 사유 코드는 [connAckCode](몇 번째 CONNECT 인가)·[subAckCode](토픽)로 바꿔 끼운다.
     */
    private class FakeMqtt5Broker : AutoCloseable {
        private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val port: Int get() = server.localPort

        @Volatile
        var connAckCode: (index: Int) -> Int = { 0x00 }

        @Volatile
        var subAckCode: (topic: String) -> Int = { 0x01 }

        /** 받은 CONNECT 마다 (받은 시각 ms, keepAlive 초) */
        val connects = CopyOnWriteArrayList<Pair<Long, Int>>()
        val subscribes = CopyOnWriteArrayList<String>()
        private val connectIndex = AtomicInteger()

        @Volatile
        private var current: Socket? = null

        private val acceptor = thread(isDaemon = true, name = "fake-mqtt-accept") {
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: continue
                current = s
                thread(isDaemon = true, name = "fake-mqtt-conn") { runCatching { serve(s) }; runCatching { s.close() } }
            }
        }

        /** 지금 연결을 브로커 쪽에서 끊는다(재시작 흉내) */
        fun dropCurrent() {
            runCatching { current?.close() }
        }

        private fun serve(s: Socket) {
            val input = DataInputStream(s.getInputStream())
            val out = s.getOutputStream()
            while (true) {
                val header = input.read()
                if (header < 0) return
                val body = ByteArray(readVarInt(input))
                input.readFully(body)
                when (header shr 4) {
                    1 -> { // CONNECT: 프로토콜 이름(2+4) · 버전(1) · 플래그(1) · keepAlive(2)
                        val keepAlive = ((body[8].toInt() and 0xFF) shl 8) or (body[9].toInt() and 0xFF)
                        connects += System.currentTimeMillis() to keepAlive
                        val code = connAckCode(connectIndex.getAndIncrement())
                        write(out, byteArrayOf(0x20, 0x03, 0x00, code.toByte(), 0x00))
                    }
                    8 -> { // SUBSCRIBE: packetId(2) · 속성 길이 · 토픽(2+n) · 옵션
                        var i = 2
                        i += varIntSize(body, i) + varIntValue(body, i)
                        val len = ((body[i].toInt() and 0xFF) shl 8) or (body[i + 1].toInt() and 0xFF)
                        val topic = String(body, i + 2, len, Charsets.UTF_8)
                        subscribes += topic
                        write(out, byteArrayOf(0x90.toByte(), 0x04, body[0], body[1], 0x00, subAckCode(topic).toByte()))
                    }
                    10 -> write(out, byteArrayOf(0xB0.toByte(), 0x04, body[0], body[1], 0x00, 0x00)) // UNSUBSCRIBE → UNSUBACK
                    3 -> { // PUBLISH — QoS 1 이면 PUBACK
                        val qos = (header shr 1) and 0x03
                        if (qos == 1) {
                            val len = ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
                            write(out, byteArrayOf(0x40, 0x02, body[2 + len], body[3 + len]))
                        }
                    }
                    12 -> write(out, byteArrayOf(0xD0.toByte(), 0x00)) // PINGREQ → PINGRESP
                    14 -> return // DISCONNECT
                }
            }
        }

        private fun write(out: OutputStream, bytes: ByteArray) = synchronized(out) {
            out.write(bytes)
            out.flush()
        }

        private fun readVarInt(input: InputStream): Int {
            var value = 0
            var shift = 0
            while (true) {
                val b = input.read()
                if (b < 0) throw java.io.EOFException()
                value = value or ((b and 0x7F) shl shift)
                if (b and 0x80 == 0) return value
                shift += 7
            }
        }

        private fun varIntValue(body: ByteArray, at: Int): Int {
            var value = 0
            var shift = 0
            var i = at
            while (true) {
                val b = body[i++].toInt() and 0xFF
                value = value or ((b and 0x7F) shl shift)
                if (b and 0x80 == 0) return value
                shift += 7
            }
        }

        private fun varIntSize(body: ByteArray, at: Int): Int {
            var i = at
            while (body[i].toInt() and 0x80 != 0) i++
            return i - at + 1
        }

        override fun close() {
            runCatching { server.close() }
            runCatching { current?.close() }
            acceptor.join(1_000)
        }
    }

    private val broker = FakeMqtt5Broker()
    private var gateway: HiveMqttGateway? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        runBlocking { gateway?.disconnect() }
        scope.cancel()
        broker.close()
    }

    /** 만료가 20시간 남은 토큰 — 재시작 창 안의 인증 거부는 '토큰 문제가 아니다' 로 볼 수 있다 */
    private val token = fakeJwt(expSec = System.currentTimeMillis() / 1000 + 20 * 3_600)

    private fun connectGateway(): HiveMqttGateway {
        val gw = HiveMqttGateway(ServerConfig("http://127.0.0.1:1", InMemoryServerOverrideStorage()), InMemoryMqttTransportMemory()).also { gateway = it }
        val creds = StoredCredentials(
            vehicleAccessToken = token, refreshToken = "ref", vehicleId = "veh_ABCDEF12", nickname = "n", ownerId = "usr_1",
            mqttUri = "tcp://127.0.0.1:${broker.port}", mqttClientId = "veh_ABCDEF12", mqttUsername = "veh_ABCDEF12",
        )
        runBlocking { gw.connect(creds) }
        waitUntil(10_000, "첫 연결") { gw.status.value.state == MqttState.CONNECTED }
        return gw
    }

    private fun waitUntil(timeoutMs: Long, what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("시간 안에 안 됨: $what")
            Thread.sleep(20)
        }
    }

    /** A072: CONNECT 의 keepAlive 는 처음 연결·재연결(applyAuth 가 다시 만든 CONNECT) 모두 30 s */
    @Test
    fun `keepAlive is 30 s on the first connect and on reconnects`() {
        connectGateway()
        assertEquals(30, broker.connects[0].second)
        broker.dropCurrent()
        waitUntil(10_000, "재연결") { broker.connects.size >= 2 && gateway!!.status.value.state == MqttState.CONNECTED }
        assertEquals(listOf(30, 30), broker.connects.map { it.second }.take(2))
    }

    /**
     * A-RT1 배선: 붙어 있다 끊긴 뒤(브로커 재시작) 멀쩡한 토큰이 NOT_AUTHORIZED(EMQX JWKS 미적재)로 거부되면 토큰 갱신을 부르지 않고(authFailures 없음)
     * 3 s → 5 s 로 다시 시도한다. 예전 규칙(갱신 요청 + 최소 15 s 대기)으로 되돌리면 실패한다.
     */
    @Test
    fun `auth rejections right after a broker restart keep the token and retry after 3 then 5 s`() {
        val refreshRequests = CopyOnWriteArrayList<MqttAuthFailure>()
        broker.connAckCode = { index -> if (index == 0) 0x00 else 0x87 }   // 첫 연결만 성공, 그 뒤 NOT_AUTHORIZED
        val gw = connectGateway()
        scope.launch { gw.authFailures.collect { refreshRequests += it } }
        Thread.sleep(100)

        broker.dropCurrent()
        waitUntil(15_000, "재시작 창 안 인증 거부 3회") { broker.connects.size >= 4 }
        val at = broker.connects.map { it.first }
        val gap1 = at[2] - at[1]
        val gap2 = at[3] - at[2]
        assertTrue("첫 거부 뒤 약 3 s(예전 15 s) — 실제 ${gap1}ms", gap1 in 2_500L..4_500L)
        assertTrue("두 번째 거부 뒤 약 5 s(창 안 거부 수가 오른다) — 실제 ${gap2}ms", gap2 in 4_500L..7_000L)
        assertTrue("토큰 갱신 요청 없음: $refreshRequests", refreshRequests.isEmpty())
        val s = gw.status.value
        assertTrue(s.lastError, s.lastError?.startsWith("auth rejected") == true)
        assertTrue(s.authFailures >= 2)
    }

    /** A018 배선: SUBACK 거부는 재시도되고 거부 토픽에 보이며, 세 번째 거부에서 한 번만 알린다. 받아들여지면 거부 표시가 지워진다 */
    @Test
    fun `denied subscriptions are retried, shown, notified once on the third denial and cleared when granted`() {
        val topic = "dh/v1/group/grp_1/state"
        broker.subAckCode = { t -> if (t == topic) 0x87 else 0x01 }
        val gw = connectGateway()
        gw.subscribeRetryDelayMs = { 150L }
        val notified = CopyOnWriteArrayList<String>()
        scope.launch { gw.subscriptionDenied.collect { notified += it } }
        Thread.sleep(100)

        runBlocking { gw.subscribe(listOf(topic)) }
        waitUntil(5_000, "거부 3회") { broker.subscribes.count { it == topic } >= 3 }
        waitUntil(2_000, "세 번째 거부 알림") { notified.isNotEmpty() }
        assertTrue(topic in gw.status.value.deniedTopics)
        waitUntil(2_000, "네 번째 시도") { broker.subscribes.count { it == topic } >= 4 }
        assertEquals("알림은 한 번", listOf(topic), notified.toList())

        broker.subAckCode = { 0x01 }
        waitUntil(5_000, "받아들여지면 거부 표시 지움") { topic !in gw.status.value.deniedTopics }
        val count = broker.subscribes.count { it == topic }
        Thread.sleep(600)
        assertEquals("받아들여진 뒤에는 다시 걸지 않는다", count, broker.subscribes.count { it == topic })
    }
}
