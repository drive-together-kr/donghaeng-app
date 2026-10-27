package app.donghaeng.companion.sdk.internal

import app.donghaeng.companion.sdk.AccountProvider
import app.donghaeng.companion.sdk.DongHaeng
import app.donghaeng.companion.sdk.DongHaengConfig
import app.donghaeng.companion.sdk.DongHaengException
import app.donghaeng.companion.sdk.MemberRole
import app.donghaeng.companion.sdk.TokenProvider
import app.donghaeng.protocol.MemberStatus
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * REST 전송(HttpDongHaengApi)을 MockWebServer 로 검증한다 — 교환 1회·캐시, 401 재교환, DTO → 공개 모델 매핑, 오류 봉투, 폴링.
 * 토큰 값은 테스트용 가짜 JWT(서명 없음)다.
 */
class HttpDongHaengApiTest {

    private lateinit var server: MockWebServer
    private lateinit var baseUrl: String
    private val hostTokenCalls = AtomicInteger()
    private val hostToken = TokenProvider { hostTokenCalls.incrementAndGet(); "host-token-from-login" }

    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        baseUrl = server.url("/").toString().trimEnd('/')
        DongHaeng.reset()
    }

    @After
    fun tearDown() {
        DongHaeng.reset()
        runCatching { server.shutdown() } // NETWORK 테스트는 이미 내려 둔다
    }

    private fun api(provider: AccountProvider = AccountProvider.DEV, token: TokenProvider = hostToken) = HttpDongHaengApi(
        config = DongHaengConfig(publicBaseUrl = "$baseUrl/", provider = provider, appVersion = "0.1.0-test"),
        tokenProvider = token,
        client = client,
        pollIntervalMs = 10, // 가상 시간이 아니라 실제 지연이 걸려도 테스트가 느려지지 않게
    )

    // ---------- 도우미 ----------

    /** 서명 없는 가짜 JWT — 모듈은 exp 만 읽는다 */
    private fun jwt(expEpochS: Long = System.currentTimeMillis() / 1000 + 3600, sub: String = "usr_test"): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        val header = enc.encodeToString("""{"alg":"RS256","kid":"k1"}""".toByteArray())
        val payload = enc.encodeToString("""{"sub":"$sub","typ":"user","iss":"donghaeng","exp":$expEpochS}""".toByteArray())
        return "$header.$payload.sig"
    }

    private fun tokenJson(accessToken: String) =
        """{"accessToken":"$accessToken","refreshToken":"rt_opaque","user":{"userId":"usr_test","displayName":"동승자"}}"""

    private fun json(code: Int, body: String) = MockResponse().setResponseCode(code)
        .setHeader("Content-Type", "application/json").setBody(body)

    private fun error(status: Int, code: String, message: String) =
        json(status, """{"error":{"code":"$code","message":"$message","traceId":"t-1"}}""")

    private fun RecordedRequest.bodyJson() = Json.parseToJsonElement(body.readUtf8()).jsonObject

    private val vehiclesJson = """[
        {"vehicleId":"veh_01","nickname":"내 그랜저","brand":"HYUNDAI","model":"GRANDEUR","isPrimary":true},
        {"vehicleId":"veh_02","nickname":"시승차"}
    ]"""

    // ---------- 인증 ----------

    @Test
    fun `첫 호출에 exchange 한 번, 이후에는 캐시한 동행 JWT 만 쓴다`() = runTest {
        val dhJwt = jwt()
        server.enqueue(json(200, tokenJson(dhJwt)))
        server.enqueue(json(200, vehiclesJson))
        server.enqueue(json(200, vehiclesJson))

        val api = api()
        api.listMyVehicles()
        api.listMyVehicles()

        assertEquals(3, server.requestCount)
        val exchange = server.takeRequest()
        assertEquals("POST", exchange.method)
        assertEquals("/v1/auth/exchange", exchange.path)
        assertNull("호스트 토큰은 Bearer 로 가지 않는다", exchange.getHeader("Authorization"))
        val body = exchange.bodyJson()
        assertEquals("dev", body["provider"]?.jsonPrimitive?.content)
        assertEquals("host-token-from-login", body["accessToken"]?.jsonPrimitive?.content)
        assertTrue(exchange.getHeader("User-Agent").orEmpty().startsWith("DongHaengCompanion/0.1.0-test"))

        repeat(2) {
            val list = server.takeRequest()
            assertEquals("GET", list.method)
            assertEquals("/v1/me/vehicles", list.path)
            assertEquals("Bearer $dhJwt", list.getHeader("Authorization"))
        }
        assertEquals("호스트 토큰은 교환 때 한 번만 묻는다", 1, hostTokenCalls.get())
    }

    @Test
    fun `제공자 wire 이름 - BLUELINK 는 bluelink`() = runTest {
        server.enqueue(json(200, tokenJson(jwt())))
        server.enqueue(json(200, "[]"))
        api(provider = AccountProvider.BLUELINK).listMyVehicles()
        assertEquals("bluelink", server.takeRequest().bodyJson()["provider"]?.jsonPrimitive?.content)
    }

    @Test
    fun `401 이면 한 번 재교환하고 재시도한다`() = runTest {
        val first = jwt(sub = "usr_a")
        val second = jwt(sub = "usr_b")
        server.enqueue(json(200, tokenJson(first)))
        server.enqueue(error(401, "UNAUTHORIZED", "토큰이 만료됐어요"))
        server.enqueue(json(200, tokenJson(second)))
        server.enqueue(json(200, vehiclesJson))

        val vehicles = api().listMyVehicles()

        assertEquals(2, vehicles.size)
        assertEquals(4, server.requestCount)
        assertEquals("/v1/auth/exchange", server.takeRequest().path)
        assertEquals("Bearer $first", server.takeRequest().getHeader("Authorization"))
        assertEquals("/v1/auth/exchange", server.takeRequest().path)
        assertEquals("Bearer $second", server.takeRequest().getHeader("Authorization"))
        assertEquals(2, hostTokenCalls.get())
    }

    @Test
    fun `재교환 뒤에도 401 이면 Unauthorized`() = runTest {
        server.enqueue(json(200, tokenJson(jwt())))
        server.enqueue(error(401, "UNAUTHORIZED", "거부"))
        server.enqueue(json(200, tokenJson(jwt())))
        server.enqueue(error(401, "UNAUTHORIZED", "거부"))

        val error = runCatching { api().listMyVehicles() }.exceptionOrNull()
        assertTrue("$error", error is DongHaengException.Unauthorized)
        assertEquals("교환 2회 + 호출 2회, 세 번째 교환은 없다", 4, server.requestCount)
    }

    @Test
    fun `exchange 자체가 401 이면 Unauthorized`() = runTest {
        server.enqueue(error(401, "INVALID_TOKEN", "호스트 토큰이 유효하지 않아요"))
        val error = runCatching { api().listMyVehicles() }.exceptionOrNull()
        assertTrue("$error", error is DongHaengException.Unauthorized)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `호스트 토큰이 비어 있으면 요청 없이 Unauthorized`() = runTest {
        val error = runCatching { api(token = TokenProvider { "" }).listMyVehicles() }.exceptionOrNull()
        assertTrue("$error", error is DongHaengException.Unauthorized)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `캐시한 JWT 의 exp 가 지났으면 호출 전에 다시 교환한다`() = runTest {
        server.enqueue(json(200, tokenJson(jwt(expEpochS = System.currentTimeMillis() / 1000 - 10))))
        server.enqueue(json(200, "[]"))
        server.enqueue(json(200, tokenJson(jwt())))
        server.enqueue(json(200, "[]"))

        val api = api()
        api.listMyVehicles()
        api.listMyVehicles()

        assertEquals(4, server.requestCount)
        assertEquals("/v1/auth/exchange", server.takeRequest().path)
        assertEquals("/v1/me/vehicles", server.takeRequest().path)
        assertEquals("만료된 캐시 → 교환", "/v1/auth/exchange", server.takeRequest().path)
        assertEquals("/v1/me/vehicles", server.takeRequest().path)
    }

    @Test
    fun `exchange 가 501 PROVIDER_NOT_SUPPORTED 면 Api 로 서버 문구를 전달`() = runTest {
        server.enqueue(error(501, "PROVIDER_NOT_SUPPORTED", "아직 지원하지 않는 제공자예요"))
        val error = runCatching { api(provider = AccountProvider.KIA_CONNECT).listMyVehicles() }.exceptionOrNull()
        assertTrue("$error", error is DongHaengException.Api)
        assertEquals("PROVIDER_NOT_SUPPORTED", (error as DongHaengException.Api).code)
        assertEquals("아직 지원하지 않는 제공자예요", error.message)
    }

    // ---------- 내 차 ----------

    @Test
    fun `listMyVehicles 는 MyVehicleDto 를 MyVehicle 로 - brand·model 없으면 빈 문자열`() = runTest {
        server.enqueue(json(200, tokenJson(jwt())))
        server.enqueue(json(200, vehiclesJson))

        val vehicles = api().listMyVehicles()

        assertEquals(2, vehicles.size)
        val first = vehicles[0]
        assertEquals("veh_01", first.vehicleId)
        assertEquals("내 그랜저", first.nickname)
        assertEquals("HYUNDAI", first.brand)
        assertEquals("GRANDEUR", first.model)
        assertTrue(first.isPrimary)
        val second = vehicles[1]
        assertEquals("", second.brand)
        assertEquals("", second.model)
        assertFalse(second.isPrimary)
    }

    @Test
    fun `listMyVehicles - 빈 계정은 빈 목록`() = runTest {
        server.enqueue(json(200, tokenJson(jwt())))
        server.enqueue(json(200, "[]"))
        assertTrue(api().listMyVehicles().isEmpty())
    }

    // ---------- 차량 등록 승인 ----------

    @Test
    fun `approvePairing 200 VehicleSummary → PairResult, 요청 본문은 userCode·nickname`() = runTest {
        server.enqueue(json(200, tokenJson(jwt())))
        server.enqueue(json(200, """{"vehicleId":"veh_new","nickname":"시승차","ownerId":"usr_test"}"""))

        val result = api().approvePairing("KQ74MZ", "시승차")

        assertEquals("veh_new", result.vehicleId)
        assertEquals("시승차", result.nickname)
        server.takeRequest()
        val approve = server.takeRequest()
        assertEquals("POST", approve.method)
        assertEquals("/v1/device/approve", approve.path)
        val body = approve.bodyJson()
        assertEquals("KQ74MZ", body["userCode"]?.jsonPrimitive?.content)
        assertEquals("시승차", body["nickname"]?.jsonPrimitive?.content)
    }

    @Test
    fun `approvePairing 410 → Api(CODE_EXPIRED) 서버 문구 그대로`() = runTest {
        server.enqueue(json(200, tokenJson(jwt())))
        server.enqueue(error(410, "CODE_EXPIRED", "코드가 만료됐어요. 차 화면에서 새 QR을 띄워 주세요"))

        val error = runCatching { api().approvePairing("KQ74MZ", "시승차") }.exceptionOrNull()

        assertTrue("$error", error is DongHaengException.Api)
        assertEquals("CODE_EXPIRED", (error as DongHaengException.Api).code)
        assertEquals("코드가 만료됐어요. 차 화면에서 새 QR을 띄워 주세요", error.message)
    }

    @Test
    fun `approvePairing 404·409 도 서버 코드 그대로`() = runTest {
        server.enqueue(json(200, tokenJson(jwt())))
        server.enqueue(error(404, "CODE_NOT_FOUND", "없는 코드"))
        server.enqueue(error(409, "ALREADY_APPROVED", "이미 승인된 코드"))
        val api = api()
        val notFound = runCatching { api.approvePairing("AAAAAA", "x") }.exceptionOrNull() as DongHaengException.Api
        assertEquals("CODE_NOT_FOUND", notFound.code)
        val already = runCatching { api.approvePairing("BBBBBB", "x") }.exceptionOrNull() as DongHaengException.Api
        assertEquals("ALREADY_APPROVED", already.code)
    }

    // ---------- 그룹 ----------

    @Test
    fun `joinGroup 은 GroupJoinResponse 를 GroupJoinResult 로`() = runTest {
        server.enqueue(json(200, tokenJson(jwt())))
        server.enqueue(
            json(
                200,
                """{"groupId":"grp_1","name":"추석 귀성길","members":[
                    {"vehicleId":"veh_lead","nickname":"아빠 차","color":"#2E7DFF","role":"LEADER","joinedAt":1},
                    {"vehicleId":"veh_01","nickname":"내 그랜저","color":"#FF7A2E","role":"MEMBER","joinedAt":2,"brand":"HYUNDAI"}
                ]}""",
            ),
        )

        val result = api().joinGroup("inv_abc", null)

        assertEquals("grp_1", result.groupId)
        assertEquals("추석 귀성길", result.name)
        assertEquals(listOf(MemberRole.LEADER, MemberRole.MEMBER), result.members.map { it.role })
        server.takeRequest()
        val join = server.takeRequest()
        assertEquals("/v1/groups/join", join.path)
        val body = join.bodyJson()
        assertEquals("inv_abc", body["inviteToken"]?.jsonPrimitive?.content)
        assertNull("vehicleId 생략 시 보내지 않는다", body["vehicleId"])
    }

    @Test
    fun `그룹 경로의 404 NOT_FOUND 는 서버 문구 그대로 전달된다 (멤버가 아닌 그룹은 서버가 404 로 숨긴다)`() = runTest {
        server.enqueue(json(200, tokenJson(jwt())))
        server.enqueue(error(404, "NOT_FOUND", "그룹을 찾을 수 없어요."))

        val error = runCatching { api().joinGroup("inv_abc", null) }.exceptionOrNull()

        assertTrue("$error", error is DongHaengException.Api)
        assertEquals("NOT_FOUND", (error as DongHaengException.Api).code)
        assertEquals("그룹을 찾을 수 없어요.", error.message)
    }

    @Test
    fun `sendPassengerMessage 는 text 를 보내고 204 를 성공으로 본다`() = runTest {
        server.enqueue(json(200, tokenJson(jwt())))
        server.enqueue(MockResponse().setResponseCode(204))

        api().sendPassengerMessage("grp_1", "앞에 휴게소 들르자")

        server.takeRequest()
        val send = server.takeRequest()
        assertEquals("/v1/groups/grp_1/messages", send.path)
        assertEquals("앞에 휴게소 들르자", send.bodyJson()["text"]?.jsonPrimitive?.content)
    }

    @Test
    fun `observeGroup 은 GET groups-id 를 폴링하고 멤버를 매핑한다`() = runTest {
        server.enqueue(json(200, tokenJson(jwt())))
        // 1) 스냅샷 없음 → Member 목록을 오프라인 상태로
        server.enqueue(
            json(
                200,
                """{"groupId":"grp_1","name":"추석 귀성길","leaderVehicleId":"veh_lead","status":"ACTIVE",
                    "members":[{"vehicleId":"veh_lead","nickname":"아빠 차","color":"#2E7DFF","role":"LEADER","joinedAt":1}]}""",
            ),
        )
        // 2) 스냅샷 있음 → snapshot.members 그대로, recentEvents(최신순) → events(오래된 것부터)
        server.enqueue(
            json(
                200,
                """{"groupId":"grp_1","name":"추석 귀성길","leaderVehicleId":"veh_lead","status":"ACTIVE",
                    "members":[{"vehicleId":"veh_lead","nickname":"아빠 차","color":"#2E7DFF","role":"LEADER","joinedAt":1}],
                    "snapshot":{"groupId":"grp_1","leaderVehicleId":"veh_lead","members":[
                      {"vehicleId":"veh_lead","nickname":"아빠 차","color":"#2E7DFF","online":true,"lat":36.8,"lon":127.1,
                       "heading":180.0,"speedKph":95.0,"ageMs":500,"eta":{"destTs":1,"remainM":1000,"remainS":60},
                       "energy":{"socPct":62},"distanceToLeaderM":0,"status":"DRIVING"}]},
                    "recentEvents":[
                      {"eventId":"evt_2","type":"LOW_ENERGY","sourceVehicleId":"veh_lead","severity":"WARN","speech":null,"payload":{}},
                      {"eventId":"evt_1","type":"MEMBER_JOINED","sourceVehicleId":"veh_lead","severity":"INFO","speech":"합류","payload":{}}
                    ]}""",
            ),
        )

        val snapshots = api().observeGroup("grp_1").take(2).toList()

        assertEquals(3, server.requestCount)
        server.takeRequest()
        repeat(2) {
            val poll = server.takeRequest()
            assertEquals("GET", poll.method)
            assertEquals("/v1/groups/grp_1", poll.path)
            assertNotNull(poll.getHeader("Authorization"))
        }
        val first = snapshots[0]
        assertEquals("grp_1", first.groupId)
        assertEquals(1, first.members.size)
        assertFalse(first.members[0].online)
        assertEquals(MemberStatus.OFFLINE, first.members[0].status)
        assertNull(first.members[0].lat)
        assertTrue(first.events.isEmpty())

        val second = snapshots[1]
        assertTrue(second.members[0].online)
        assertEquals(MemberStatus.DRIVING, second.members[0].status)
        assertEquals(62, second.members[0].energy?.socPct)
        assertEquals(listOf("evt_1", "evt_2"), second.events.map { it.eventId })
    }

    @Test
    fun `observeGroup 은 서버 오류를 흐름 예외로 전달한다`() = runTest {
        server.enqueue(json(200, tokenJson(jwt())))
        server.enqueue(error(403, "FORBIDDEN", "참여 중인 그룹이 아니에요"))
        val error = runCatching { api().observeGroup("grp_x").take(1).toList() }.exceptionOrNull()
        assertTrue("$error", error is DongHaengException.Api)
        assertEquals("FORBIDDEN", (error as DongHaengException.Api).code)
    }

    // ---------- 오류 매핑 ----------

    @Test
    fun `서버에 연결할 수 없으면 Api(NETWORK)`() = runTest {
        val api = api() // baseUrl 을 잡아 둔 뒤 서버를 내린다
        server.shutdown()
        val error = runCatching { api.listMyVehicles() }.exceptionOrNull()
        assertTrue("$error", error is DongHaengException.Api)
        assertEquals("NETWORK", (error as DongHaengException.Api).code)
        assertEquals("서버에 연결할 수 없어요", error.message)
    }

    @Test
    fun `오류 봉투가 없는 5xx 는 HTTP_status 코드`() = runTest {
        server.enqueue(json(200, tokenJson(jwt())))
        server.enqueue(MockResponse().setResponseCode(502).setBody("<html>bad gateway</html>"))
        val error = runCatching { api().listMyVehicles() }.exceptionOrNull() as DongHaengException.Api
        assertEquals("HTTP_502", error.code)
    }

    @Test
    fun `본문이 깨진 2xx 는 Api(BAD_RESPONSE)`() = runTest {
        server.enqueue(json(200, tokenJson(jwt())))
        server.enqueue(json(200, """{"not":"a list"}"""))
        val error = runCatching { api().listMyVehicles() }.exceptionOrNull() as DongHaengException.Api
        assertEquals("BAD_RESPONSE", error.code)
    }

    // ---------- 공개 init 경유 ----------

    @Test
    fun `공개 init 의 DEV 제공자도 서버 REST 를 쓴다(T13 전환)`() = runTest {
        server.enqueue(json(200, tokenJson(jwt())))
        server.enqueue(json(200, vehiclesJson))

        DongHaeng.init(DongHaengConfig("$baseUrl/", AccountProvider.DEV, "0.1.0-test"), hostToken)
        val vehicles = DongHaeng.listMyVehicles()

        assertEquals(2, vehicles.size)
        assertEquals("/v1/auth/exchange", server.takeRequest().path)
        assertEquals("/v1/me/vehicles", server.takeRequest().path)
    }

    @Test
    fun `JwtClaims - exp 를 읽고, JWT 가 아니면 null`() {
        assertEquals(1_900_000_000_000L, JwtClaims.expiresAtMs(jwt(expEpochS = 1_900_000_000L)))
        assertNull(JwtClaims.expiresAtMs("dev:plain-token"))
        assertNull(JwtClaims.expiresAtMs("a.b.c"))
        assertNull(JwtClaims.expiresAtMs(""))
    }
}
