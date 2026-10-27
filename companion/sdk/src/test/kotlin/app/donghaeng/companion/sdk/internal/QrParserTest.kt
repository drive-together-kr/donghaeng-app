package app.donghaeng.companion.sdk.internal

import app.donghaeng.companion.sdk.internal.QrParser.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/07 §5 QR 판별 규칙 */
class QrParserTest {

    private val localBase = "http://10.0.2.2:8080"
    private val cloudBase = "https://1-2-3-4.sslip.io"

    @Test
    fun `pair - base pair c=CODE 는 Pair(code)`() {
        assertEquals(Result.Pair("KQ74MZ"), QrParser.parse("http://10.0.2.2:8080/pair?c=KQ74MZ", localBase))
    }

    @Test
    fun `join - base j token 은 Join(token)`() {
        assertEquals(Result.Join("inv_abc123"), QrParser.parse("http://10.0.2.2:8080/j/inv_abc123", localBase))
    }

    @Test
    fun `join - 클라우드 base(https, sslip) 도 같은 규칙`() {
        assertEquals(Result.Join("inv_xyz"), QrParser.parse("https://1-2-3-4.sslip.io/j/inv_xyz", cloudBase))
        assertEquals(Result.Pair("AB12CD"), QrParser.parse("https://1-2-3-4.sslip.io/pair?c=AB12CD", cloudBase))
    }

    @Test
    fun `other server - 경로는 맞지만 호스트가 다르면 OtherServer(host)`() {
        val r = QrParser.parse("https://1-2-3-4.sslip.io/j/inv_abc", localBase)
        assertTrue(r is Result.OtherServer)
        assertEquals("1-2-3-4.sslip.io", (r as Result.OtherServer).host)
    }

    @Test
    fun `other server - 포트가 다르면 다른 서버`() {
        val r = QrParser.parse("http://10.0.2.2:9090/pair?c=KQ74MZ", localBase)
        assertEquals(Result.OtherServer("10.0.2.2:9090"), r)
    }

    @Test
    fun `unknown - 동행 경로가 아니면 Unknown (호스트가 같아도)`() {
        assertEquals(Result.Unknown, QrParser.parse("http://10.0.2.2:8080/foo/bar", localBase))
        assertEquals(Result.Unknown, QrParser.parse("http://10.0.2.2:8080/", localBase))
        assertEquals(Result.Unknown, QrParser.parse("http://10.0.2.2:8080/pair", localBase)) // 코드 없음
    }

    @Test
    fun `unknown - URL 이 아니거나 http(s) 가 아니면 Unknown`() {
        assertEquals(Result.Unknown, QrParser.parse("hello world", localBase))
        assertEquals(Result.Unknown, QrParser.parse("", localBase))
        assertEquals(Result.Unknown, QrParser.parse("mailto:someone@dev.local", localBase))
        assertEquals(Result.Unknown, QrParser.parse("WIFI:T:WPA;S:dh;P:secret;;", localBase))
    }

    @Test
    fun `unknown - 다른 호스트라도 경로가 동행이 아니면 OtherServer 가 아니라 Unknown`() {
        assertEquals(Result.Unknown, QrParser.parse("https://example.invalid/some/page", localBase))
    }

    @Test
    fun `trailing slash - base 끝의 슬래시와 QR 경로 끝 슬래시를 허용`() {
        assertEquals(Result.Pair("KQ74MZ"), QrParser.parse("http://10.0.2.2:8080/pair?c=KQ74MZ", "http://10.0.2.2:8080/"))
        assertEquals(Result.Join("inv_1"), QrParser.parse("http://10.0.2.2:8080/j/inv_1/", "http://10.0.2.2:8080/"))
        assertEquals(Result.Join("inv_1"), QrParser.parse("  http://10.0.2.2:8080/j/inv_1  ", "http://10.0.2.2:8080/"))
    }

    @Test
    fun `base 에 경로 접두사가 있으면 떼고 판별`() {
        assertEquals(Result.Join("inv_9"), QrParser.parse("https://1-2-3-4.sslip.io/dh/j/inv_9", "https://1-2-3-4.sslip.io/dh/"))
    }

    @Test
    fun `호스트 대소문자는 무시`() {
        assertEquals(Result.Join("inv_9"), QrParser.parse("https://1-2-3-4.SSLIP.io/j/inv_9", cloudBase))
    }

    @Test
    fun `pair 코드는 URL 디코딩되고 다른 쿼리 파라미터는 무시`() {
        assertEquals(Result.Pair("KQ7 4MZ"), QrParser.parse("http://10.0.2.2:8080/pair?x=1&c=KQ7%204MZ&y=2", localBase))
    }
}
