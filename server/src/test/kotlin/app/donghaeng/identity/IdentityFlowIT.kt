package app.donghaeng.identity

import app.donghaeng.KPostgresContainer
import app.donghaeng.KRedisContainer
import app.donghaeng.common.api.ApiError
import app.donghaeng.common.config.DhProperties
import app.donghaeng.protocol.DhJson
import app.donghaeng.protocol.rest.ApiPaths
import app.donghaeng.protocol.rest.DeviceApproveRequest
import app.donghaeng.protocol.rest.DeviceCodeRequest
import app.donghaeng.protocol.rest.DeviceCodeResponse
import app.donghaeng.protocol.rest.DeviceTokenRequest
import app.donghaeng.protocol.rest.DeviceTokenResponse
import app.donghaeng.protocol.rest.ErrorCodes
import app.donghaeng.protocol.rest.ErrorEnvelope
import app.donghaeng.protocol.rest.ExchangeRequest
import app.donghaeng.protocol.rest.LoginRequest
import app.donghaeng.protocol.rest.MqttInfo
import app.donghaeng.protocol.rest.MyVehicleDto
import app.donghaeng.protocol.rest.RefreshRequest
import app.donghaeng.protocol.rest.TokenResponse
import app.donghaeng.protocol.rest.VehiclePatchRequest
import app.donghaeng.protocol.rest.VehicleSummary
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.SignedJWT
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.http.HttpMessageConverters
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.http.converter.json.KotlinSerializationJsonHttpMessageConverter
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * identity 통합 테스트 (docs/03 §1 계약): 실제 PostgreSQL 16 + Redis 7 컨테이너로 부팅해 HTTP 로 전체 흐름을 돈다.
 * 본문은 protocol 의 DTO 를 [DhJson] 으로 직렬화해 보내고 문자열로 받아 다시 해석한다 — 와이어 JSON 모양까지 확인하기 위해.
 * Docker 가 없으면 건너뛴다 (disabledWithoutDocker).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class IdentityFlowIT {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = KPostgresContainer("postgres:16")

        @Container
        @ServiceConnection(name = "redis")
        @JvmStatic
        val redis = KRedisContainer(DockerImageName.parse("redis:7")).withExposedPorts(6379)

        private const val CAR_INFO_ID = "CAR-INFO-ID-TEST-0001"
    }

    @Autowired
    lateinit var rest: TestRestTemplate

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Autowired
    lateinit var props: DhProperties

    @Autowired
    lateinit var converters: HttpMessageConverters

    private val json = DhJson.json

    @BeforeEach
    fun usePatchCapableClient() {
        // 기본 HttpURLConnection 은 PATCH 를 못 보낸다 → JDK HttpClient 기반으로 교체 (오류 응답을 예외로 바꾸지 않는 설정은 그대로)
        rest.restTemplate.requestFactory = JdkClientHttpRequestFactory()
    }

    // ---------------------------------------------------------------- helpers

    private fun call(method: HttpMethod, path: String, body: String? = null, bearer: String? = null): ResponseEntity<String> {
        val headers = HttpHeaders().apply {
            accept = listOf(MediaType.APPLICATION_JSON)
            if (body != null) contentType = MediaType.APPLICATION_JSON
            if (bearer != null) setBearerAuth(bearer)
        }
        return rest.exchange(path, method, HttpEntity(body, headers), String::class.java)
    }

    private inline fun <reified T> ResponseEntity<String>.decode(): T = json.decodeFromString<T>(body ?: error("empty body"))

    private fun ResponseEntity<String>.errorCode(): String = decode<ErrorEnvelope>().error.code

    private fun ResponseEntity<String>.assertError(status: Int, code: String) {
        assertEquals(status, statusCode.value(), "status for body=$body")
        assertTrue(headers.contentType?.isCompatibleWith(MediaType.APPLICATION_JSON) == true, "content-type=${headers.contentType}")
        val envelope = decode<ErrorEnvelope>()
        assertEquals(code, envelope.error.code, body)
        assertTrue(!envelope.error.message.isNullOrBlank())
        assertTrue(!envelope.error.traceId.isNullOrBlank(), "traceId missing: $body")
    }

    private fun login(email: String, displayName: String): TokenResponse {
        val res = call(HttpMethod.POST, ApiPaths.AUTH_LOGIN, json.encodeToString(LoginRequest(email = email, displayName = displayName)))
        assertEquals(200, res.statusCode.value(), res.body)
        return res.decode()
    }

    private fun pollToken(deviceCode: String) =
        call(HttpMethod.POST, ApiPaths.DEVICE_TOKEN, json.encodeToString(DeviceTokenRequest(deviceCode)))

    private fun approve(userCode: String, nickname: String, bearer: String?) =
        call(HttpMethod.POST, ApiPaths.DEVICE_APPROVE, json.encodeToString(DeviceApproveRequest(userCode, nickname)), bearer)

    // ---------------------------------------------------------------- tests

    @Test
    fun `full pairing flow - login, code, poll 428 then 429, approve 200, token 200 verified by JWKS, my vehicles, exchange, refresh rotation, re-approve 409`() {
        // 1) dev 로그인 — provider_sub 는 소문자 이메일
        val tokens = login("Driver.One@Example.com", "운전자1")
        assertTrue(tokens.user.userId.startsWith("usr_"), tokens.user.userId)
        assertEquals("운전자1", tokens.user.displayName)
        assertEquals(
            "driver.one@example.com",
            jdbc.queryForObject("select provider_sub from app_user where user_id = ?", String::class.java, tokens.user.userId),
        )
        // 같은 이메일(대소문자 달라도) 다시 로그인 → 같은 사용자
        assertEquals(tokens.user.userId, login("driver.one@example.com", "운전자1-갱신").user.userId)

        // 2) 차량: 디바이스 코드
        val codeRes = call(
            HttpMethod.POST, ApiPaths.DEVICE_CODE,
            json.encodeToString(DeviceCodeRequest(vehicleId = CAR_INFO_ID, model = "GRANDEUR", brand = "HYUNDAI", appVersion = "0.1.0")),
        )
        assertEquals(200, codeRes.statusCode.value(), codeRes.body)
        val code: DeviceCodeResponse = codeRes.decode()
        assertTrue(code.deviceCode.startsWith("dc_") && code.deviceCode.length == 35, code.deviceCode)
        assertEquals(6, code.userCode.length)
        assertTrue(code.userCode.all { it in UserCodes.ALPHABET }, code.userCode)
        assertEquals(ApiPaths.pairUrl(props.publicBaseUrl, code.userCode), code.verifyUri)
        assertEquals(600, code.expiresIn)
        assertEquals(3, code.interval)
        val storedHash = jdbc.queryForObject("select car_info_id_hash from device_authorization where device_code = ?", String::class.java, code.deviceCode)!!
        assertEquals(64, storedHash.length)
        assertFalse(storedHash.contains(CAR_INFO_ID))

        // 3) 폴링 → 428, 바로 다시 → 429
        pollToken(code.deviceCode).assertError(428, ErrorCodes.AUTHORIZATION_PENDING)
        pollToken(code.deviceCode).assertError(429, ErrorCodes.SLOW_DOWN)

        // 4) 승인: 토큰 없이 401, 사용자 토큰으로 200 VehicleSummary
        approve(code.userCode, "아빠 차", bearer = null).assertError(401, ErrorCodes.UNAUTHORIZED)
        val approved = approve(code.userCode.lowercase().replaceRange(3, 3, "-"), "아빠 차", bearer = tokens.accessToken)
        assertEquals(200, approved.statusCode.value(), approved.body)
        val summary: VehicleSummary = approved.decode()
        assertTrue(summary.vehicleId.startsWith("veh_"), summary.vehicleId)
        assertEquals("아빠 차", summary.nickname)
        assertEquals(tokens.user.userId, summary.ownerId)

        // 5) interval 뒤 폴링 → 200 차량 토큰 + MQTT 정보
        Thread.sleep(3_100)
        val issued = pollToken(code.deviceCode)
        assertEquals(200, issued.statusCode.value(), issued.body)
        val device: DeviceTokenResponse = issued.decode()
        assertEquals(summary, device.vehicle)
        assertEquals(MqttInfo(props.mqtt.publicUri, summary.vehicleId, summary.vehicleId), device.mqtt)
        assertEquals("CONSUMED", jdbc.queryForObject("select status from device_authorization where device_code = ?", String::class.java, code.deviceCode))

        // 6) 차량 토큰을 /.well-known/jwks.json 의 키로 검증 (EMQX 가 하는 일)
        val jwksRes = rest.getForEntity(ApiPaths.JWKS, String::class.java)
        assertEquals(200, jwksRes.statusCode.value())
        assertTrue(jwksRes.headers.contentType!!.isCompatibleWith(MediaType.APPLICATION_JSON))
        val jwks = JWKSet.parse(jwksRes.body!!)
        assertEquals(1, jwks.keys.size)
        val signed = SignedJWT.parse(device.vehicleAccessToken)
        val key = jwks.getKeyByKeyId(signed.header.keyID) as? RSAKey
        assertNotNull(key, "kid ${signed.header.keyID} not in JWKS")
        assertTrue(signed.verify(RSASSAVerifier(key)), "signature must verify with the published key")
        val claims = signed.jwtClaimsSet
        assertEquals("donghaeng", claims.issuer)
        assertEquals(summary.vehicleId, claims.subject)
        assertEquals("vehicle", claims.getStringClaim("typ"))
        assertEquals(tokens.user.userId, claims.getStringClaim("owner"))
        assertEquals(24 * 3600L, (claims.expirationTime.time - claims.issueTime.time) / 1000)

        // 7) 내 차 목록 — 첫 차량이라 대표
        val mine = call(HttpMethod.GET, ApiPaths.ME_VEHICLES, bearer = tokens.accessToken)
        assertEquals(200, mine.statusCode.value(), mine.body)
        val vehicles: List<MyVehicleDto> = mine.decode()
        assertEquals(listOf(MyVehicleDto(summary.vehicleId, "아빠 차", "HYUNDAI", "GRANDEUR", isPrimary = true)), vehicles)
        assertTrue(mine.body!!.contains("\"isPrimary\":true"), mine.body)
        // 차량 토큰으로는 사용자 API 불가
        call(HttpMethod.GET, ApiPaths.ME_VEHICLES, bearer = device.vehicleAccessToken).assertError(403, ErrorCodes.FORBIDDEN)

        // 8) exchange(dev, 사용자 JWT) → 같은 사용자 / 깨진 토큰 401 / 공식 앱 제공자 501
        val exchanged = call(HttpMethod.POST, ApiPaths.AUTH_EXCHANGE, json.encodeToString(ExchangeRequest("dev", tokens.accessToken)))
        assertEquals(200, exchanged.statusCode.value(), exchanged.body)
        assertEquals(tokens.user.userId, exchanged.decode<TokenResponse>().user.userId)
        call(HttpMethod.POST, ApiPaths.AUTH_EXCHANGE, json.encodeToString(ExchangeRequest("dev", "not.a.jwt"))).assertError(401, ErrorCodes.INVALID_TOKEN)
        call(HttpMethod.POST, ApiPaths.AUTH_EXCHANGE, json.encodeToString(ExchangeRequest("bluelink", "oem-token"))).assertError(501, ErrorCodes.PROVIDER_NOT_SUPPORTED)
        call(HttpMethod.POST, ApiPaths.AUTH_EXCHANGE, json.encodeToString(ExchangeRequest("genesis_connected", "oem-token"))).assertError(501, ErrorCodes.PROVIDER_NOT_SUPPORTED)

        // 9) refresh 회전: 새 쌍, 이전 refresh 재사용 401, 새 refresh 는 또 회전 가능
        val rotatedRes = call(HttpMethod.POST, ApiPaths.AUTH_REFRESH, json.encodeToString(RefreshRequest(tokens.refreshToken)))
        assertEquals(200, rotatedRes.statusCode.value(), rotatedRes.body)
        val rotated: TokenResponse = rotatedRes.decode()
        assertEquals(tokens.user.userId, rotated.user.userId)
        assertNotEquals(tokens.refreshToken, rotated.refreshToken)
        call(HttpMethod.POST, ApiPaths.AUTH_REFRESH, json.encodeToString(RefreshRequest(tokens.refreshToken))).assertError(401, ErrorCodes.INVALID_TOKEN)
        val rotatedAgain = call(HttpMethod.POST, ApiPaths.AUTH_REFRESH, json.encodeToString(RefreshRequest(rotated.refreshToken)))
        assertEquals(200, rotatedAgain.statusCode.value(), rotatedAgain.body)
        // 차량 refresh: accessToken 은 차량 토큰, user 는 소유자
        val vehicleRefreshRes = call(HttpMethod.POST, ApiPaths.AUTH_REFRESH, json.encodeToString(RefreshRequest(device.refreshToken)))
        assertEquals(200, vehicleRefreshRes.statusCode.value(), vehicleRefreshRes.body)
        val vehicleRefresh: TokenResponse = vehicleRefreshRes.decode()
        assertEquals(summary.vehicleId, SignedJWT.parse(vehicleRefresh.accessToken).jwtClaimsSet.subject)
        assertEquals(tokens.user.userId, vehicleRefresh.user.userId)

        // 10) 같은 코드 다시 승인 → 409
        approve(code.userCode, "다른 이름", bearer = rotatedAgain.decode<TokenResponse>().accessToken).assertError(409, ErrorCodes.ALREADY_APPROVED)

        // 11) PATCH 닉네임 → DELETE 204 → 목록 비고 차량 refresh 토큰 폐기
        val patched = call(HttpMethod.PATCH, ApiPaths.meVehicle(summary.vehicleId), json.encodeToString(VehiclePatchRequest(nickname = "엄마 차")), tokens.accessToken)
        assertEquals(200, patched.statusCode.value(), patched.body)
        assertEquals(MyVehicleDto(summary.vehicleId, "엄마 차", "HYUNDAI", "GRANDEUR", isPrimary = true), patched.decode())
        call(HttpMethod.PATCH, ApiPaths.meVehicle("veh_NOPE"), json.encodeToString(VehiclePatchRequest(nickname = "x")), tokens.accessToken).assertError(404, ErrorCodes.NOT_FOUND)

        val deleted = call(HttpMethod.DELETE, ApiPaths.meVehicle(summary.vehicleId), bearer = tokens.accessToken)
        assertEquals(204, deleted.statusCode.value(), deleted.body)
        assertEquals(emptyList<MyVehicleDto>(), call(HttpMethod.GET, ApiPaths.ME_VEHICLES, bearer = tokens.accessToken).decode<List<MyVehicleDto>>())
        call(HttpMethod.POST, ApiPaths.AUTH_REFRESH, json.encodeToString(RefreshRequest(vehicleRefresh.refreshToken))).assertError(401, ErrorCodes.INVALID_TOKEN)
        call(HttpMethod.DELETE, ApiPaths.meVehicle(summary.vehicleId), bearer = tokens.accessToken).assertError(404, ErrorCodes.NOT_FOUND)
    }

    @Test
    fun `re-pairing the same car transfers ownership and primary follows the first-vehicle rule`() {
        val a = login("owner-a@example.com", "소유자A")
        val b = login("owner-b@example.com", "소유자B")
        val carId = "CAR-INFO-ID-TRANSFER-0002"

        fun pairFor(user: TokenResponse, nickname: String): VehicleSummary {
            val code: DeviceCodeResponse = call(HttpMethod.POST, ApiPaths.DEVICE_CODE, json.encodeToString(DeviceCodeRequest(vehicleId = carId, brand = "KIA", model = "EV9"))).decode()
            val res = approve(code.userCode, nickname, user.accessToken)
            assertEquals(200, res.statusCode.value(), res.body)
            return res.decode()
        }

        val first = pairFor(a, "A 의 차")
        val second = pairFor(b, "B 의 차")
        assertEquals(first.vehicleId, second.vehicleId, "same car_info_id_hash → same vehicle row")
        assertEquals(b.user.userId, second.ownerId)

        assertEquals(emptyList<MyVehicleDto>(), call(HttpMethod.GET, ApiPaths.ME_VEHICLES, bearer = a.accessToken).decode<List<MyVehicleDto>>())
        val bList: List<MyVehicleDto> = call(HttpMethod.GET, ApiPaths.ME_VEHICLES, bearer = b.accessToken).decode()
        assertEquals(listOf(MyVehicleDto(first.vehicleId, "B 의 차", "KIA", "EV9", isPrimary = true)), bList)

        // B 의 두 번째 차는 대표가 아니고, isPrimary=true 로 바꾸면 이전 대표는 풀린다 (사용자당 하나)
        val codeB2: DeviceCodeResponse = call(HttpMethod.POST, ApiPaths.DEVICE_CODE, json.encodeToString(DeviceCodeRequest(vehicleId = "CAR-INFO-ID-SECOND-0003"))).decode()
        val b2: VehicleSummary = approve(codeB2.userCode, "B 의 둘째 차", b.accessToken).decode()
        val before: List<MyVehicleDto> = call(HttpMethod.GET, ApiPaths.ME_VEHICLES, bearer = b.accessToken).decode()
        assertEquals(listOf(true, false), before.map { it.isPrimary })
        val patched = call(HttpMethod.PATCH, ApiPaths.meVehicle(b2.vehicleId), json.encodeToString(VehiclePatchRequest(isPrimary = true)), b.accessToken)
        assertEquals(200, patched.statusCode.value(), patched.body)
        val after: List<MyVehicleDto> = call(HttpMethod.GET, ApiPaths.ME_VEHICLES, bearer = b.accessToken).decode()
        assertEquals(b2.vehicleId, after.first { it.isPrimary }.vehicleId)
        assertEquals(1, after.count { it.isPrimary })
    }

    @Test
    fun `expired or unknown device code - 410 and 404 on poll and approve`() {
        jdbc.update(
            """
            insert into device_authorization (device_code, user_code, car_info_id_hash, status, expires_at)
            values (?, ?, ?, 'PENDING', now() - interval '1 minute')
            """.trimIndent(),
            "dc_expired_test_0000000000000000", "ZZZZZ2", "0".repeat(64),
        )
        pollToken("dc_expired_test_0000000000000000").assertError(410, ErrorCodes.CODE_EXPIRED)
        pollToken("dc_does_not_exist").assertError(404, ErrorCodes.CODE_NOT_FOUND)

        val user = login("expired-tester@example.com", "만료테스터")
        approve("ZZZZZ2", "만료 차", user.accessToken).assertError(410, ErrorCodes.CODE_EXPIRED)
        approve("ZZZZZ3", "없는 차", user.accessToken).assertError(404, ErrorCodes.CODE_NOT_FOUND)
        approve("BAD-0", "형식 오류", user.accessToken).assertError(404, ErrorCodes.CODE_NOT_FOUND)
        approve("", "빈 코드", user.accessToken).assertError(400, ErrorCodes.VALIDATION_FAILED)
    }

    @Test
    fun `unauthenticated or invalid bearer on my vehicles is a JSON 401`() {
        val none = call(HttpMethod.GET, ApiPaths.ME_VEHICLES)
        none.assertError(401, ErrorCodes.UNAUTHORIZED)
        assertTrue(none.headers.getFirst(HttpHeaders.WWW_AUTHENTICATE)!!.contains("Bearer"))
        call(HttpMethod.GET, ApiPaths.ME_VEHICLES, bearer = "garbage.token.value").assertError(401, ErrorCodes.INVALID_TOKEN)
    }

    @Test
    fun `validation - login requires dev provider and a valid email, malformed JSON is 400`() {
        call(HttpMethod.POST, ApiPaths.AUTH_LOGIN, json.encodeToString(LoginRequest(provider = "bluelink", email = "a@b.c", displayName = "x"))).assertError(400, ErrorCodes.VALIDATION_FAILED)
        call(HttpMethod.POST, ApiPaths.AUTH_LOGIN, json.encodeToString(LoginRequest(email = "not-an-email", displayName = "x"))).assertError(400, ErrorCodes.VALIDATION_FAILED)
        call(HttpMethod.POST, ApiPaths.AUTH_LOGIN, """{"provider":"dev","email":"a@b.c"}""").assertError(400, ErrorCodes.VALIDATION_FAILED) // displayName 누락
        call(HttpMethod.POST, ApiPaths.AUTH_LOGIN, "{not json").assertError(400, ErrorCodes.VALIDATION_FAILED)
        call(HttpMethod.POST, ApiPaths.DEVICE_CODE, json.encodeToString(DeviceCodeRequest(vehicleId = "  "))).assertError(400, ErrorCodes.VALIDATION_FAILED)
    }

    @Test
    fun `kotlinx converter serves protocol DTOs ahead of Jackson, which keeps ApiError`() {
        val list = converters.converters
        val kotlinIdx = list.indexOfFirst { it is KotlinSerializationJsonHttpMessageConverter }
        val jacksonIdx = list.indexOfFirst { it is MappingJackson2HttpMessageConverter }
        assertTrue(kotlinIdx >= 0 && jacksonIdx > kotlinIdx, "kotlin=$kotlinIdx jackson=$jacksonIdx")
        val kotlin = list[kotlinIdx]
        assertTrue(kotlin.canWrite(TokenResponse::class.java, MediaType.APPLICATION_JSON))
        assertTrue(kotlin.canRead(LoginRequest::class.java, MediaType.APPLICATION_JSON))
        assertFalse(kotlin.canWrite(ApiError::class.java, MediaType.APPLICATION_JSON), "ApiError is not @Serializable → Jackson")
    }

    @Test
    fun `flyway V2 added the identity indexes and the provider comment`() {
        val indexes = jdbc.queryForList("select indexname from pg_indexes where schemaname = 'public'", String::class.java)
        listOf("idx_vehicle_owner_id", "idx_device_authorization_expires_at", "idx_refresh_token_subject", "uq_vehicle_owner_primary").forEach {
            assertTrue(it in indexes, "missing index $it in $indexes")
        }
        val comment = jdbc.queryForObject(
            "select col_description('app_user'::regclass, (select attnum from pg_attribute where attrelid = 'app_user'::regclass and attname = 'provider'))",
            String::class.java,
        )
        assertEquals("dev | bluelink | myhyundai | kia_connect | genesis_connected", comment)
    }
}
