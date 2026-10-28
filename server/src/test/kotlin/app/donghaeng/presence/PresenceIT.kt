package app.donghaeng.presence

import app.donghaeng.event.VehicleEventService
import app.donghaeng.group.GroupMembershipApi
import app.donghaeng.mqtt.MqttPublisher
import app.donghaeng.protocol.DhJson
import app.donghaeng.protocol.Energy
import app.donghaeng.protocol.Envelope
import app.donghaeng.protocol.GroupEventType
import app.donghaeng.protocol.GroupState
import app.donghaeng.protocol.LocSource
import app.donghaeng.protocol.MemberStatus
import app.donghaeng.protocol.MessageTypes
import app.donghaeng.protocol.Place
import app.donghaeng.protocol.Telemetry
import app.donghaeng.protocol.Topics
import app.donghaeng.protocol.VehicleEventType
import app.donghaeng.protocol.rest.ApiPaths
import app.donghaeng.protocol.rest.DeviceApproveRequest
import app.donghaeng.protocol.rest.DeviceCodeRequest
import app.donghaeng.protocol.rest.DeviceCodeResponse
import app.donghaeng.protocol.rest.DeviceTokenRequest
import app.donghaeng.protocol.rest.DeviceTokenResponse
import app.donghaeng.protocol.rest.GroupCreateRequest
import app.donghaeng.protocol.rest.GroupCreateResponse
import app.donghaeng.protocol.rest.GroupDetailResponse
import app.donghaeng.protocol.rest.GroupJoinRequest
import app.donghaeng.protocol.rest.LoginRequest
import app.donghaeng.protocol.rest.TokenResponse
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PresencePostgres(image: String) : PostgreSQLContainer<PresencePostgres>(image)
class PresenceRedis(image: DockerImageName) : GenericContainer<PresenceRedis>(image)

/** 발행을 기록하는 MqttPublisher (스냅샷 발행기 단독 검증용) */
class RecordingPublisher : MqttPublisher {
    data class Sent(val topic: String, val type: String, val json: String, val qos: Int, val retain: Boolean)
    val sent = CopyOnWriteArrayList<Sent>()
    val cleared = CopyOnWriteArrayList<String>()
    override val connected: Boolean = true
    override fun <T> publish(topic: String, type: String, data: T, serializer: KSerializer<T>, qos: Int, retain: Boolean): Boolean {
        sent += Sent(topic, type, DhJson.json.encodeToString(serializer, data), qos, retain)
        return true
    }
    override fun clearRetained(topic: String): Boolean { cleared += topic; return true }
}

/**
 * 스냅샷 읽기를 한 번 **SQL 오류**로 실패시키는 [PresenceApi] (평소엔 [DefaultPresenceApi] 그대로).
 * 실패는 실제 JDBC 문장(`select 1/0`)으로 낸다 — 같은 트랜잭션 안이었다면 PostgreSQL 이 그 트랜잭션의 나머지 문장(이벤트 INSERT)을 거부한다.
 */
class FlakyPresenceApi(private val delegate: PresenceApi, private val jdbc: JdbcTemplate) : PresenceApi {
    @Volatile var failNext = false
    override fun snapshotOf(groupId: String): GroupState? {
        if (failNext) {
            failNext = false
            jdbc.queryForObject("select 1/0", Int::class.java)
        }
        return delegate.snapshotOf(groupId)
    }
}

/**
 * presence 통합 테스트 (docs/03 §5.3, T24): 실제 PostgreSQL + Redis 로 부팅해 텔레메트리 수신 → Redis → `GET /groups/{id}` snapshot /
 * 2초 스냅샷 발행기(retained STATE) 까지 확인한다. Docker 가 없으면 건너뛴다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class PresenceIT {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PresencePostgres("postgres:16")

        @Container
        @ServiceConnection(name = "redis")
        @JvmStatic
        val redis = PresenceRedis(DockerImageName.parse("redis:7")).withExposedPorts(6379)

        private val seq = AtomicInteger()
        private val telemetrySeq = AtomicLong()
        private val DEST = Place(lat = 35.1595454, lon = 126.8526012, name = "광주 할머니댁")
    }

    @Autowired lateinit var rest: TestRestTemplate
    @Autowired lateinit var ingestor: TelemetryIngestor
    @Autowired lateinit var presenceIngestor: PresenceIngestor
    @Autowired lateinit var membership: GroupMembershipApi
    @Autowired lateinit var builder: SnapshotBuilder
    @Autowired lateinit var vehicleEvents: VehicleEventService
    @Autowired lateinit var flakyPresence: FlakyPresenceApi

    /** 이 테스트 컨텍스트에서만 [PresenceApi] 를 [FlakyPresenceApi] 로 감싼다 */
    @TestConfiguration
    class FlakyPresenceConfig {
        @Bean
        @Primary
        fun flakyPresenceApi(delegate: DefaultPresenceApi, jdbc: JdbcTemplate) = FlakyPresenceApi(delegate, jdbc)
    }

    private val json = DhJson.json

    @BeforeEach
    fun useJdkHttpClient() {
        rest.restTemplate.requestFactory = JdkClientHttpRequestFactory()
    }

    // ---------------------------------------------------------------- helpers (GroupFlowIT 와 같은 흐름)

    private class Driver(val user: TokenResponse, val vehicle: DeviceTokenResponse) {
        val userToken get() = user.accessToken
        val vehicleToken get() = vehicle.vehicleAccessToken
        val vehicleId get() = vehicle.vehicle.vehicleId
    }

    private fun call(method: HttpMethod, path: String, body: String? = null, bearer: String? = null): ResponseEntity<String> {
        val headers = HttpHeaders().apply {
            accept = listOf(MediaType.APPLICATION_JSON)
            if (body != null) contentType = MediaType.APPLICATION_JSON
            if (bearer != null) setBearerAuth(bearer)
        }
        return rest.exchange(path, method, HttpEntity(body, headers), String::class.java)
    }

    private inline fun <reified T> ResponseEntity<String>.decode(): T = json.decodeFromString<T>(body ?: error("empty body"))

    private fun ResponseEntity<String>.assertOk(expected: Int = 200): ResponseEntity<String> {
        assertEquals(expected, statusCode.value(), "status for body=$body")
        return this
    }

    private fun driver(label: String, nickname: String): Driver {
        val n = seq.incrementAndGet()
        val user: TokenResponse = call(HttpMethod.POST, ApiPaths.AUTH_LOGIN, json.encodeToString(LoginRequest(email = "presence-$label-$n@example.com", displayName = "사용자 $label"))).assertOk().decode()
        val code: DeviceCodeResponse = call(HttpMethod.POST, ApiPaths.DEVICE_CODE, json.encodeToString(DeviceCodeRequest(vehicleId = "CAR-INFO-PRESENCE-IT-$n", model = "GRANDEUR", brand = "HYUNDAI", appVersion = "0.1.0"))).assertOk().decode()
        call(HttpMethod.POST, ApiPaths.DEVICE_APPROVE, json.encodeToString(DeviceApproveRequest(code.userCode, nickname)), user.accessToken).assertOk()
        val vehicle: DeviceTokenResponse = call(HttpMethod.POST, ApiPaths.DEVICE_TOKEN, json.encodeToString(DeviceTokenRequest(code.deviceCode))).assertOk().decode()
        return Driver(user, vehicle)
    }

    private fun create(d: Driver): GroupCreateResponse =
        call(HttpMethod.POST, ApiPaths.GROUPS, json.encodeToString(GroupCreateRequest("귀성길", DEST, 5)), d.vehicleToken).assertOk(201).decode()

    private fun detailOk(groupId: String, bearer: String): GroupDetailResponse = call(HttpMethod.GET, ApiPaths.group(groupId), bearer = bearer).assertOk().decode()

    private fun ingest(vehicleId: String, t: Telemetry, tsMs: Long) {
        val data: JsonElement = json.encodeToJsonElement(Telemetry.serializer(), t)
        ingestor.handle(vehicleId, Envelope(type = MessageTypes.TELEMETRY, vid = vehicleId, ts = tsMs, seq = telemetrySeq.incrementAndGet(), data = data))
    }

    private fun GroupState.member(vid: String) = members.first { it.vehicleId == vid }

    // ---------------------------------------------------------------- test

    @Test
    fun `telemetry becomes the group snapshot in GET group and in the retained STATE publication`() {
        val a = driver("a", "아빠 차")
        val b = driver("b", "엄마 차")
        val created = create(a)
        call(HttpMethod.POST, ApiPaths.GROUPS_JOIN, json.encodeToString(GroupJoinRequest(created.invite.token, null)), b.userToken).assertOk()
        val gid = created.groupId

        // 1) 텔레메트리 전: snapshot 은 있되 전원 OFFLINE·위치 없음
        val d0 = detailOk(gid, a.userToken)
        val s0 = assertNotNull(d0.snapshot, "snapshot must be present for an active group")
        assertEquals(2, s0.members.size)
        assertTrue(s0.members.all { it.status == MemberStatus.OFFLINE && it.lat == null && !it.online })

        // 2) 선두 A 주행 중, 멤버 B 1 km 뒤에서 막 정지
        val now = System.currentTimeMillis()
        ingest(a.vehicleId, Telemetry(lat = 37.5000, lon = 127.0, heading = 90f, speedKph = 80f, locSource = LocSource.NAVI, gear = "D", energy = Energy(fuelPct = 40)), now)
        ingest(b.vehicleId, Telemetry(lat = 37.5090, lon = 127.0, heading = 0f, speedKph = 0f, locSource = LocSource.ANDROID), now)
        val s1 = assertNotNull(detailOk(gid, b.userToken).snapshot)
        val a1 = s1.member(a.vehicleId)
        val b1 = s1.member(b.vehicleId)
        assertEquals(MemberStatus.DRIVING, a1.status); assertTrue(a1.online); assertEquals(0, a1.distanceToLeaderM); assertEquals(40, a1.energy?.fuelPct); assertEquals(80f, a1.speedKph); assertEquals("아빠 차", a1.nickname)
        assertEquals(37.5, a1.lat!!, 1e-9)
        assertTrue(a1.ageMs!! in 0..10_000, "ageMs=${a1.ageMs}")
        assertEquals(MemberStatus.DRIVING, b1.status, "정지 60초 전까지는 DRIVING")
        assertTrue(b1.distanceToLeaderM!! in 950..1050, "distance=${b1.distanceToLeaderM}")

        // 3) 에너지는 변화 시에만 오므로 다음 텔레메트리에 없어도 유지된다
        ingest(a.vehicleId, Telemetry(lat = 37.5001, lon = 127.0, heading = 90f, speedKph = 81f, locSource = LocSource.NAVI, gear = "D"), now + 1_000)
        assertEquals(40, detailOk(gid, a.userToken).snapshot!!.member(a.vehicleId).energy?.fuelPct)

        // 4) presence offline → OFFLINE, 다시 online → 복귀. 61초 정지 → STOPPED
        presenceIngestor.onPresence(b.vehicleId, false, now)
        assertEquals(MemberStatus.OFFLINE, detailOk(gid, a.userToken).snapshot!!.member(b.vehicleId).status)
        presenceIngestor.onPresence(b.vehicleId, true, now)
        ingest(b.vehicleId, Telemetry(lat = 37.5090, lon = 127.0, heading = 0f, speedKph = 0f, locSource = LocSource.ANDROID), now - 61_000) // stoppedSince 는 이미 now 로 기록돼 있음
        val stoppedState = detailOk(gid, a.userToken).snapshot!!.member(b.vehicleId)
        assertEquals(MemberStatus.OFFLINE, stoppedState.status, "61초 전 위치만 있으면 OFFLINE")

        // 5) 선두 A 가 목적지 반경 안에서 정지 → ARRIVED
        ingest(a.vehicleId, Telemetry(lat = DEST.lat + 0.0005, lon = DEST.lon, heading = 0f, speedKph = 0f, locSource = LocSource.NAVI), now + 2_000)
        assertEquals(MemberStatus.ARRIVED, detailOk(gid, a.userToken).snapshot!!.member(a.vehicleId).status)

        // 6) 스냅샷 발행기: 활성 그룹마다 retained STATE 1건
        val recorder = RecordingPublisher()
        val publisher = SnapshotPublisher(membership, builder, recorder)
        val published = publisher.publishAll()
        assertTrue(published >= 1, "published=$published")
        val mine = recorder.sent.single { it.topic == Topics.groupState(gid) }
        assertEquals(MessageTypes.STATE, mine.type); assertEquals(0, mine.qos); assertTrue(mine.retain)
        val state: GroupState = json.decodeFromString(mine.json)
        assertEquals(a.vehicleId, state.leaderVehicleId)
        assertEquals(setOf(a.vehicleId, b.vehicleId), state.members.map { it.vehicleId }.toSet())
        assertEquals(MemberStatus.ARRIVED, state.member(a.vehicleId).status)

        // 7) 그룹 종료 → 명단에서 빠져 더 이상 발행되지 않는다
        call(HttpMethod.POST, ApiPaths.groupEnd(gid), bearer = a.vehicleToken).assertOk(204)
        assertNull(membership.rosterOf(gid))
        assertNull(detailOk(gid, a.userToken).snapshot, "ended group has no snapshot")
        recorder.sent.clear()
        publisher.publishAll()
        assertTrue(recorder.sent.none { it.topic == Topics.groupState(gid) })
    }

    @Test
    fun `hard brake audience is chosen from the live snapshot, stored with the event and returned by GET group`() {
        val a = driver("a2", "아빠 차")
        val b = driver("b2", "둘째 차")
        val c = driver("c2", "삼촌 차")
        val created = create(a)
        listOf(b, c).forEach { call(HttpMethod.POST, ApiPaths.GROUPS_JOIN, json.encodeToString(GroupJoinRequest(created.invite.token, null)), it.userToken).assertOk() }
        val gid = created.groupId

        // 선두 A, B 약 1 km 뒤, C 약 2 km 뒤 (위도 0.009° ≈ 1 km)
        val now = System.currentTimeMillis()
        ingest(a.vehicleId, Telemetry(lat = 37.5000, lon = 127.0, heading = 0f, speedKph = 80f, locSource = LocSource.NAVI), now)
        ingest(b.vehicleId, Telemetry(lat = 37.4910, lon = 127.0, heading = 0f, speedKph = 80f, locSource = LocSource.NAVI), now)
        ingest(c.vehicleId, Telemetry(lat = 37.4820, lon = 127.0, heading = 0f, speedKph = 80f, locSource = LocSource.NAVI), now)
        val brake = buildJsonObject { put("decelMps2", 5.6); put("speedBeforeKph", 72.0) }

        // 가운데 B 급제동 → 뒤차 C 만
        val fromB = assertNotNull(vehicleEvents.handle(b.vehicleId, VehicleEventType.HARD_BRAKE, brake))
        assertEquals(listOf(c.vehicleId), fromB.audience)
        // 맨 뒤 C 급제동 → 아무도 아님(기록은 남음)
        val fromC = assertNotNull(vehicleEvents.handle(c.vehicleId, VehicleEventType.HARD_BRAKE, brake))
        assertEquals(emptyList(), fromC.audience)
        // 그 밖 종류는 그룹 전체(audience 없음)
        val low = assertNotNull(vehicleEvents.handle(c.vehicleId, VehicleEventType.LOW_ENERGY, buildJsonObject { put("kind", "FUEL"); put("pct", 12) }))
        assertNull(low.audience)

        // REST recentEvents 가 MQTT 와 같은 audience 를 돌려준다 — null(전체)과 [](아무도 아님)을 구분해 저장
        val raw = call(HttpMethod.GET, ApiPaths.group(gid), bearer = a.userToken).assertOk().body!!
        val recent = json.decodeFromString<GroupDetailResponse>(raw).recentEvents
        val byId = recent.associateBy { it.eventId }
        assertEquals(listOf(c.vehicleId), byId.getValue(fromB.eventId).audience)
        assertEquals(emptyList(), byId.getValue(fromC.eventId).audience)
        assertNull(byId.getValue(low.eventId).audience)
        assertEquals(GroupEventType.HARD_BRAKE.name, byId.getValue(fromB.eventId).type)
        assertTrue(raw.contains("\"audience\":[]"), "빈 목록은 [] 로 나간다")
    }

    @Test
    fun `a SQL error while reading the audience snapshot does not lose the hard brake - it goes to the whole group`() {
        // 리뷰 지적: 스냅샷 읽기가 저장 트랜잭션 안에 있으면 catch 해도 PostgreSQL 이 같은 트랜잭션의 INSERT 를 거부해 급제동이 통째로 사라졌다
        val a = driver("a3", "아빠 차")
        val b = driver("b3", "둘째 차")
        val created = create(a)
        call(HttpMethod.POST, ApiPaths.GROUPS_JOIN, json.encodeToString(GroupJoinRequest(created.invite.token, null)), b.userToken).assertOk()
        val gid = created.groupId
        val now = System.currentTimeMillis()
        ingest(a.vehicleId, Telemetry(lat = 37.5000, lon = 127.0, heading = 0f, speedKph = 80f, locSource = LocSource.NAVI), now)
        ingest(b.vehicleId, Telemetry(lat = 37.4910, lon = 127.0, heading = 0f, speedKph = 80f, locSource = LocSource.NAVI), now)

        flakyPresence.failNext = true
        val e = assertNotNull(vehicleEvents.handle(a.vehicleId, VehicleEventType.HARD_BRAKE, buildJsonObject { put("decelMps2", 5.6); put("speedBeforeKph", 72.0) }))
        assertFalse(flakyPresence.failNext, "스냅샷 읽기가 실제로 실패했다")
        assertNull(e.audience, "스냅샷을 못 읽으면 audience 없이(그룹 전체) — 차량이 자기 스냅샷으로 다시 판정")
        val recent = detailOk(gid, a.userToken).recentEvents
        assertEquals(GroupEventType.HARD_BRAKE.name, recent.single { it.eventId == e.eventId }.type, "저장돼 REST 에도 나온다")
        assertNull(recent.single { it.eventId == e.eventId }.audience)

        // 다음 급제동(맨 뒤 B)은 평소대로 스냅샷으로 고른다 — 뒤차가 없어 []
        val fromB = assertNotNull(vehicleEvents.handle(b.vehicleId, VehicleEventType.HARD_BRAKE, buildJsonObject { put("decelMps2", 5.0); put("speedBeforeKph", 60.0) }))
        assertEquals(emptyList(), fromB.audience)
    }
}
