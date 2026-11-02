package app.donghaeng.companion.demo

import app.donghaeng.protocol.rest.TokenResponse
import app.donghaeng.protocol.rest.UserDto
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * RD-3 — dev 로그인 실패에 HTTP 상태를 싣고, 5xx·429·연결 실패만 일시 오류로 본다.
 * [HostTokenCache] 와 이어 붙여 '만료된 호스트 토큰 + 재배포 중 502' 가 다시 로그인 안내(빈 토큰)가 아니라 IOException(모듈 → NETWORK, 백오프 재시도)이 되는지 본다.
 */
class DevAuthClientTest {

    private lateinit var server: MockWebServer
    private lateinit var baseUrl: String

    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        baseUrl = server.url("/").toString()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun auth() = DevAuthClient(baseUrl, client = client)

    private suspend fun loginFailure(): DevAuthClient.DevAuthException =
        runCatching { auth().login("judge-00000000@dev.local", "동승자") }.exceptionOrNull() as DevAuthClient.DevAuthException

    @Test
    fun `프록시 502(본문 HTML)는 상태를 싣고 일시 오류`() = runTest {
        server.enqueue(MockResponse().setResponseCode(502).setHeader("Content-Type", "text/html").setBody("<html>Bad Gateway</html>"))
        val error = loginFailure()
        assertEquals(502, error.status)
        assertTrue(error.transient)
    }

    @Test
    fun `429 RATE_LIMITED 는 일시 오류, 403 FORBIDDEN 은 거부`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(429).setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"code":"RATE_LIMITED","message":"잠시 뒤 다시 시도해 주세요","traceId":"t-1"}}"""),
        )
        server.enqueue(
            MockResponse().setResponseCode(403).setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"code":"FORBIDDEN","message":"접속 키가 맞지 않아요","traceId":"t-2"}}"""),
        )
        val limited = loginFailure()
        assertEquals(429, limited.status)
        assertTrue(limited.transient)
        val forbidden = loginFailure()
        assertEquals(403, forbidden.status)
        assertFalse(forbidden.transient)
    }

    @Test
    fun `서버에 닿지 않으면 상태 없이 IOException 원인 - 일시 오류`() = runTest {
        server.shutdown()
        val error = loginFailure()
        assertEquals(null, error.status)
        assertTrue(error.cause is IOException)
        assertTrue(error.transient)
    }

    @Test
    fun `만료된 호스트 토큰을 다시 받다 502 면 HostTokenCache 는 빈 토큰이 아니라 IOException`() = runTest {
        server.enqueue(MockResponse().setResponseCode(502).setBody("Bad Gateway"))
        val auth = auth()
        val expired = expiredTokens()
        val cache = HostTokenCache(expired, reissue = { auth.login("judge-00000000@dev.local", "동승자") })
        val error = runCatching { cache.accessToken() }.exceptionOrNull()
        assertTrue("$error", error is IOException)
    }

    /** 이미 만료된 dev 토큰 쌍(서명 없는 가짜 JWT) */
    private fun expiredTokens(): TokenResponse {
        val enc = Base64.getUrlEncoder().withoutPadding()
        val header = enc.encodeToString("""{"alg":"RS256"}""".toByteArray())
        val payload = enc.encodeToString("""{"sub":"usr_1","typ":"user","exp":${System.currentTimeMillis() / 1000 - 60}}""".toByteArray())
        return TokenResponse("$header.$payload.sig", "rt_opaque", UserDto("usr_1", "동승자"))
    }
}
