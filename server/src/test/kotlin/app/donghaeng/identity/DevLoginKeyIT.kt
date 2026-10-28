package app.donghaeng.identity

import app.donghaeng.KPostgresContainer
import app.donghaeng.KRedisContainer
import app.donghaeng.protocol.DhJson
import app.donghaeng.protocol.rest.ApiHeaders
import app.donghaeng.protocol.rest.ApiPaths
import app.donghaeng.protocol.rest.ErrorCodes
import app.donghaeng.protocol.rest.ErrorEnvelope
import app.donghaeng.protocol.rest.ExchangeRequest
import app.donghaeng.protocol.rest.LoginRequest
import app.donghaeng.protocol.rest.TokenResponse
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val KEY = "it-dev-login-key-0123456789abcdef"

/**
 * dev 간편 로그인 공유 키(`dh.auth.dev-login-key`, 클라우드 T32): 키가 설정된 서버는 `X-Dev-Login-Key` 가 없거나 다르면
 * 403 `FORBIDDEN` 으로 거부하고, 같으면 지금과 똑같이 토큰 쌍을 준다. 받은 토큰의 dev 교환(companion 경로)도 그대로 된다.
 * 키가 없는 기본 설정(로컬)은 [IdentityFlowIT] 가 헤더 없이 로그인해 확인한다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["dh.auth.dev-login-key=$KEY"],
)
@Testcontainers(disabledWithoutDocker = true)
class DevLoginKeyIT {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = KPostgresContainer("postgres:16")

        @Container
        @ServiceConnection(name = "redis")
        @JvmStatic
        val redis = KRedisContainer(DockerImageName.parse("redis:7")).withExposedPorts(6379)
    }

    @Autowired
    lateinit var rest: TestRestTemplate

    private val json = DhJson.json

    private fun call(path: String, body: String, devLoginKey: String? = null): ResponseEntity<String> {
        val headers = HttpHeaders().apply {
            accept = listOf(MediaType.APPLICATION_JSON)
            contentType = MediaType.APPLICATION_JSON
            if (devLoginKey != null) set(ApiHeaders.DEV_LOGIN_KEY, devLoginKey)
        }
        return rest.exchange(path, HttpMethod.POST, HttpEntity(body, headers), String::class.java)
    }

    private fun loginBody(email: String) = json.encodeToString(LoginRequest(email = email, displayName = "키 시험"))

    private fun ResponseEntity<String>.assertForbidden() {
        assertEquals(403, statusCode.value(), body)
        assertEquals(ErrorCodes.FORBIDDEN, json.decodeFromString<ErrorEnvelope>(body!!).error.code, body)
    }

    @Test
    fun `missing or wrong dev login key is 403 FORBIDDEN`() {
        call(ApiPaths.AUTH_LOGIN, loginBody("nokey@example.com")).assertForbidden()
        call(ApiPaths.AUTH_LOGIN, loginBody("nokey@example.com"), devLoginKey = "").assertForbidden()
        call(ApiPaths.AUTH_LOGIN, loginBody("nokey@example.com"), devLoginKey = "wrong-key").assertForbidden()
        call(ApiPaths.AUTH_LOGIN, loginBody("nokey@example.com"), devLoginKey = KEY + "x").assertForbidden()
        // 키 검사가 본문 검증보다 먼저다 — 키 없는 호출자에게 계정 규칙(형식 오류 400)을 알려 주지 않는다
        call(ApiPaths.AUTH_LOGIN, """{"provider":"dev","email":"not-an-email","displayName":"x"}""").assertForbidden()
    }

    @Test
    fun `matching dev login key logs in and the dev token still exchanges`() {
        val res = call(ApiPaths.AUTH_LOGIN, loginBody("withkey@example.com"), devLoginKey = KEY)
        assertEquals(200, res.statusCode.value(), res.body)
        val tokens = json.decodeFromString<TokenResponse>(res.body!!)
        assertTrue(tokens.user.userId.startsWith("usr_"), tokens.user.userId)

        // companion 경로: 호스트 토큰(dev 로그인 accessToken) → /auth/exchange 는 키 없이 그대로 (DevTokenVerifier)
        val exchanged = call(ApiPaths.AUTH_EXCHANGE, json.encodeToString(ExchangeRequest("dev", tokens.accessToken)))
        assertEquals(200, exchanged.statusCode.value(), exchanged.body)
        assertEquals(tokens.user.userId, json.decodeFromString<TokenResponse>(exchanged.body!!).user.userId)

        // 형식 오류는 키가 맞을 때만 400 으로 보인다
        val bad = call(ApiPaths.AUTH_LOGIN, """{"provider":"dev","email":"not-an-email","displayName":"x"}""", devLoginKey = KEY)
        assertEquals(400, bad.statusCode.value(), bad.body)
        assertEquals(ErrorCodes.VALIDATION_FAILED, json.decodeFromString<ErrorEnvelope>(bad.body!!).error.code)
    }
}
