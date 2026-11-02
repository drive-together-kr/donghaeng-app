package app.donghaeng.common.web

import app.donghaeng.KPostgresContainer
import app.donghaeng.KRedisContainer
import app.donghaeng.protocol.DhJson
import app.donghaeng.protocol.rest.ApiPaths
import app.donghaeng.protocol.rest.DeviceCodeRequest
import app.donghaeng.protocol.rest.ErrorCodes
import app.donghaeng.protocol.rest.ErrorEnvelope
import app.donghaeng.protocol.rest.LoginRequest
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

/**
 * C094·C106: 공개 경로의 IP 당 분당 요청 수 제한(로그인·디바이스 코드 발급). 실제 Redis 로 센다.
 * 기본값은 30 이지만 여기서는 3 으로 낮춰 확인한다(테스트 기본 설정은 다른 통합 테스트를 위해 사실상 끈다).
 * 클라이언트 IP 는 루프백에서 온 요청의 `X-Forwarded-For` 마지막 값 — Caddy 뒤와 같은 판정.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["dh.rate-limit.ip-per-minute=3"],
)
@Testcontainers(disabledWithoutDocker = true)
class RateLimitIT {

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

    private fun post(path: String, body: String, clientIp: String): ResponseEntity<String> {
        val headers = HttpHeaders().apply {
            accept = listOf(MediaType.APPLICATION_JSON)
            contentType = MediaType.APPLICATION_JSON
            set("X-Forwarded-For", clientIp)
        }
        return rest.exchange(path, HttpMethod.POST, HttpEntity(body, headers), String::class.java)
    }

    private fun ResponseEntity<String>.assertRateLimited() {
        assertEquals(429, statusCode.value(), body)
        assertEquals(ErrorCodes.RATE_LIMITED, json.decodeFromString<ErrorEnvelope>(body!!).error.code)
        val retryAfter = headers.getFirst(HttpHeaders.RETRY_AFTER)?.toIntOrNull()
        assertTrue(retryAfter != null && retryAfter in 1..60, "Retry-After=$retryAfter")
    }

    @Test
    fun `the 4th login from one IP within a minute is 429 with Retry-After, another IP is not affected`() {
        val body = json.encodeToString(LoginRequest(email = "rate@example.com", displayName = "빈도"))
        repeat(3) { assertEquals(200, post(ApiPaths.AUTH_LOGIN, body, "203.0.113.10").statusCode.value()) }
        post(ApiPaths.AUTH_LOGIN, body, "203.0.113.10").assertRateLimited()
        assertEquals(200, post(ApiPaths.AUTH_LOGIN, body, "203.0.113.11").statusCode.value())
    }

    @Test
    fun `unauthenticated device code issuing is limited per IP as well`() {
        val body = json.encodeToString(DeviceCodeRequest(vehicleId = "CAR-RATE-1"))
        repeat(3) { assertEquals(200, post(ApiPaths.DEVICE_CODE, body, "198.51.100.20").statusCode.value()) }
        post(ApiPaths.DEVICE_CODE, body, "198.51.100.20").assertRateLimited()
        assertEquals(200, post(ApiPaths.DEVICE_CODE, body, "198.51.100.21").statusCode.value())
    }
}
