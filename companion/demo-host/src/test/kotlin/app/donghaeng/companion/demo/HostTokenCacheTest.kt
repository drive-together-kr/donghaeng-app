package app.donghaeng.companion.demo

import app.donghaeng.protocol.rest.TokenResponse
import app.donghaeng.protocol.rest.UserDto
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Base64

/** D023 — 호스트 토큰(1시간)을 만료 2분 전부터 다시 받는다. 실패 시 아직 유효하면 그대로, 만료면 네트워크=IOException·거부=빈 문자열 */
class HostTokenCacheTest {

    private var now = 1_793_000_000_000L
    private var reissues = 0

    private fun jwt(expMs: Long, tag: String): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        val header = enc.encodeToString("""{"alg":"RS256"}""".toByteArray())
        val payload = enc.encodeToString("""{"sub":"usr_1","typ":"user","exp":${expMs / 1000},"tag":"$tag"}""".toByteArray())
        return "$header.$payload.sig"
    }

    private fun tokens(access: String) = TokenResponse(access, "rt_opaque", UserDto("usr_1", "동승자"))

    private val oneHour = 60 * 60 * 1000L
    private val first = jwt(now + oneHour, "first")
    private val second = jwt(now + 2 * oneHour, "second")

    private fun cache(reissue: suspend () -> TokenResponse = { reissues++; tokens(second) }) =
        HostTokenCache(tokens(first), reissue = reissue, clock = { now })

    @Test
    fun `만료까지 여유가 있으면 다시 받지 않는다`() = runTest {
        val cache = cache()
        now += oneHour - 3 * 60 * 1000L // 3분 남음
        assertEquals(first, cache.accessToken())
        assertEquals(0, reissues)
    }

    @Test
    fun `만료 2분 안이면 다시 받아 새 토큰을 주고, 이후에는 새 토큰을 쓴다`() = runTest {
        val cache = cache()
        now += oneHour - 60 * 1000L // 1분 남음
        assertEquals(second, cache.accessToken())
        assertEquals(second, cache.accessToken())
        assertEquals("한 번만 다시 받는다", 1, reissues)
    }

    @Test
    fun `로그인 약 2시간 뒤(두 번째 교환)에도 만료 토큰을 내주지 않는다 - 회귀`() = runTest {
        val cache = cache()
        now += 2 * oneHour + 30_000L // 첫 토큰은 이미 만료
        val token = cache.accessToken()
        assertEquals(second, token)
        assertTrue(JwtExp.expiresAtMs(token)!! > now - 60_000)
    }

    @Test
    fun `다시 받기가 네트워크로 실패해도 아직 유효하면 지금 토큰`() = runTest {
        val cache = cache { throw DevAuthClient.DevAuthException("서버에 연결할 수 없어요", IOException("timeout")) }
        now += oneHour - 60 * 1000L
        assertEquals(first, cache.accessToken())
    }

    @Test
    fun `만료됐고 네트워크 실패면 IOException - 모듈이 일시 오류(NETWORK)로 다룬다`() = runTest {
        val cache = cache { throw DevAuthClient.DevAuthException("서버에 연결할 수 없어요", IOException("timeout")) }
        now += oneHour + 1_000L
        val error = runCatching { cache.accessToken() }.exceptionOrNull()
        assertTrue("$error", error is IOException)
    }

    @Test
    fun `만료됐고 서버가 거부하면 빈 문자열 - 모듈이 Unauthorized 로 다시 로그인 안내`() = runTest {
        val cache = cache { throw DevAuthClient.DevAuthException("로그인에 실패했어요 (HTTP 403)") }
        now += oneHour + 1_000L
        assertEquals("", cache.accessToken())
    }

    @Test
    fun `로그아웃(clear) 뒤에는 빈 문자열`() = runTest {
        val cache = cache()
        cache.clear()
        assertEquals("", cache.accessToken())
        assertEquals(0, reissues)
    }

    @Test
    fun `JWT 가 아니면 해석하지 않고 그대로`() = runTest {
        val cache = HostTokenCache(tokens("opaque-token"), reissue = { reissues++; tokens(second) }, clock = { now })
        assertEquals("opaque-token", cache.accessToken())
        assertEquals(0, reissues)
    }
}
