package app.donghaeng.group

import app.donghaeng.KPostgresContainer
import app.donghaeng.KRedisContainer
import app.donghaeng.common.config.DhProperties
import app.donghaeng.common.idempotency.IdempotencyRetention
import app.donghaeng.common.security.SecureTokens
import app.donghaeng.event.EventRetention
import app.donghaeng.event.VehicleEventService
import app.donghaeng.protocol.RouteChanged
import app.donghaeng.protocol.VehicleEventType
import app.donghaeng.protocol.rest.ApiHeaders
import app.donghaeng.protocol.rest.MemberLeftReason
import app.donghaeng.routesync.RouteSyncService
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import app.donghaeng.protocol.GroupEnded
import app.donghaeng.protocol.GroupJoined
import app.donghaeng.protocol.DhJson
import app.donghaeng.protocol.GroupEvent
import app.donghaeng.protocol.GroupEventType
import app.donghaeng.protocol.Place
import app.donghaeng.protocol.Topics
import app.donghaeng.protocol.rest.ApiPaths
import app.donghaeng.protocol.rest.DeviceApproveRequest
import app.donghaeng.protocol.rest.DeviceCodeRequest
import app.donghaeng.protocol.rest.DeviceCodeResponse
import app.donghaeng.protocol.rest.DeviceTokenRequest
import app.donghaeng.protocol.rest.DeviceTokenResponse
import app.donghaeng.protocol.rest.ErrorCodes
import app.donghaeng.protocol.rest.ErrorEnvelope
import app.donghaeng.protocol.rest.GroupCreateRequest
import app.donghaeng.protocol.rest.GroupCreateResponse
import app.donghaeng.protocol.rest.GroupDetailResponse
import app.donghaeng.protocol.rest.GroupJoinRequest
import app.donghaeng.protocol.rest.GroupJoinResponse
import app.donghaeng.protocol.rest.GroupLeaveRequest
import app.donghaeng.protocol.rest.InviteDto
import app.donghaeng.protocol.rest.InvitePreviewResponse
import app.donghaeng.protocol.rest.LeaderRequest
import app.donghaeng.protocol.rest.LoginRequest
import app.donghaeng.protocol.rest.MyGroupDto
import app.donghaeng.protocol.rest.PassengerMessageRequest
import app.donghaeng.protocol.rest.TokenResponse
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * group 통합 테스트 (docs/03 §2 계약): 실제 PostgreSQL 16 + Redis 7 로 부팅해 HTTP 로 생성·초대·합류·조회·나가기·종료·위임·메시지를 돈다.
 * 차량은 identity 의 페어링 흐름(코드 → 승인 → 토큰)으로 만든다. Docker 가 없으면 건너뛴다.
 */
/** T15 전까지 cmd 출구는 로그뿐이라, 테스트에서는 호출을 기록하는 구현으로 바꿔 끼운다 (커밋 뒤 호출 여부까지 확인) */
class RecordingVehicleCommandPort : VehicleCommandPort {
    val joined = CopyOnWriteArrayList<Pair<String, GroupJoined>>()
    val ended = CopyOnWriteArrayList<Pair<String, GroupEnded>>()
    val revoked = CopyOnWriteArrayList<String>()
    override fun groupJoined(vehicleId: String, cmd: GroupJoined) { joined += vehicleId to cmd }
    override fun groupEnded(vehicleId: String, cmd: GroupEnded) { ended += vehicleId to cmd }
    override fun tokenRevoked(vehicleId: String) { revoked += vehicleId }
}

/** C032: 합류 뒤 발행되는 [VehicleJoinedGroup] 과 그 순간까지 나간 GROUP_JOINED 수 — 경로 재전송이 GROUP_JOINED 뒤인지 본다 */
class JoinedEventRecorder(private val commands: RecordingVehicleCommandPort) {
    val events = CopyOnWriteArrayList<Pair<VehicleJoinedGroup, Int>>()

    @org.springframework.context.event.EventListener
    fun on(e: VehicleJoinedGroup) {
        events += e to commands.joined.count { it.first == e.vehicleId && it.second.groupId == e.groupId }
    }
}

@TestConfiguration
class RecordingCommandConfig {
    @Bean
    @Primary
    fun recordingVehicleCommandPort() = RecordingVehicleCommandPort()

    @Bean
    fun joinedEventRecorder(commands: RecordingVehicleCommandPort) = JoinedEventRecorder(commands)
}

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@Import(RecordingCommandConfig::class)
class GroupFlowIT {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = KPostgresContainer("postgres:16")

        @Container
        @ServiceConnection(name = "redis")
        @JvmStatic
        val redis = KRedisContainer(DockerImageName.parse("redis:7")).withExposedPorts(6379)

        private val seq = AtomicInteger()
        private val DEST = Place(lat = 35.1595454, lon = 126.8526012, name = "광주 할머니댁", address = "광주광역시 …")
    }

    @Autowired
    lateinit var rest: TestRestTemplate

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Autowired
    lateinit var redisTemplate: StringRedisTemplate

    @Autowired
    lateinit var props: DhProperties

    @Autowired
    lateinit var commands: RecordingVehicleCommandPort

    @Autowired
    lateinit var joinedEvents: JoinedEventRecorder

    @Autowired
    lateinit var routeSync: RouteSyncService

    @Autowired
    lateinit var vehicleEvents: VehicleEventService

    @Autowired
    lateinit var eventRetention: EventRetention

    @Autowired
    lateinit var groupRetention: GroupRetention

    @Autowired
    lateinit var idempotencyRetention: IdempotencyRetention

    @Autowired
    lateinit var txManager: PlatformTransactionManager

    private val json = DhJson.json

    @BeforeEach
    fun useJdkHttpClient() {
        // 기본 HttpURLConnection 은 401(WWW-Authenticate) 응답에서 "cannot retry due to server authentication" 을 던지고
        // 본문 없는 POST 에 form Content-Type 을 붙인다 → JDK HttpClient 기반으로 교체 (IdentityFlowIT 와 같음)
        rest.restTemplate.requestFactory = JdkClientHttpRequestFactory()
    }

    // ---------------------------------------------------------------- helpers

    /** 사용자 + 그 사용자 소유 차량 하나 (차량 토큰 포함) */
    private class Driver(val user: TokenResponse, val vehicle: DeviceTokenResponse, val carInfoId: String) {
        val userToken get() = user.accessToken
        val vehicleToken get() = vehicle.vehicleAccessToken
        val vehicleId get() = vehicle.vehicle.vehicleId
        val nickname get() = vehicle.vehicle.nickname
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

    private fun ResponseEntity<String>.assertError(status: Int, code: String) {
        assertEquals(status, statusCode.value(), "status for body=$body")
        assertTrue(headers.contentType?.isCompatibleWith(MediaType.APPLICATION_JSON) == true, "content-type=${headers.contentType}")
        val envelope = decode<ErrorEnvelope>()
        assertEquals(code, envelope.error.code, body)
        assertTrue(!envelope.error.message.isNullOrBlank())
        assertTrue(!envelope.error.traceId.isNullOrBlank(), "traceId missing: $body")
    }

    private fun login(label: String): TokenResponse {
        val n = seq.incrementAndGet()
        val res = call(HttpMethod.POST, ApiPaths.AUTH_LOGIN, json.encodeToString(LoginRequest(email = "$label-$n@example.com", displayName = "사용자 $label")))
        return res.assertOk().decode()
    }

    private fun newCarId() = "CAR-INFO-GROUP-IT-${seq.incrementAndGet()}"

    private fun pair(user: TokenResponse, nickname: String, brand: String? = "HYUNDAI", model: String? = "GRANDEUR", carId: String = newCarId()): DeviceTokenResponse {
        val code: DeviceCodeResponse = call(
            HttpMethod.POST, ApiPaths.DEVICE_CODE,
            json.encodeToString(DeviceCodeRequest(vehicleId = carId, model = model, brand = brand, appVersion = "0.1.0")),
        ).assertOk().decode()
        call(HttpMethod.POST, ApiPaths.DEVICE_APPROVE, json.encodeToString(DeviceApproveRequest(code.userCode, nickname)), user.accessToken).assertOk()
        // 승인 뒤 첫 폴링은 바로 200 (폴링 제한 키가 아직 없다)
        return call(HttpMethod.POST, ApiPaths.DEVICE_TOKEN, json.encodeToString(DeviceTokenRequest(code.deviceCode))).assertOk().decode()
    }

    private fun driver(label: String, nickname: String, brand: String? = "HYUNDAI", model: String? = "GRANDEUR"): Driver {
        val user = login(label)
        val carId = newCarId()
        return Driver(user, pair(user, nickname, brand, model, carId), carId)
    }

    private fun leave(groupId: String, bearer: String, vehicleId: String? = null) =
        call(HttpMethod.POST, ApiPaths.groupLeave(groupId), vehicleId?.let { json.encodeToString(GroupLeaveRequest(it)) }, bearer)

    private fun create(d: Driver, name: String = "추석 귀성길", maxMembers: Int = 10, destination: Place = DEST): GroupCreateResponse =
        call(HttpMethod.POST, ApiPaths.GROUPS, json.encodeToString(GroupCreateRequest(name, destination, maxMembers)), d.vehicleToken).assertOk(201).decode()

    private fun join(d: Driver, token: String, vehicleId: String? = null) =
        call(HttpMethod.POST, ApiPaths.GROUPS_JOIN, json.encodeToString(GroupJoinRequest(token, vehicleId)), d.userToken)

    private fun detail(groupId: String, bearer: String) = call(HttpMethod.GET, ApiPaths.group(groupId), bearer = bearer)

    private fun detailOk(groupId: String, bearer: String): GroupDetailResponse = detail(groupId, bearer).assertOk().decode()

    private fun myGroups(d: Driver, active: Boolean? = null): List<MyGroupDto> =
        call(HttpMethod.GET, ApiPaths.ME_GROUPS + (active?.let { "?active=$it" } ?: ""), bearer = d.userToken).assertOk().decode()

    private fun groupStatus(groupId: String): String = jdbc.queryForObject("select status from travel_group where group_id = ?", String::class.java, groupId)!!

    private fun membersKey(gid: String) = GroupMembershipCache.membersKey(gid)
    private fun vehicleGroupKey(vid: String) = GroupMembershipCache.vehicleGroupKey(vid)

    private fun List<GroupEvent>.ofType(type: GroupEventType) = filter { it.type == type.name }

    // ---------------------------------------------------------------- tests

    @Test
    fun `full group flow - create, join, detail, me groups, message, delegate, reissue, leave, end`() {
        val a = driver("a", "아빠 차")
        val b = driver("b", "엄마 차", brand = "KIA", model = "EV9")
        val c = driver("c", "삼촌 차")
        val d = driver("d", "이모 차")
        val outsider = login("outsider")

        // 1) 생성 — 차량 토큰만 (사용자 토큰 403, 무토큰 401)
        call(HttpMethod.POST, ApiPaths.GROUPS, json.encodeToString(GroupCreateRequest("x", DEST)), a.userToken).assertError(403, ErrorCodes.FORBIDDEN)
        call(HttpMethod.POST, ApiPaths.GROUPS, json.encodeToString(GroupCreateRequest("x", DEST))).assertError(401, ErrorCodes.UNAUTHORIZED)
        val before = System.currentTimeMillis()
        val created = create(a, maxMembers = 3)
        assertTrue(created.groupId.startsWith("grp_"), created.groupId)
        assertEquals(a.vehicleId, created.leaderVehicleId)
        assertTrue(created.invite.token.startsWith("inv_"), created.invite.token)
        assertEquals(ApiPaths.joinUrl(props.publicBaseUrl, created.invite.token), created.invite.url)
        assertTrue(created.invite.expiresAt in (before + 9 * 60_000)..(System.currentTimeMillis() + 10 * 60_000 + 5_000), "expiresAt=${created.invite.expiresAt}")
        assertEquals(Topics.groupState(created.groupId), created.mqttTopics.state)
        assertEquals(Topics.groupEvent(created.groupId), created.mqttTopics.event)
        val gid = created.groupId
        // 토큰 원문은 DB 에 없다 (해시만)
        assertEquals(0, jdbc.queryForObject("select count(*) from group_invite where token_hash = ?", Long::class.javaObjectType, created.invite.token))
        assertEquals(1, jdbc.queryForObject("select count(*) from group_invite where group_id = ? and max_uses = 6", Long::class.javaObjectType, gid), "max_uses = 3 × (maxMembers - 1)")
        assertTrue(redisTemplate.opsForSet().isMember(membersKey(gid), a.vehicleId) == true)
        assertEquals(gid, redisTemplate.opsForValue().get(vehicleGroupKey(a.vehicleId)))

        // 1b) 초대 미리보기 — QR URL 을 JSON 으로 (인증 없음). HTML 요청은 안내 페이지
        val previewHeaders = HttpHeaders().apply { accept = listOf(MediaType.APPLICATION_JSON) }
        val previewRes = rest.exchange(java.net.URI(created.invite.url).path, HttpMethod.GET, HttpEntity<Void>(previewHeaders), String::class.java)
        assertEquals(200, previewRes.statusCode.value(), previewRes.body)
        val preview: InvitePreviewResponse = previewRes.decode()
        assertEquals(InvitePreviewResponse("추석 귀성길", a.vehicleId, "아빠 차", DEST.copy(address = "광주광역시 …"), 1, 3, created.invite.expiresAt), preview)
        val htmlHeaders = HttpHeaders().apply { accept = listOf(MediaType.TEXT_HTML) }
        val htmlRes = rest.exchange(java.net.URI(created.invite.url).path, HttpMethod.GET, HttpEntity<Void>(htmlHeaders), String::class.java)
        assertEquals(200, htmlRes.statusCode.value())
        assertTrue(htmlRes.headers.contentType!!.isCompatibleWith(MediaType.TEXT_HTML) && htmlRes.body!!.contains("그룹 합류"), htmlRes.body)
        rest.exchange("/j/inv_" + "B".repeat(43), HttpMethod.GET, HttpEntity<Void>(previewHeaders), String::class.java).assertError(410, ErrorCodes.INVITE_EXPIRED)

        // 2) 합류 — 사용자 토큰만 (차량 토큰 403). vehicleId 생략 → 대표 차량
        call(HttpMethod.POST, ApiPaths.GROUPS_JOIN, json.encodeToString(GroupJoinRequest(created.invite.token)), b.vehicleToken).assertError(403, ErrorCodes.FORBIDDEN)
        val joinedB: GroupJoinResponse = join(b, created.invite.token).assertOk().decode()
        assertEquals(gid, joinedB.groupId)
        assertEquals("추석 귀성길", joinedB.name)
        assertEquals(listOf(a.vehicleId to "LEADER", b.vehicleId to "MEMBER"), joinedB.members.map { it.vehicleId to it.role })
        assertEquals(listOf("아빠 차", "엄마 차"), joinedB.members.map { it.nickname })
        assertEquals(listOf("HYUNDAI", "KIA"), joinedB.members.map { it.brand })
        assertEquals(2, joinedB.members.map { it.color }.toSet().size, "colors must differ")
        joinedB.members.forEach { assertTrue(Regex("^#[0-9A-F]{6}$").matches(it.color), it.color) }
        assertTrue(joinedB.members.all { it.joinedAt > 0 })
        join(b, created.invite.token).assertError(409, ErrorCodes.ALREADY_MEMBER)
        join(c, created.invite.token, vehicleId = c.vehicleId).assertOk()
        join(d, created.invite.token).assertError(409, ErrorCodes.GROUP_FULL)
        assertEquals(2, jdbc.queryForObject("select used_count from group_invite where group_id = ?", Int::class.javaObjectType, gid))

        // 3) 조회 — 멤버 차량·멤버 차량 소유자 200, 남 404. 이벤트는 최신순
        val byVehicle = detailOk(gid, a.vehicleToken)
        val byUser = detailOk(gid, b.userToken)
        assertEquals(byVehicle, byUser)
        assertEquals("ACTIVE", byUser.status)
        assertEquals(DEST.copy(address = "광주광역시 …"), byUser.destination)
        assertEquals(3, byUser.members.size)
        assertNotNull(byUser.snapshot, "presence(T24) fills the snapshot for active groups")   // 텔레메트리 전이라 전원 OFFLINE
        assertTrue(byUser.snapshot!!.members.all { it.status == app.donghaeng.protocol.MemberStatus.OFFLINE })
        val joins = byUser.recentEvents.ofType(GroupEventType.MEMBER_JOINED)
        assertEquals(listOf(c.vehicleId, b.vehicleId), joins.map { it.sourceVehicleId }, "newest first")
        assertEquals("삼촌 차가 그룹에 들어왔어요.", joins[0].speech)
        assertEquals("엄마 차", joins[1].payload["nickname"]?.jsonPrimitive?.content)
        detail(gid, outsider.accessToken).assertError(404, ErrorCodes.NOT_FOUND)
        detail(gid, d.vehicleToken).assertError(404, ErrorCodes.NOT_FOUND)
        detail("grp_NOPE", a.vehicleToken).assertError(404, ErrorCodes.NOT_FOUND)

        // 4) 내 그룹
        val mineB = myGroups(b)
        assertEquals(1, mineB.size)
        assertEquals(MyGroupDto(gid, "추석 귀성길", a.vehicleId, "ACTIVE", DEST.copy(address = "광주광역시 …"), 3, listOf(b.vehicleId), mineB[0].createdAt), mineB[0])
        assertEquals(emptyList<MyGroupDto>(), myGroups(d))
        assertEquals(3, redisTemplate.opsForSet().size(membersKey(gid)))
        assertEquals(gid, redisTemplate.opsForValue().get(vehicleGroupKey(c.vehicleId)))

        // 5) 동승자 메시지 — 멤버 차량 소유자만, 1~60자
        val msg = call(HttpMethod.POST, ApiPaths.groupMessages(gid), json.encodeToString(PassengerMessageRequest("  앞에  휴게소\n들르자 ")), b.userToken).assertOk(201)
        val event: GroupEvent = msg.decode()
        assertEquals(GroupEventType.PASSENGER_MESSAGE.name, event.type)
        assertEquals(b.vehicleId, event.sourceVehicleId)
        assertEquals("엄마 차에서 온 메시지. 앞에 휴게소 들르자", event.speech)
        assertEquals("앞에 휴게소 들르자", event.payload["text"]?.jsonPrimitive?.content)
        call(HttpMethod.POST, ApiPaths.groupMessages(gid), json.encodeToString(PassengerMessageRequest("가".repeat(61))), b.userToken).assertError(400, ErrorCodes.INVALID_MESSAGE)
        call(HttpMethod.POST, ApiPaths.groupMessages(gid), json.encodeToString(PassengerMessageRequest("   ")), b.userToken).assertError(400, ErrorCodes.INVALID_MESSAGE)
        call(HttpMethod.POST, ApiPaths.groupMessages(gid), json.encodeToString(PassengerMessageRequest("hi")), outsider.accessToken).assertError(404, ErrorCodes.NOT_FOUND)
        call(HttpMethod.POST, ApiPaths.groupMessages(gid), json.encodeToString(PassengerMessageRequest("hi", fromVehicleId = a.vehicleId)), b.userToken).assertError(404, ErrorCodes.NO_PAIRED_VEHICLE)
        call(HttpMethod.POST, ApiPaths.groupMessages(gid), json.encodeToString(PassengerMessageRequest("hi")), b.vehicleToken).assertError(403, ErrorCodes.FORBIDDEN)
        assertEquals(event.eventId, detailOk(gid, a.vehicleToken).recentEvents.first().eventId)

        // 6) 방장 위임 — 방장 아닌 차량 403, 방장 차량 200
        call(HttpMethod.POST, ApiPaths.groupLeader(gid), json.encodeToString(LeaderRequest(c.vehicleId)), b.vehicleToken).assertError(403, ErrorCodes.FORBIDDEN)
        call(HttpMethod.POST, ApiPaths.groupLeader(gid), json.encodeToString(LeaderRequest(d.vehicleId)), a.vehicleToken).assertError(404, ErrorCodes.NOT_FOUND)
        call(HttpMethod.POST, ApiPaths.groupLeader(gid), json.encodeToString(LeaderRequest(a.vehicleId)), a.vehicleToken).assertError(400, ErrorCodes.VALIDATION_FAILED)
        val delegated: GroupDetailResponse = call(HttpMethod.POST, ApiPaths.groupLeader(gid), json.encodeToString(LeaderRequest(b.vehicleId)), a.userToken).assertOk().decode()
        assertEquals(b.vehicleId, delegated.leaderVehicleId)
        assertEquals(mapOf(a.vehicleId to "MEMBER", b.vehicleId to "LEADER", c.vehicleId to "MEMBER"), delegated.members.associate { it.vehicleId to it.role })
        val leaderChanged = delegated.recentEvents.ofType(GroupEventType.LEADER_CHANGED).first()
        assertEquals("이제 엄마 차가 선두예요.", leaderChanged.speech)
        assertEquals(a.vehicleId, leaderChanged.payload["previousLeaderVehicleId"]?.jsonPrimitive?.content)

        // 7) 초대 재발급 — 새 방장(의 소유자)만, 이전 토큰은 즉시 만료
        call(HttpMethod.POST, ApiPaths.groupInvites(gid), bearer = a.vehicleToken).assertError(403, ErrorCodes.FORBIDDEN)
        val reissued: InviteDto = call(HttpMethod.POST, ApiPaths.groupInvites(gid), bearer = b.userToken).assertOk().decode()
        assertNotEquals(created.invite.token, reissued.token)
        assertEquals(ApiPaths.joinUrl(props.publicBaseUrl, reissued.token), reissued.url)
        join(d, created.invite.token).assertError(410, ErrorCodes.INVITE_EXPIRED)

        // 8) 나가기 — 차량 자신. 다시 나가면 404
        call(HttpMethod.POST, ApiPaths.groupLeave(gid), bearer = c.vehicleToken).assertOk(204)
        call(HttpMethod.POST, ApiPaths.groupLeave(gid), bearer = c.vehicleToken).assertError(404, ErrorCodes.NOT_FOUND)
        val afterLeave = detailOk(gid, a.vehicleToken)
        assertEquals(listOf(a.vehicleId, b.vehicleId), afterLeave.members.map { it.vehicleId })
        val left = afterLeave.recentEvents.ofType(GroupEventType.MEMBER_LEFT).first()
        assertEquals(c.vehicleId, left.sourceVehicleId)
        assertEquals("삼촌 차가 그룹에서 나갔어요.", left.speech)
        assertEquals(GroupService.LEFT_REASON_LEFT, left.payload["reason"]?.jsonPrimitive?.content)
        assertNull(redisTemplate.opsForValue().get(vehicleGroupKey(c.vehicleId)))
        assertEquals(2, redisTemplate.opsForSet().size(membersKey(gid)))
        detail(gid, c.vehicleToken).assertError(404, ErrorCodes.NOT_FOUND)
        // 정원에 자리가 났으니 새 초대로 D 합류 가능
        join(d, reissued.token).assertOk()

        // 9) 종료 — 방장이 아닌 소유자 403, 방장 차량 204. 그 뒤 조회는 ENDED 로 보이고 변경은 409
        call(HttpMethod.POST, ApiPaths.groupEnd(gid), bearer = a.userToken).assertError(403, ErrorCodes.FORBIDDEN)
        call(HttpMethod.POST, ApiPaths.groupEnd(gid), bearer = b.vehicleToken).assertOk(204)
        assertEquals("ENDED", groupStatus(gid))
        val ended = detailOk(gid, a.userToken)
        assertEquals("ENDED", ended.status)
        assertEquals(3, ended.members.size, "roster is kept after end")
        val endedEvent = ended.recentEvents.ofType(GroupEventType.GROUP_ENDED).single()
        assertEquals("방장이 그룹을 종료했어요.", endedEvent.speech)
        assertEquals("LEADER_ENDED", endedEvent.payload["reason"]?.jsonPrimitive?.content)
        assertNull(redisTemplate.opsForValue().get(vehicleGroupKey(a.vehicleId)))
        assertNull(redisTemplate.opsForValue().get(vehicleGroupKey(d.vehicleId)))
        assertEquals(false, redisTemplate.hasKey(membersKey(gid)))
        assertEquals(0, jdbc.queryForObject("select count(*) from group_invite where group_id = ? and expires_at > now()", Long::class.javaObjectType, gid))
        call(HttpMethod.POST, ApiPaths.groupEnd(gid), bearer = b.vehicleToken).assertError(409, ErrorCodes.GROUP_ENDED)
        call(HttpMethod.POST, ApiPaths.groupLeave(gid), bearer = a.vehicleToken).assertError(409, ErrorCodes.GROUP_ENDED)
        call(HttpMethod.POST, ApiPaths.groupInvites(gid), bearer = b.vehicleToken).assertError(409, ErrorCodes.GROUP_ENDED)
        call(HttpMethod.POST, ApiPaths.groupMessages(gid), json.encodeToString(PassengerMessageRequest("hi")), b.userToken).assertError(409, ErrorCodes.GROUP_ENDED)
        join(c, reissued.token).assertError(410, ErrorCodes.INVITE_EXPIRED)
        assertEquals(emptyList<MyGroupDto>(), myGroups(b, active = true))
        assertEquals(listOf("ENDED"), myGroups(b, active = false).map { it.status })
        assertNotNull(myGroups(b).single().endedAt)
    }

    @Test
    fun `leader leaving promotes the earliest member, last member leaving ends the group`() {
        val a = driver("la", "아빠 차")
        val b = driver("lb", "엄마 차")
        val c = driver("lc", "삼촌 차")
        val created = create(a)
        val gid = created.groupId
        join(b, created.invite.token).assertOk()
        join(c, created.invite.token).assertOk()

        call(HttpMethod.POST, ApiPaths.groupLeave(gid), bearer = a.vehicleToken).assertOk(204)
        val afterLeader = detailOk(gid, b.userToken)
        assertEquals(b.vehicleId, afterLeader.leaderVehicleId, "earliest remaining member becomes leader")
        assertEquals(listOf(b.vehicleId to "LEADER", c.vehicleId to "MEMBER"), afterLeader.members.map { it.vehicleId to it.role })
        val left = afterLeader.recentEvents.ofType(GroupEventType.MEMBER_LEFT).first()
        assertEquals("아빠 차가 그룹에서 나갔어요. 이제 엄마 차가 선두예요.", left.speech)
        assertEquals(b.vehicleId, left.payload["newLeaderVehicleId"]?.jsonPrimitive?.content)
        assertNull(afterLeader.recentEvents.ofType(GroupEventType.LEADER_CHANGED).first().speech, "succession speech is carried by MEMBER_LEFT")

        // 사용자 토큰으로 나가기 (vehicleId 생략 → 이 그룹의 내 차 전부), 본문 없이도 된다
        call(HttpMethod.POST, ApiPaths.groupLeave(gid), json.encodeToString(GroupLeaveRequest()), c.userToken).assertOk(204)
        call(HttpMethod.POST, ApiPaths.groupLeave(gid), bearer = b.userToken).assertOk(204)
        assertEquals("ENDED", groupStatus(gid))
        // 모두 나간 그룹은 아무도 볼 수 없다(현재 멤버가 없음) → 이벤트는 DB 로 확인
        detail(gid, b.vehicleToken).assertError(404, ErrorCodes.NOT_FOUND)
        detail(gid, b.userToken).assertError(404, ErrorCodes.NOT_FOUND)
        val rows = jdbc.queryForList("select type, speech, payload::text as payload from group_event where group_id = ? order by occurred_at desc, event_id desc", gid)
        assertEquals(listOf("GROUP_ENDED", "MEMBER_LEFT", "MEMBER_LEFT", "LEADER_CHANGED", "MEMBER_LEFT", "MEMBER_JOINED", "MEMBER_JOINED"), rows.map { it["type"] })
        fun reasonOf(row: Map<String, Any>) = json.parseToJsonElement(row["payload"] as String).jsonObject["reason"]?.jsonPrimitive?.content
        val endedRow = rows.first()
        assertNull(endedRow["speech"], "nobody left to hear it")
        assertEquals("ALL_LEFT", reasonOf(endedRow), endedRow["payload"].toString())
        assertEquals(GroupService.LEFT_REASON_LEFT, reasonOf(rows[1]))
        assertEquals("엄마 차가 그룹에서 나갔어요.", rows[1]["speech"], "last leaver: no successor")
        assertEquals(false, redisTemplate.hasKey(membersKey(gid)))
        assertEquals(emptyList<MyGroupDto>(), myGroups(b), "left members do not list the ended group")
    }

    @Test
    fun `creating or joining another group leaves the previous one automatically`() {
        val a = driver("sa", "아빠 차")
        val b = driver("sb", "엄마 차")
        val c = driver("sc", "삼촌 차")

        val g1 = create(a, name = "첫 그룹")
        val g2 = create(a, name = "둘째 그룹")
        assertEquals("ENDED", groupStatus(g1.groupId), "sole member left → ended")
        assertEquals("ACTIVE", groupStatus(g2.groupId))
        assertEquals(g2.groupId, redisTemplate.opsForValue().get(vehicleGroupKey(a.vehicleId)))
        // 나간(자동 이탈한) 그룹은 내 그룹 목록에 없다 — 종료 시점까지 남아 있던 그룹만 ENDED 로 남는다
        assertEquals(listOf(g2.groupId), myGroups(a).map { it.groupId })
        detail(g1.groupId, a.vehicleToken).assertError(404, ErrorCodes.NOT_FOUND)

        join(b, g2.invite.token).assertOk()
        val g3 = create(c, name = "셋째 그룹")
        val joinedG3: GroupJoinResponse = join(b, g3.invite.token).assertOk().decode()
        assertEquals(listOf(c.vehicleId, b.vehicleId), joinedG3.members.map { it.vehicleId })
        val g2Detail = detailOk(g2.groupId, a.vehicleToken)
        assertEquals(listOf(a.vehicleId), g2Detail.members.map { it.vehicleId })
        assertEquals(GroupService.LEFT_REASON_SWITCHED, g2Detail.recentEvents.ofType(GroupEventType.MEMBER_LEFT).first().payload["reason"]?.jsonPrimitive?.content)
        assertEquals(g3.groupId, redisTemplate.opsForValue().get(vehicleGroupKey(b.vehicleId)))
        assertEquals(false, redisTemplate.opsForSet().isMember(membersKey(g2.groupId), b.vehicleId))
        assertEquals(listOf(g3.groupId), myGroups(b, active = true).map { it.groupId })

        // C 가 g3 를 종료 → B 에게 g3 는 ENDED 로 남고, B 가 g2 에 재합류(나갔던 멤버 행 되살림) → ACTIVE 먼저 정렬
        call(HttpMethod.POST, ApiPaths.groupEnd(g3.groupId), bearer = c.vehicleToken).assertOk(204)
        val rejoined: GroupJoinResponse = join(b, g2.invite.token).assertOk().decode()
        assertEquals(listOf(a.vehicleId to "LEADER", b.vehicleId to "MEMBER"), rejoined.members.map { it.vehicleId to it.role })
        assertEquals(1, jdbc.queryForObject("select count(*) from group_member where group_id = ? and vehicle_id = ?", Long::class.javaObjectType, g2.groupId, b.vehicleId), "rejoin reuses the row")
        assertEquals(listOf(g2.groupId to "ACTIVE", g3.groupId to "ENDED"), myGroups(b).map { it.groupId to it.status }, "ACTIVE first")
        assertEquals(g2.groupId, redisTemplate.opsForValue().get(vehicleGroupKey(b.vehicleId)))
    }

    @Test
    fun `invite errors - expired, unknown, malformed, no vehicle, not my vehicle`() {
        val a = driver("ea", "아빠 차")
        val b = driver("eb", "엄마 차")
        val noCar = login("nocar")
        val created = create(a)

        call(HttpMethod.POST, ApiPaths.GROUPS_JOIN, json.encodeToString(GroupJoinRequest(created.invite.token)), noCar.accessToken).assertError(404, ErrorCodes.NO_PAIRED_VEHICLE)
        join(b, created.invite.token, vehicleId = a.vehicleId).assertError(404, ErrorCodes.NO_PAIRED_VEHICLE)
        join(b, "inv_" + "A".repeat(43)).assertError(410, ErrorCodes.INVITE_EXPIRED)
        join(b, "not-a-token").assertError(410, ErrorCodes.INVITE_EXPIRED)
        join(b, "").assertError(410, ErrorCodes.INVITE_EXPIRED)

        jdbc.update("update group_invite set expires_at = now() - interval '1 minute' where group_id = ?", created.groupId)
        join(b, created.invite.token).assertError(410, ErrorCodes.INVITE_EXPIRED)
        assertEquals("ACTIVE", groupStatus(created.groupId))

        // 사용 횟수 초과도 만료로 본다
        val fresh: InviteDto = call(HttpMethod.POST, ApiPaths.groupInvites(created.groupId), bearer = a.vehicleToken).assertOk().decode()
        jdbc.update("update group_invite set used_count = max_uses where group_id = ? and expires_at > now()", created.groupId)
        join(b, fresh.token).assertError(410, ErrorCodes.INVITE_EXPIRED)
    }

    @Test
    fun `create validation and revoked vehicle`() {
        val a = driver("va", "아빠 차")
        fun create(body: String) = call(HttpMethod.POST, ApiPaths.GROUPS, body, a.vehicleToken)

        create(json.encodeToString(GroupCreateRequest("  ", DEST))).assertError(400, ErrorCodes.VALIDATION_FAILED)
        create(json.encodeToString(GroupCreateRequest("가".repeat(31), DEST))).assertError(400, ErrorCodes.VALIDATION_FAILED)
        create(json.encodeToString(GroupCreateRequest("x", DEST, maxMembers = 1))).assertError(400, ErrorCodes.VALIDATION_FAILED)
        create(json.encodeToString(GroupCreateRequest("x", DEST, maxMembers = 11))).assertError(400, ErrorCodes.VALIDATION_FAILED)
        create(json.encodeToString(GroupCreateRequest("x", DEST.copy(lat = 95.0)))).assertError(400, ErrorCodes.VALIDATION_FAILED)
        create(json.encodeToString(GroupCreateRequest("x", DEST.copy(lon = -181.0)))).assertError(400, ErrorCodes.VALIDATION_FAILED)
        create(json.encodeToString(GroupCreateRequest("x", DEST.copy(name = "")))).assertError(400, ErrorCodes.VALIDATION_FAILED)
        create("""{"name":"x"}""").assertError(400, ErrorCodes.VALIDATION_FAILED) // destination 누락
        create("{not json").assertError(400, ErrorCodes.VALIDATION_FAILED)

        // 이름·주소는 trim 되어 저장된다
        val ok = call(HttpMethod.POST, ApiPaths.GROUPS, json.encodeToString(GroupCreateRequest("  강릉 여행  ", DEST.copy(name = " 강릉역 ", address = "  "), 2)), a.vehicleToken).assertOk(201).decode<GroupCreateResponse>()
        val d = detailOk(ok.groupId, a.vehicleToken)
        assertEquals("강릉 여행", d.name)
        assertEquals(Place(DEST.lat, DEST.lon, "강릉역"), d.destination)

        // 페어링 해제된 차량의 (아직 유효한) 토큰으로는 그룹을 만들 수 없다
        call(HttpMethod.DELETE, ApiPaths.meVehicle(a.vehicleId), bearer = a.userToken).assertOk(204)
        create(json.encodeToString(GroupCreateRequest("x", DEST))).assertError(403, ErrorCodes.FORBIDDEN)
        detail(ok.groupId, a.userToken).assertError(404, ErrorCodes.NOT_FOUND)
    }

    @Test
    fun `vehicle commands - GROUP_JOINED to the joiner and GROUP_ENDED to every member, only after commit`() {
        val a = driver("ca", "아빠 차")
        val b = driver("cb", "엄마 차")
        val created = create(a)
        val before = commands.joined.size
        join(b, created.invite.token).assertOk()
        val joined = commands.joined.drop(before).single()
        assertEquals(b.vehicleId, joined.first)
        assertEquals(GroupJoined(created.groupId, "추석 귀성길", a.vehicleId, DEST.copy(address = "광주광역시 …"), created.mqttTopics), joined.second)
        // 실패한 합류(만료)는 cmd 를 내지 않는다
        val c = driver("cc", "삼촌 차")
        jdbc.update("update group_invite set expires_at = now() - interval '1 minute' where group_id = ?", created.groupId)
        join(c, created.invite.token).assertError(410, ErrorCodes.INVITE_EXPIRED)
        assertEquals(before + 1, commands.joined.size)

        val endedBefore = commands.ended.size
        call(HttpMethod.POST, ApiPaths.groupEnd(created.groupId), bearer = a.vehicleToken).assertOk(204)
        val ended = commands.ended.drop(endedBefore)
        assertEquals(setOf(a.vehicleId, b.vehicleId), ended.map { it.first }.toSet())
        assertTrue(ended.all { it.second == GroupEnded(created.groupId, "LEADER_ENDED") }, ended.toString())
    }

    @Test
    fun `user with two cars - leave one by vehicleId, leadership passes to own second car, leave all ends the group`() {
        val owner = login("two-cars")
        val carA = pair(owner, "첫째 차")
        val carB = pair(owner, "둘째 차")
        val other = driver("tc-other", "남의 차")
        val created: GroupCreateResponse = call(HttpMethod.POST, ApiPaths.GROUPS, json.encodeToString(GroupCreateRequest("두 대", DEST)), carA.vehicleAccessToken).assertOk(201).decode()
        val gid = created.groupId
        call(HttpMethod.POST, ApiPaths.GROUPS_JOIN, json.encodeToString(GroupJoinRequest(created.invite.token, carB.vehicle.vehicleId)), owner.accessToken).assertOk()
        join(other, created.invite.token).assertOk()
        assertEquals(3, detailOk(gid, owner.accessToken).members.size)

        // 남의 차 ID·없는 ID 로는 못 뺀다 (404 NO_PAIRED_VEHICLE), 깨진 본문은 400
        leave(gid, owner.accessToken, vehicleId = other.vehicleId).assertError(404, ErrorCodes.NO_PAIRED_VEHICLE)
        leave(gid, owner.accessToken, vehicleId = "veh_NOPE").assertError(404, ErrorCodes.NO_PAIRED_VEHICLE)
        call(HttpMethod.POST, ApiPaths.groupLeave(gid), "{not json", owner.accessToken).assertError(400, ErrorCodes.VALIDATION_FAILED)
        // 비멤버 사용자의 나가기 → 404 NO_PAIRED_VEHICLE
        leave(gid, login("tc-stranger").accessToken).assertError(404, ErrorCodes.NO_PAIRED_VEHICLE)

        // 방장(첫째 차)만 빼면 둘째 차가 방장을 잇는다 (합류순: 둘째 차가 남의 차보다 먼저)
        leave(gid, owner.accessToken, vehicleId = carA.vehicle.vehicleId).assertOk(204)
        val afterOne = detailOk(gid, owner.accessToken)
        assertEquals(carB.vehicle.vehicleId, afterOne.leaderVehicleId)
        assertEquals(listOf(carB.vehicle.vehicleId to "LEADER", other.vehicleId to "MEMBER"), afterOne.members.map { it.vehicleId to it.role })
        // 차량 토큰의 나가기는 본문의 vehicleId 를 무시하고 자기 자신을 뺀다
        leave(gid, other.vehicleToken, vehicleId = carB.vehicle.vehicleId).assertOk(204)
        assertEquals(listOf(carB.vehicle.vehicleId), detailOk(gid, owner.accessToken).members.map { it.vehicleId })
        // 본문 없이 → 이 그룹의 내 차 전부 → 마지막 멤버라 종료
        leave(gid, owner.accessToken).assertOk(204)
        assertEquals("ENDED", groupStatus(gid))
        assertEquals(emptyList<MyGroupDto>(), myGroups(Driver(owner, carA, "x"), active = true))
    }

    @Test
    fun `join check order - no vehicle wins over malformed token, colors are reused, invite stored as sha256`() {
        val noCar = login("order-nocar")
        call(HttpMethod.POST, ApiPaths.GROUPS_JOIN, json.encodeToString(GroupJoinRequest("not-a-token")), noCar.accessToken).assertError(404, ErrorCodes.NO_PAIRED_VEHICLE)
        call(HttpMethod.POST, ApiPaths.GROUPS_JOIN, json.encodeToString(GroupJoinRequest("")), noCar.accessToken).assertError(404, ErrorCodes.NO_PAIRED_VEHICLE)

        val a = driver("col-a", "아빠 차")
        val b = driver("col-b", "엄마 차")
        val c = driver("col-c", "삼촌 차")
        val d = driver("col-d", "이모 차")
        val created = create(a)
        join(b, created.invite.token).assertOk()
        join(c, created.invite.token).assertOk()
        val colors = detailOk(created.groupId, a.vehicleToken).members.associate { it.vehicleId to it.color }
        assertEquals(MemberColors.PALETTE.take(3), listOf(colors[a.vehicleId], colors[b.vehicleId], colors[c.vehicleId]))
        leave(created.groupId, b.vehicleToken).assertOk(204)
        val joinedD: GroupJoinResponse = join(d, created.invite.token).assertOk().decode()
        assertEquals(MemberColors.PALETTE[1], joinedD.members.first { it.vehicleId == d.vehicleId }.color, "freed colour is reused")
        assertEquals(1, jdbc.queryForObject("select count(*) from group_invite where token_hash = ?", Long::class.javaObjectType, SecureTokens.sha256Hex(created.invite.token)))
    }

    @Test
    fun `concurrent joins never exceed maxMembers`() {
        val a = driver("cc-a", "아빠 차")
        val created = create(a, maxMembers = 3)
        val joiners = (1..6).map { driver("cc-$it", "차 $it") }
        val pool = Executors.newFixedThreadPool(joiners.size)
        try {
            val futures = joiners.map { d -> pool.submit<Int> { join(d, created.invite.token).statusCode.value() } }
            val statuses = futures.map { it.get(60, TimeUnit.SECONDS) }
            assertEquals(2, statuses.count { it == 200 }, "exactly two seats: $statuses")
            assertEquals(4, statuses.count { it == 409 }, "the rest are GROUP_FULL: $statuses")
        } finally {
            pool.shutdownNow()
        }
        assertEquals(3, detailOk(created.groupId, a.vehicleToken).members.size)
        assertEquals(2, jdbc.queryForObject("select used_count from group_invite where group_id = ?", Int::class.javaObjectType, created.groupId))
        assertEquals(3, redisTemplate.opsForSet().size(membersKey(created.groupId)))
    }

    @Test
    fun `unpairing evicts the vehicle from its group, and a re-paired car rejects tokens of the previous owner`() {
        val a = driver("up-a", "아빠 차")
        val b = driver("up-b", "엄마 차")
        val created = create(a)
        val gid = created.groupId
        join(b, created.invite.token).assertOk()

        // b 가 폰에서 페어링 해제 → 그룹에서 빠진다(MEMBER_LEFT reason UNPAIRED), b 의 (아직 유효한) 차량 토큰은 403
        call(HttpMethod.DELETE, ApiPaths.meVehicle(b.vehicleId), bearer = b.userToken).assertOk(204)
        val afterRevoke = detailOk(gid, a.vehicleToken)
        assertEquals(listOf(a.vehicleId), afterRevoke.members.map { it.vehicleId })
        val left = afterRevoke.recentEvents.ofType(GroupEventType.MEMBER_LEFT).first()
        assertEquals(b.vehicleId, left.sourceVehicleId)
        assertEquals(GroupService.LEFT_REASON_UNPAIRED, left.payload["reason"]?.jsonPrimitive?.content)
        detail(gid, b.vehicleToken).assertError(403, ErrorCodes.FORBIDDEN)
        assertNull(redisTemplate.opsForValue().get(vehicleGroupKey(b.vehicleId)))

        // 다른 계정 d 가 a 의 차를 다시 페어링(소유권 이전) → a 의 그룹에서 빠지고(혼자라 종료), a 의 옛 토큰(owner=a)은 403
        val d = login("up-d")
        val taken = pair(d, "넘겨받은 차", carId = a.carInfoId)
        assertEquals(a.vehicleId, taken.vehicle.vehicleId, "same car → same vehicle row, new owner")
        assertEquals("ENDED", groupStatus(gid))
        call(HttpMethod.POST, ApiPaths.GROUPS, json.encodeToString(GroupCreateRequest("옛 토큰", DEST)), a.vehicleToken).assertError(403, ErrorCodes.FORBIDDEN)
        detail(gid, a.vehicleToken).assertError(403, ErrorCodes.FORBIDDEN)
        // 새 소유자의 새 차량 토큰으로는 그룹을 만들 수 있다
        call(HttpMethod.POST, ApiPaths.GROUPS, json.encodeToString(GroupCreateRequest("새 주인", DEST)), taken.vehicleAccessToken).assertOk(201)
        val rows = jdbc.queryForList("select type, payload::text as payload from group_event where group_id = ? order by occurred_at desc, event_id desc", gid)
        assertEquals("GROUP_ENDED", rows[0]["type"])
        assertEquals(GroupService.LEFT_REASON_TRANSFERRED, json.parseToJsonElement(rows[1]["payload"] as String).jsonObject["reason"]?.jsonPrimitive?.content)
    }

    @Test
    fun `T33 - TOKEN_REVOKED goes to the previous vehicleId after unpair and after an ownership transfer, never for a same-owner re-pair`() {
        val a = driver("tr-a", "아빠 차")
        val b = driver("tr-b", "엄마 차")
        val created = create(a)
        join(b, created.invite.token).assertOk()

        // 1) 폰에서 페어링 해제 → 커밋 뒤 b 에 TOKEN_REVOKED 한 번 (그룹 정리 cmd 와 함께)
        val before = commands.revoked.size
        call(HttpMethod.DELETE, ApiPaths.meVehicle(b.vehicleId), bearer = b.userToken).assertOk(204)
        assertEquals(listOf(b.vehicleId), commands.revoked.drop(before))
        // 이미 해제된 차를 또 해제(404) → 롤백이라 보내지 않는다
        call(HttpMethod.DELETE, ApiPaths.meVehicle(b.vehicleId), bearer = b.userToken).assertError(404, ErrorCodes.NOT_FOUND)
        assertEquals(before + 1, commands.revoked.size)

        // 2) 같은 소유자가 같은 차를 다시 페어링 → 소유권 이전이 아니므로 보내지 않는다(차량 앱이 새 토큰을 받는 중이다)
        val sameOwner = pair(a.user, "아빠 차 다시", carId = a.carInfoId)
        assertEquals(a.vehicleId, sameOwner.vehicle.vehicleId)
        assertEquals(before + 1, commands.revoked.size)

        // 3) 다른 계정이 a 의 차를 승인(소유권 이전) → 이전 vehicleId(= 같은 차량 행) 에 TOKEN_REVOKED
        val d = login("tr-d")
        val taken = pair(d, "넘겨받은 차", carId = a.carInfoId)
        assertEquals(a.vehicleId, taken.vehicle.vehicleId)
        assertEquals(listOf(b.vehicleId, a.vehicleId), commands.revoked.drop(before))
    }

    // ---------------------------------------------------------------- 2026-11 점검 반영 (계약 1·2·3, C032·C033·C104·C106·C108)

    private fun vehicleGroup(bearer: String) = call(HttpMethod.GET, ApiPaths.VEHICLES_ME_GROUP, bearer = bearer)

    private fun message(groupId: String, text: String, bearer: String, key: String? = null): ResponseEntity<String> {
        val headers = HttpHeaders().apply {
            accept = listOf(MediaType.APPLICATION_JSON)
            contentType = MediaType.APPLICATION_JSON
            setBearerAuth(bearer)
            if (key != null) set(ApiHeaders.IDEMPOTENCY_KEY, key)
        }
        return rest.exchange(ApiPaths.groupMessages(groupId), HttpMethod.POST, HttpEntity(json.encodeToString(PassengerMessageRequest(text)), headers), String::class.java)
    }

    @Test
    fun `contract 1 - GET v1 vehicles me group returns the current group as GROUP_JOINED or 204, and repairs a stale cache`() {
        val a = driver("rc-a", "아빠 차")
        val b = driver("rc-b", "엄마 차")
        assertEquals(204, vehicleGroup(a.vehicleToken).statusCode.value(), "그룹 전")
        val created = create(a)
        val forLeader: GroupJoined = vehicleGroup(a.vehicleToken).assertOk().decode()
        assertEquals(GroupJoined(created.groupId, "추석 귀성길", a.vehicleId, DEST.copy(address = "광주광역시 …"), created.mqttTopics), forLeader)
        join(b, created.invite.token).assertOk()
        assertEquals(forLeader, vehicleGroup(b.vehicleToken).assertOk().decode<GroupJoined>(), "cmd GROUP_JOINED 와 같은 본문")
        vehicleGroup(b.userToken).assertError(403, ErrorCodes.FORBIDDEN)
        call(HttpMethod.GET, ApiPaths.VEHICLES_ME_GROUP).assertError(401, ErrorCodes.UNAUTHORIZED)

        // 나간 뒤 204. 캐시에 옛 소속이 남아 있어도(쓰기 실패 흉내) DB 기준으로 답하고 캐시를 고친다
        leave(created.groupId, b.vehicleToken).assertOk(204)
        redisTemplate.opsForValue().set(vehicleGroupKey(b.vehicleId), created.groupId)
        assertEquals(204, vehicleGroup(b.vehicleToken).statusCode.value())
        assertNull(redisTemplate.opsForValue().get(vehicleGroupKey(b.vehicleId)), "옛 소속 캐시를 지운다(read-repair)")
        // 캐시가 비어 있으면 채운다
        redisTemplate.delete(vehicleGroupKey(a.vehicleId))
        vehicleGroup(a.vehicleToken).assertOk()
        assertEquals(created.groupId, redisTemplate.opsForValue().get(vehicleGroupKey(a.vehicleId)))

        // 끝난 그룹 → 204
        call(HttpMethod.POST, ApiPaths.groupEnd(created.groupId), bearer = a.vehicleToken).assertOk(204)
        assertEquals(204, vehicleGroup(a.vehicleToken).statusCode.value())
    }

    /**
     * RC-2: 해제·소유권 이전된 차의 (서명상 아직 유효한) 토큰도 계약 1 대로 204('진행 중 그룹 없음') — 403 이면 차량 앱이 Unknown 으로 보고 옛 세션을 남긴다(T33).
     * 옛 토큰으로는 새 소유자의 그룹을 보지 못하고, 새 소유자의 소속 캐시도 건드리지 않는다. 자격 증명 정리는 refresh 401 경로가 맡는다.
     */
    @Test
    fun `RC-2 - an unpaired or transferred car token gets 204 from GET v1 vehicles me group, never the new owner's group`() {
        val a = driver("rc2-a", "아빠 차")
        val b = driver("rc2-b", "엄마 차")
        val created = create(a)
        join(b, created.invite.token).assertOk()
        assertEquals(created.groupId, vehicleGroup(b.vehicleToken).assertOk().decode<GroupJoined>().groupId)

        // 폰에서 해제 → 그 차의 옛 토큰은 204(차가 세션을 지운다)
        call(HttpMethod.DELETE, ApiPaths.meVehicle(b.vehicleId), bearer = b.userToken).assertOk(204)
        assertEquals(204, vehicleGroup(b.vehicleToken).statusCode.value(), "해제된 차")

        // 다른 계정이 a 의 차를 다시 페어링해 새 그룹을 만든다 → a 의 옛 토큰은 204, 새 그룹을 보지 못한다
        val d = login("rc2-d")
        val taken = pair(d, "넘겨받은 차", carId = a.carInfoId)
        assertEquals(a.vehicleId, taken.vehicle.vehicleId)
        val newGroup: GroupCreateResponse = call(HttpMethod.POST, ApiPaths.GROUPS, json.encodeToString(GroupCreateRequest("새 주인", DEST)), taken.vehicleAccessToken).assertOk(201).decode()
        val stale = vehicleGroup(a.vehicleToken)
        assertEquals(204, stale.statusCode.value(), "소유권이 넘어간 차의 옛 토큰")
        assertNull(stale.body, "본문 없음 — 새 소유자의 그룹을 알려 주지 않는다")
        assertEquals(newGroup.groupId, redisTemplate.opsForValue().get(vehicleGroupKey(a.vehicleId)), "옛 토큰은 새 소유자의 소속 캐시를 고치지 않는다")
        assertEquals(newGroup.groupId, vehicleGroup(taken.vehicleAccessToken).assertOk().decode<GroupJoined>().groupId, "새 토큰은 새 그룹")
        // 다른 그룹 API 는 그대로 403(자격 거절)
        detail(newGroup.groupId, a.vehicleToken).assertError(403, ErrorCodes.FORBIDDEN)
    }

    @Test
    fun `contract 2 - DELETE v1 vehicles me unpairs the car itself through the same path as the phone, idempotently`() {
        val a = driver("su-a", "아빠 차")
        val b = driver("su-b", "엄마 차")
        val created = create(a)
        join(b, created.invite.token).assertOk()
        val revokedBefore = commands.revoked.size

        call(HttpMethod.DELETE, ApiPaths.VEHICLES_ME, bearer = b.userToken).assertError(403, ErrorCodes.FORBIDDEN)
        call(HttpMethod.DELETE, ApiPaths.VEHICLES_ME, bearer = b.vehicleToken).assertOk(204)
        val left = detailOk(created.groupId, a.vehicleToken).recentEvents.ofType(GroupEventType.MEMBER_LEFT).first()
        assertEquals(b.vehicleId, left.sourceVehicleId)
        assertEquals(MemberLeftReason.UNPAIRED, left.payload["reason"]?.jsonPrimitive?.content)
        assertEquals(listOf(b.vehicleId), commands.revoked.drop(revokedBefore), "폰의 해제와 같이 커밋 뒤 TOKEN_REVOKED")
        assertNotNull(jdbc.queryForObject("select revoked_at from vehicle where vehicle_id = ?", java.sql.Timestamp::class.java, b.vehicleId))
        assertEquals(0, jdbc.queryForObject("select count(*) from refresh_token where subject = ? and revoked_at is null", Long::class.javaObjectType, b.vehicleId))
        // 이미 해제 → 204(멱등), 아무 일도 없다
        call(HttpMethod.DELETE, ApiPaths.VEHICLES_ME, bearer = b.vehicleToken).assertOk(204)
        assertEquals(revokedBefore + 1, commands.revoked.size)

        // 소유권이 넘어간 차의 옛 토큰으로는 새 소유자의 연결을 끊지 못한다(204, 변화 없음)
        val newOwner = login("su-new")
        val taken = pair(newOwner, "넘겨받은 차", carId = a.carInfoId)
        call(HttpMethod.DELETE, ApiPaths.VEHICLES_ME, bearer = a.vehicleToken).assertOk(204)
        assertNull(jdbc.queryForObject("select revoked_at from vehicle where vehicle_id = ?", java.sql.Timestamp::class.java, taken.vehicle.vehicleId))
    }

    @Test
    fun `C032 - a leader route change becomes the group route for late joiners, preview and the reconcile endpoint`() {
        val a = driver("rt-a", "아빠 차")
        val b = driver("rt-b", "엄마 차")
        val created = create(a)
        join(b, created.invite.token).assertOk()
        val sokcho = Place(38.2070, 128.5918, "속초")
        val restStop = Place(37.2963, 127.4120, "덕평휴게소")

        assertNull(routeSync.onRouteChanged(b.vehicleId, RouteChanged(sokcho)), "방장이 아니면 무시")
        assertEquals(DEST.copy(address = "광주광역시 …"), detailOk(created.groupId, a.vehicleToken).destination)

        assertNotNull(routeSync.onRouteChanged(a.vehicleId, RouteChanged(sokcho, listOf(restStop))))
        assertEquals(sokcho, detailOk(created.groupId, a.vehicleToken).destination, "그룹 목적지가 바뀐다")
        val routeEvent = detailOk(created.groupId, b.userToken).recentEvents.ofType(GroupEventType.ROUTE_CHANGED).single()
        assertNull(routeEvent.speech, "RC-1: 그룹 이벤트에는 문구가 없다(피드는 라벨 '경로 변경', 멤버는 제안 cmd 문구만 듣는다)")
        val previewHeaders = HttpHeaders().apply { accept = listOf(MediaType.APPLICATION_JSON) }
        val preview: InvitePreviewResponse = rest.exchange(java.net.URI(created.invite.url).path, HttpMethod.GET, HttpEntity<Void>(previewHeaders), String::class.java).decode()
        assertEquals(sokcho, preview.destination)
        assertEquals(sokcho, vehicleGroup(b.vehicleToken).assertOk().decode<GroupJoined>().destination)

        // 늦게 합류한 C: GROUP_JOINED 의 목적지가 최신이고, 그 cmd 를 보낸 뒤 경유지 재전송 이벤트가 난다
        val c = driver("rt-c", "삼촌 차")
        join(c, created.invite.token).assertOk()
        assertEquals(sokcho, commands.joined.last { it.first == c.vehicleId }.second.destination)
        val (joinedEvent, joinedCmdsBefore) = joinedEvents.events.last { it.first.vehicleId == c.vehicleId }
        assertEquals(listOf(restStop), joinedEvent.waypoints)
        assertEquals(sokcho, joinedEvent.destination)
        assertEquals(1, joinedCmdsBefore, "경로 제안은 GROUP_JOINED 를 보낸 뒤에")

        // 그룹이 끝나면 경유지(위치성 데이터)는 지운다
        call(HttpMethod.POST, ApiPaths.groupEnd(created.groupId), bearer = a.vehicleToken).assertOk(204)
        assertNull(jdbc.queryForObject("select route_waypoints::text from travel_group where group_id = ?", String::class.java, created.groupId))
    }

    @Test
    fun `contract 3 and C106 - passenger messages replay by Idempotency-Key and are limited to one per 2 s per group`() {
        val a = driver("mi-a", "아빠 차")
        val b = driver("mi-b", "엄마 차")
        val created = create(a)
        join(b, created.invite.token).assertOk()
        val gid = created.groupId

        val first: GroupEvent = message(gid, "출발할게요", b.userToken, key = "123e4567-e89b-12d3-a456-426614174000").assertOk(201).decode()
        val replay: GroupEvent = message(gid, "출발할게요", b.userToken, key = "123e4567-e89b-12d3-a456-426614174000").assertOk(201).decode()
        assertEquals(first, replay, "같은 키 = 첫 응답 그대로(빈도 제한에도 걸리지 않는다)")
        message(gid, "출발할게요", b.userToken, key = "bad").assertError(400, ErrorCodes.VALIDATION_FAILED)

        // 첫 메시지를 '방금'으로 고정(느린 기계에서도 2초 안) — 같은 그룹의 다음 메시지는 429
        jdbc.update("update group_event set occurred_at = now() where group_id = ? and type = 'PASSENGER_MESSAGE'", gid)
        val limited = message(gid, "한 번 더", a.userToken, key = "msg-key-00000002")
        limited.assertError(429, ErrorCodes.RATE_LIMITED)
        assertTrue((limited.headers.getFirst(HttpHeaders.RETRY_AFTER)?.toInt() ?: 0) in 1..2, "Retry-After=${limited.headers.getFirst(HttpHeaders.RETRY_AFTER)}")
        // 실패는 기억하지 않는다 — 간격이 지난 뒤 같은 키로 다시 보내면 처리된다
        jdbc.update("update group_event set occurred_at = occurred_at - interval '5 seconds' where group_id = ? and type = 'PASSENGER_MESSAGE'", gid)
        val second: GroupEvent = message(gid, "한 번 더", a.userToken, key = "msg-key-00000002").assertOk(201).decode()
        assertNotEquals(first.eventId, second.eventId)
        assertEquals(2, jdbc.queryForObject("select count(*) from group_event where group_id = ? and type = 'PASSENGER_MESSAGE'", Long::class.javaObjectType, gid), "재시도는 저장·팬아웃하지 않는다")
        assertEquals(2, jdbc.queryForObject("select count(*) from idempotency_record where request_path = ?", Long::class.javaObjectType, ApiPaths.groupMessages(gid)))
    }

    @Test
    fun `C104 - an ownership transfer tells the old group the old nickname, not the new owner's`() {
        val a = driver("on-a", "아빠 차")
        val b = driver("on-b", "엄마 차")
        val created = create(a)
        join(b, created.invite.token).assertOk()
        val newOwner = login("on-new")
        pair(newOwner, "넘겨받은 차", carId = a.carInfoId)
        val left = detailOk(created.groupId, b.userToken).recentEvents.ofType(GroupEventType.MEMBER_LEFT).first()
        assertEquals(MemberLeftReason.OWNER_CHANGED, left.payload["reason"]?.jsonPrimitive?.content)
        assertEquals("아빠 차", left.payload["nickname"]?.jsonPrimitive?.content)
        assertEquals("아빠 차가 그룹에서 나갔어요. 이제 엄마 차가 선두예요.", left.speech)
    }

    @Test
    fun `C108 - a join racing an unpair waits for the vehicle lock and sees the revoke, so the unpaired car never becomes a member`() {
        val a = driver("ru-a", "아빠 차")
        val b = driver("ru-b", "엄마 차")
        val created = create(a)
        val pool = Executors.newSingleThreadExecutor()
        try {
            // 해제 트랜잭션 흉내(MyVehicleService.revoke → evictVehicle): revoked_at 을 쓰고 차량 advisory 를 쥔 채 커밋 전에 합류를 보낸다
            val pending = TransactionTemplate(txManager).execute {
                jdbc.update("update vehicle set revoked_at = now(), is_primary = false where vehicle_id = ?", b.vehicleId)
                jdbc.queryForObject("select pg_advisory_xact_lock(hashtext(?))", Any::class.java, "vehicle:${b.vehicleId}")
                val inFlight = pool.submit<ResponseEntity<String>> { join(b, created.invite.token) }
                Thread.sleep(700)
                assertFalse(inFlight.isDone, "join must wait for the vehicle lock")
                inFlight
            }!!
            pending.get(20, TimeUnit.SECONDS).assertError(404, ErrorCodes.NO_PAIRED_VEHICLE)
            assertEquals(listOf(a.vehicleId), detailOk(created.groupId, a.vehicleToken).members.map { it.vehicleId })
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `C108 - a join racing a re-pairing does not deadlock - vehicle row before the vehicle advisory lock`() {
        val a = driver("rp-a", "아빠 차")
        val b = driver("rp-b", "엄마 차")
        val created = create(a)
        val pool = Executors.newSingleThreadExecutor()
        try {
            // 재페어링 흉내(VehicleRegistry.register → evictVehicle): 차량 행 FOR UPDATE → 차량 advisory
            val pending = TransactionTemplate(txManager).execute {
                jdbc.execute("set local lock_timeout = '10s'")
                jdbc.queryForMap("select vehicle_id from vehicle where vehicle_id = ? for update", b.vehicleId)
                val inFlight = pool.submit<ResponseEntity<String>> { join(b, created.invite.token) }
                Thread.sleep(700)
                assertFalse(inFlight.isDone, "join waits on the vehicle row")
                // 합류가 advisory 를 쥐고 차량 행을 기다리는 순서였다면 여기서 교착(40P01)
                jdbc.queryForObject("select pg_advisory_xact_lock(hashtext(?))", Any::class.java, "vehicle:${b.vehicleId}")
                inFlight
            }!!
            pending.get(20, TimeUnit.SECONDS).assertOk()
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `C033 - events older than 24 h are purged, an ended group loses waypoint coordinates and route payloads, old invites and retry keys are purged`() {
        val a = driver("rt2-a", "아빠 차")
        val b = driver("rt2-b", "엄마 차")
        val created = create(a)
        join(b, created.invite.token).assertOk()
        val gid = created.groupId
        val wp = assertNotNull(vehicleEvents.handle(b.vehicleId, VehicleEventType.WAYPOINT_ARRIVED, buildJsonObject { put("name", "덕평휴게소"); put("lat", 37.2963); put("lon", 127.412) }))
        assertNotNull(routeSync.onRouteChanged(a.vehicleId, RouteChanged(Place(38.2070, 128.5918, "속초"), listOf(Place(37.2963, 127.4120, "덕평휴게소")))))
        // 25시간 전 이벤트 하나
        jdbc.update("update group_event set occurred_at = now() - interval '25 hours' where group_id = ? and type = 'MEMBER_JOINED'", gid)
        assertTrue(eventRetention.purgeExpired() >= 1)
        assertEquals(0, jdbc.queryForObject("select count(*) from group_event where group_id = ? and type = 'MEMBER_JOINED'", Long::class.javaObjectType, gid))
        assertEquals(1, jdbc.queryForObject("select count(*) from group_event where event_id = ?", Long::class.javaObjectType, wp.eventId), "24시간 안은 남는다")

        call(HttpMethod.POST, ApiPaths.groupEnd(gid), bearer = a.vehicleToken).assertOk(204)
        val wpPayload = json.parseToJsonElement(jdbc.queryForObject("select payload::text from group_event where event_id = ?", String::class.java, wp.eventId)!!).jsonObject
        assertEquals(setOf("name"), wpPayload.keys, "좌표는 지우고 이름(피드)만 남긴다")
        val routePayload = json.parseToJsonElement(jdbc.queryForObject("select payload::text from group_event where group_id = ? and type = 'ROUTE_CHANGED'", String::class.java, gid)!!).jsonObject
        assertFalse("destination" in routePayload || "waypoints" in routePayload, routePayload.toString())

        // 하루 넘게 만료된 초대·만료된 재시도 키 정리
        jdbc.update("update group_invite set expires_at = now() - interval '2 days' where group_id = ?", gid)
        assertTrue(groupRetention.purge() >= 1)
        assertEquals(0, jdbc.queryForObject("select count(*) from group_invite where group_id = ?", Long::class.javaObjectType, gid))
        jdbc.update(
            "insert into idempotency_record (caller_id, request_path, idem_key, status, body, created_at, expires_at) values ('usr_x', '/p', 'old-key-0001', 201, '{}', now() - interval '25 hours', now() - interval '1 hour')",
        )
        assertTrue(idempotencyRetention.purgeExpired() >= 1)
        assertEquals(0, jdbc.queryForObject("select count(*) from idempotency_record where idem_key = 'old-key-0001'", Long::class.javaObjectType))
    }

    @Test
    fun `flyway V3 added the speech column and group indexes`() {
        val columns = jdbc.queryForList("select column_name from information_schema.columns where table_name = 'group_event'", String::class.java)
        assertTrue("speech" in columns, columns.toString())
        val indexes = jdbc.queryForList("select indexname from pg_indexes where schemaname = 'public'", String::class.java)
        listOf("idx_group_member_vehicle_active", "idx_group_invite_group_id", "idx_travel_group_status_created").forEach {
            assertTrue(it in indexes, "missing index $it in $indexes")
        }
    }
}
