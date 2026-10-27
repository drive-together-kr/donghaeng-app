package app.donghaeng.mqtt

import app.donghaeng.KPostgresContainer
import app.donghaeng.KRedisContainer
import app.donghaeng.protocol.CmdType
import app.donghaeng.protocol.DhJson
import app.donghaeng.protocol.Envelope
import app.donghaeng.protocol.GroupEvent
import app.donghaeng.protocol.GroupEventType
import app.donghaeng.protocol.GroupJoined
import app.donghaeng.protocol.LocSource
import app.donghaeng.protocol.MessageTypes
import app.donghaeng.protocol.Telemetry
import app.donghaeng.protocol.Topics
import app.donghaeng.protocol.rest.ApiPaths
import app.donghaeng.protocol.rest.DeviceApproveRequest
import app.donghaeng.protocol.rest.DeviceCodeRequest
import app.donghaeng.protocol.rest.DeviceCodeResponse
import app.donghaeng.protocol.rest.DeviceTokenRequest
import app.donghaeng.protocol.rest.DeviceTokenResponse
import app.donghaeng.protocol.rest.GroupCreateRequest
import app.donghaeng.protocol.rest.GroupCreateResponse
import app.donghaeng.protocol.rest.GroupJoinRequest
import app.donghaeng.protocol.rest.LoginRequest
import app.donghaeng.protocol.rest.PassengerMessageRequest
import app.donghaeng.protocol.Place
import app.donghaeng.protocol.rest.TokenResponse
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.MqttGlobalPublishFilter
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt5.Mqtt5BlockingClient
import com.hivemq.client.mqtt.mqtt5.exceptions.Mqtt5ConnAckException
import com.hivemq.client.mqtt.mqtt5.exceptions.Mqtt5PubAckException
import com.hivemq.client.mqtt.mqtt5.exceptions.Mqtt5SubAckException
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5Publish
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.Testcontainers
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.utility.DockerImageName
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KEmqxContainer(image: DockerImageName) : GenericContainer<KEmqxContainer>(image)

/** 서버 공유 구독으로 들어온 텔레메트리를 기록한다 (라우터 → 핸들러 경로 확인용) */
@Component
class RecordingTelemetryHandler : MqttInboundHandler {
    override val kind = Topics.VehicleTopicKind.TELEMETRY
    val received = CopyOnWriteArrayList<Pair<String, Envelope<JsonElement>>>()
    override fun handle(vehicleId: String, envelope: Envelope<JsonElement>) { received += vehicleId to envelope }
}

@TestConfiguration
class MqttItConfig {
    @Bean
    fun recordingTelemetryHandler() = RecordingTelemetryHandler()
}

/**
 * T15 통합 테스트: 실제 EMQX 5.8 컨테이너가 **이 서버의 JWKS 로 JWT 인증**하고 **이 서버의 `/internal/mqtt/acl` 로 인가**한다.
 * 서버는 고정 포트(18089)로 떠서 컨테이너가 `host.testcontainers.internal:18089` 로 접근한다.
 *
 * 확인: 서버 자신의 접속·공유 구독, 차량 토큰 접속, 차량 ACL(자기 토픽만), 합류 → cmd GROUP_JOINED 수신, 그룹 이벤트 팬아웃,
 * 비멤버는 그룹 토픽 구독 거부, 다른 차량 토픽 발행은 서버에 닿지 않음. Docker 없으면 건너뛴다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = ["server.port=18089", "dh.internal-token=it-internal-token"])
@org.testcontainers.junit.jupiter.Testcontainers(disabledWithoutDocker = true)
@Import(MqttItConfig::class)
class MqttIT {

    companion object {
        const val PORT = 18089
        private const val HOST = "host.testcontainers.internal"

        init {
            Testcontainers.exposeHostPorts(PORT)
        }

        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = KPostgresContainer("postgres:16")

        @Container
        @ServiceConnection(name = "redis")
        @JvmStatic
        val redis = KRedisContainer(DockerImageName.parse("redis:7")).withExposedPorts(6379)

        @Container
        @JvmStatic
        val emqx: KEmqxContainer = KEmqxContainer(DockerImageName.parse("emqx/emqx:5.8"))
            .withExposedPorts(1883)
            .withEnv("EMQX_NODE__NAME", "emqx@127.0.0.1")
            .withEnv("EMQX_AUTHENTICATION__1__MECHANISM", "jwt")
            .withEnv("EMQX_AUTHENTICATION__1__FROM", "password")
            .withEnv("EMQX_AUTHENTICATION__1__USE_JWKS", "true")
            .withEnv("EMQX_AUTHENTICATION__1__ENDPOINT", "http://$HOST:$PORT/.well-known/jwks.json")
            .withEnv("EMQX_AUTHENTICATION__1__REFRESH_INTERVAL", "3")
            .withEnv("EMQX_AUTHENTICATION__1__VERIFY_CLAIMS", "{sub = \"\${username}\"}")
            .withEnv("EMQX_AUTHORIZATION__NO_MATCH", "deny")
            .withEnv("EMQX_AUTHORIZATION__DENY_ACTION", "ignore")
            // 인가 결과 캐시(기본 1분)에 '거부'도 남으므로 합류 직후 재시도를 위해 짧게 (compose 는 10s)
            .withEnv("EMQX_AUTHORIZATION__CACHE__TTL", "1s")
            .withEnv(
                "EMQX_AUTHORIZATION__SOURCES",
                "[{type = http, enable = true, method = post, url = \"http://$HOST:$PORT/internal/mqtt/acl\", " +
                    "headers {content-type = \"application/json\", x-internal-token = \"it-internal-token\"}, " +
                    "body {username = \"\${username}\", clientid = \"\${clientid}\", topic = \"\${topic}\", action = \"\${action}\"}, " +
                    "request_timeout = \"5s\"}]",
            )
            .waitingFor(Wait.forLogMessage(".*is running now.*", 1).withStartupTimeout(Duration.ofMinutes(3)))

        @JvmStatic
        @DynamicPropertySource
        fun mqttProps(registry: DynamicPropertyRegistry) {
            registry.add("dh.mqtt.enabled") { "true" }
            registry.add("dh.mqtt.uri") { "tcp://${emqx.host}:${emqx.getMappedPort(1883)}" }
        }

        private val seq = AtomicInteger()
        private val DEST = Place(37.5665, 126.9780, "서울시청")

        private val clients = CopyOnWriteArrayList<Mqtt5BlockingClient>()

        @JvmStatic
        @AfterAll
        fun closeClients() {
            clients.forEach { runCatching { it.disconnect() } }
        }
    }

    @Autowired
    lateinit var rest: TestRestTemplate

    @Autowired
    lateinit var serverClient: ServerMqttClient

    @Autowired
    lateinit var telemetry: RecordingTelemetryHandler

    private val json = DhJson.json

    // ---------------------------------------------------------------- helpers

    private fun call(method: HttpMethod, path: String, body: String? = null, bearer: String? = null): ResponseEntity<String> {
        rest.restTemplate.requestFactory = JdkClientHttpRequestFactory()
        val headers = HttpHeaders().apply {
            accept = listOf(MediaType.APPLICATION_JSON)
            if (body != null) contentType = MediaType.APPLICATION_JSON
            if (bearer != null) setBearerAuth(bearer)
        }
        return rest.exchange(path, method, HttpEntity(body, headers), String::class.java)
    }

    private inline fun <reified T> ResponseEntity<String>.decode(): T = json.decodeFromString<T>(body ?: error("empty body"))

    private class Driver(val user: TokenResponse, val vehicle: DeviceTokenResponse) {
        val vehicleId get() = vehicle.vehicle.vehicleId
        val vehicleToken get() = vehicle.vehicleAccessToken
    }

    private fun driver(label: String, nickname: String): Driver {
        val n = seq.incrementAndGet()
        val user: TokenResponse = call(HttpMethod.POST, ApiPaths.AUTH_LOGIN, json.encodeToString(LoginRequest(email = "$label-$n@example.com", displayName = label))).decode()
        val code: DeviceCodeResponse = call(HttpMethod.POST, ApiPaths.DEVICE_CODE, json.encodeToString(DeviceCodeRequest(vehicleId = "CAR-MQTT-IT-$n", brand = "KIA", model = "EV9"))).decode()
        call(HttpMethod.POST, ApiPaths.DEVICE_APPROVE, json.encodeToString(DeviceApproveRequest(code.userCode, nickname)), user.accessToken)
        val token: DeviceTokenResponse = call(HttpMethod.POST, ApiPaths.DEVICE_TOKEN, json.encodeToString(DeviceTokenRequest(code.deviceCode))).decode()
        return Driver(user, token)
    }

    /** 차량으로 접속 — EMQX 가 JWKS 를 아직 못 받았으면(기동 직후) 잠시 거부되므로 최대 60초 재시도 */
    private fun connectVehicle(d: Driver): Mqtt5BlockingClient {
        val client = MqttClient.builder()
            .useMqttVersion5()
            .identifier(d.vehicleId)
            .serverHost(emqx.host)
            .serverPort(emqx.getMappedPort(1883))
            .buildBlocking()
        val deadline = System.currentTimeMillis() + 60_000
        var last: Throwable? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                client.connectWith().simpleAuth().username(d.vehicleId).password(d.vehicleToken.toByteArray()).applySimpleAuth().send()
                clients += client
                return client
            } catch (e: Mqtt5ConnAckException) {
                last = e
                Thread.sleep(1_000)
            }
        }
        throw AssertionError("vehicle connect kept failing: ${last?.message}")
    }

    private fun awaitServerConnected() {
        val deadline = System.currentTimeMillis() + 60_000
        while (!serverClient.connected && System.currentTimeMillis() < deadline) Thread.sleep(500)
        assertTrue(serverClient.connected, "server MQTT client must connect to the broker (JWT via own JWKS)")
    }

    /** HiveMQ 의 blocking send() 는 SUBACK 이 전부 오류 코드면 예외를 던진다 → 거부 = false */
    private fun Mqtt5BlockingClient.subscribeOk(topic: String): Boolean =
        try {
            subscribeWith().topicFilter(topic).qos(MqttQos.AT_LEAST_ONCE).send().reasonCodes.none { it.isError }
        } catch (e: Mqtt5SubAckException) {
            false
        }

    // ---------------------------------------------------------------- tests

    @Test
    fun `server connects with its own JWT, vehicles are confined to their topics, join delivers GROUP_JOINED and events fan out`() {
        awaitServerConnected()
        val a = driver("mqtt-a", "아빠 차")
        val b = driver("mqtt-b", "엄마 차")
        val carA = connectVehicle(a)
        val carB = connectVehicle(b)

        // 1) 자기 cmd 만 구독 가능, 남의 cmd·공유 구독·와일드카드는 거부
        assertTrue(carB.subscribeOk(Topics.vehicleCmd(b.vehicleId)), "own cmd")
        assertTrue(!carB.subscribeOk(Topics.vehicleCmd(a.vehicleId)), "other vehicle's cmd must be denied")
        assertTrue(!carB.subscribeOk("dh/v1/vehicle/+/cmd"), "wildcard must be denied")
        assertTrue(!carB.subscribeOk("\$share/server/dh/v1/vehicle/+/#"), "shared subscription must be denied")

        // 2) A 가 그룹을 만들기 전에는 그룹 토픽 구독 거부, B 가 cmd 를 받을 준비
        val received = LinkedBlockingQueue<Mqtt5Publish>()
        carB.toAsync().publishes(MqttGlobalPublishFilter.SUBSCRIBED) { received.offer(it) }
        val created: GroupCreateResponse = call(HttpMethod.POST, ApiPaths.GROUPS, json.encodeToString(GroupCreateRequest("MQTT 테스트", DEST)), a.vehicleToken).decode()
        assertTrue(!carB.subscribeOk(created.mqttTopics.event), "non-member may not subscribe group event")
        assertTrue(carA.subscribeOk(created.mqttTopics.event), "leader (member) may subscribe group event")

        // 3) B 합류 → B 의 cmd 토픽으로 GROUP_JOINED, 그룹 event 토픽으로 MEMBER_JOINED 팬아웃(A 가 받음)
        val groupEvents = LinkedBlockingQueue<Mqtt5Publish>()
        carA.toAsync().publishes(MqttGlobalPublishFilter.SUBSCRIBED) { groupEvents.offer(it) }
        val joinRes = call(HttpMethod.POST, ApiPaths.GROUPS_JOIN, json.encodeToString(GroupJoinRequest(created.invite.token)), b.user.accessToken)
        assertEquals(200, joinRes.statusCode.value(), joinRes.body)

        val cmd = received.poll(20, TimeUnit.SECONDS)
        assertNotNull(cmd, "B must receive a cmd within 20s")
        assertEquals(Topics.vehicleCmd(b.vehicleId), cmd.topic.toString())
        val cmdEnvelope = DhJson.decode<GroupJoined>(String(cmd.payloadAsBytes))
        assertEquals(CmdType.GROUP_JOINED.name, cmdEnvelope.type)
        assertEquals(serverClient.clientId, cmdEnvelope.vid)
        assertEquals(created.groupId, cmdEnvelope.data.groupId)
        assertEquals(created.mqttTopics, cmdEnvelope.data.topics)

        val fanout = groupEvents.poll(20, TimeUnit.SECONDS)
        assertNotNull(fanout, "A must receive the MEMBER_JOINED fan-out")
        assertEquals(created.mqttTopics.event, fanout.topic.toString())
        val eventEnvelope = DhJson.decode<GroupEvent>(String(fanout.payloadAsBytes))
        assertEquals(MessageTypes.EVENT, eventEnvelope.type)
        assertEquals(GroupEventType.MEMBER_JOINED.name, eventEnvelope.data.type)
        assertEquals(b.vehicleId, eventEnvelope.data.sourceVehicleId)

        // 4) 이제 멤버가 된 B 는 그룹 토픽 구독 가능(EMQX 인가 캐시 TTL 뒤); 동승자 메시지도 팬아웃된다
        Thread.sleep(1_500)
        assertTrue(carB.subscribeOk(created.mqttTopics.event), "member may subscribe group event")
        assertTrue(carB.subscribeOk(created.mqttTopics.state))
        call(HttpMethod.POST, ApiPaths.groupMessages(created.groupId), json.encodeToString(PassengerMessageRequest("천천히 와")), b.user.accessToken)
        val message = generateSequence { groupEvents.poll(20, TimeUnit.SECONDS) }
            .map { DhJson.decode<GroupEvent>(String(it.payloadAsBytes)) }
            .firstOrNull { it.data.type == GroupEventType.PASSENGER_MESSAGE.name }
        assertNotNull(message, "passenger message must fan out")
        assertEquals("엄마 차에서 온 메시지. 천천히 와", message.data.speech)

        // 5) 텔레메트리: 자기 토픽 발행은 서버 공유 구독 → 라우터 → 핸들러에 닿고, 남의 토픽 발행은 닿지 않는다
        val telemetryBody = Telemetry(lat = 37.5, lon = 127.0, heading = 90f, speedKph = 80f, locSource = LocSource.FAKE)
        fun envelope(vid: String, seq: Long) = DhJson.encode(Envelope(type = MessageTypes.TELEMETRY, vid = vid, ts = System.currentTimeMillis(), seq = seq, data = telemetryBody))
        // 남의 토픽 → EMQX 가 PUBACK 0x87(Not authorized) 로 거부하고 HiveMQ blocking 클라이언트는 예외를 던진다
        val spoof = runCatching { carB.publishWith().topic(Topics.vehicleTelemetry(a.vehicleId)).qos(MqttQos.AT_LEAST_ONCE).payload(envelope(a.vehicleId, 1).toByteArray()).send() }
        assertTrue(spoof.exceptionOrNull() is Mqtt5PubAckException, "publish to another vehicle's topic must be rejected: $spoof")
        carB.publishWith().topic(Topics.vehicleTelemetry(b.vehicleId)).qos(MqttQos.AT_LEAST_ONCE).payload(envelope("veh_SPOOF", 2).toByteArray()).send() // 봉투 vid 불일치 → 라우터가 버림
        carB.publishWith().topic(Topics.vehicleTelemetry(b.vehicleId)).qos(MqttQos.AT_LEAST_ONCE).payload(envelope(b.vehicleId, 3).toByteArray()).send() // 정상
        val deadline = System.currentTimeMillis() + 20_000
        while (telemetry.received.none { it.first == b.vehicleId } && System.currentTimeMillis() < deadline) Thread.sleep(200)
        val mine = telemetry.received.filter { it.first == b.vehicleId }
        assertEquals(1, mine.size, "exactly the legitimate telemetry reaches the handler: ${telemetry.received}")
        assertEquals(3L, mine.single().second.seq)
        assertNull(telemetry.received.firstOrNull { it.first == a.vehicleId }, "spoofed publish to A's topic must be denied by ACL")
    }

    @Test
    fun `acl hook rejects calls without the internal token and denies unknown principals`() {
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        val noToken = rest.exchange("/internal/mqtt/acl", HttpMethod.POST, HttpEntity("""{"username":"veh_X","clientid":"veh_X","topic":"dh/v1/vehicle/veh_X/telemetry","action":"publish"}""", headers), String::class.java)
        assertEquals(403, noToken.statusCode.value())
        headers.set("X-Internal-Token", "it-internal-token")
        val ok = rest.exchange("/internal/mqtt/acl", HttpMethod.POST, HttpEntity("""{"username":"veh_X","clientid":"veh_X","topic":"dh/v1/vehicle/veh_X/telemetry","action":"publish"}""", headers), String::class.java)
        assertEquals(200, ok.statusCode.value())
        assertEquals("""{"result":"allow"}""", ok.body)
        val deny = rest.exchange("/internal/mqtt/acl", HttpMethod.POST, HttpEntity("""{"username":"usr_1","clientid":"x","topic":"dh/v1/vehicle/veh_X/telemetry","action":"publish"}""", headers), String::class.java)
        assertEquals("""{"result":"deny"}""", deny.body)
        val badAction = rest.exchange("/internal/mqtt/acl", HttpMethod.POST, HttpEntity("""{"username":"veh_X","topic":"dh/v1/vehicle/veh_X/telemetry","action":"connect"}""", headers), String::class.java)
        assertEquals("""{"result":"deny"}""", badAction.body)
    }
}
