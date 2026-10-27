package app.donghaeng.group

import app.donghaeng.KPostgresContainer
import app.donghaeng.KRedisContainer
import app.donghaeng.common.config.DhProperties
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * group 통합 테스트 (docs/03 §2 계약): 실제 PostgreSQL 16 + Redis 7 로 부팅해 HTTP 로 생성·초대·합류·조회·나가기·종료·위임·메시지를 돈다.
 * 차량은 identity 의 페어링 흐름(코드 → 승인 → 토큰)으로 만든다. Docker 가 없으면 건너뛴다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
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

    private val json = DhJson.json

    @BeforeEach
    fun useJdkHttpClient() {
        // 기본 HttpURLConnection 은 401(WWW-Authenticate) 응답에서 "cannot retry due to server authentication" 을 던지고
        // 본문 없는 POST 에 form Content-Type 을 붙인다 → JDK HttpClient 기반으로 교체 (IdentityFlowIT 와 같음)
        rest.restTemplate.requestFactory = JdkClientHttpRequestFactory()
    }

    // ---------------------------------------------------------------- helpers

    /** 사용자 + 그 사용자 소유 차량 하나 (차량 토큰 포함) */
    private class Driver(val user: TokenResponse, val vehicle: DeviceTokenResponse) {
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

    private fun pair(user: TokenResponse, nickname: String, brand: String? = "HYUNDAI", model: String? = "GRANDEUR"): DeviceTokenResponse {
        val n = seq.incrementAndGet()
        val code: DeviceCodeResponse = call(
            HttpMethod.POST, ApiPaths.DEVICE_CODE,
            json.encodeToString(DeviceCodeRequest(vehicleId = "CAR-INFO-GROUP-IT-$n", model = model, brand = brand, appVersion = "0.1.0")),
        ).assertOk().decode()
        call(HttpMethod.POST, ApiPaths.DEVICE_APPROVE, json.encodeToString(DeviceApproveRequest(code.userCode, nickname)), user.accessToken).assertOk()
        // 승인 뒤 첫 폴링은 바로 200 (폴링 제한 키가 아직 없다)
        return call(HttpMethod.POST, ApiPaths.DEVICE_TOKEN, json.encodeToString(DeviceTokenRequest(code.deviceCode))).assertOk().decode()
    }

    private fun driver(label: String, nickname: String, brand: String? = "HYUNDAI", model: String? = "GRANDEUR"): Driver {
        val user = login(label)
        return Driver(user, pair(user, nickname, brand, model))
    }

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
        assertNull(byUser.snapshot)
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
    fun `flyway V3 added the speech column and group indexes`() {
        val columns = jdbc.queryForList("select column_name from information_schema.columns where table_name = 'group_event'", String::class.java)
        assertTrue("speech" in columns, columns.toString())
        val indexes = jdbc.queryForList("select indexname from pg_indexes where schemaname = 'public'", String::class.java)
        listOf("idx_group_member_vehicle_active", "idx_group_invite_group_id", "idx_travel_group_status_created").forEach {
            assertTrue(it in indexes, "missing index $it in $indexes")
        }
    }
}
