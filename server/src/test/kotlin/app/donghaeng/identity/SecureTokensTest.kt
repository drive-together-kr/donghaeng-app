package app.donghaeng.identity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** refresh 토큰·device code 의 난수와 해시 규칙 */
class SecureTokensTest {

    private val base62 = Regex("^[0-9A-Za-z]+$")

    @Test
    fun `refresh token is 32 random bytes in base62 and never repeats`() {
        val tokens = List(500) { SecureTokens.randomBase62Bytes(32) }
        tokens.forEach {
            assertTrue(base62.matches(it), it)
            // 256비트는 Base62 로 최대 43자 (앞자리 0 이면 더 짧을 수 있음)
            assertTrue(it.length in 38..43, "length=${it.length}")
            assertTrue(SecureTokens.isBase62(it))
        }
        assertEquals(tokens.size, tokens.toSet().size, "duplicates")
    }

    @Test
    fun `device code random part is exactly 32 base62 chars`() {
        repeat(200) {
            val s = SecureTokens.randomBase62Chars(32)
            assertEquals(32, s.length)
            assertTrue(base62.matches(s), s)
        }
    }

    @Test
    fun `sha256Hex is deterministic lowercase hex`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            SecureTokens.sha256Hex("abc"),
        )
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            SecureTokens.sha256Hex(""),
        )
        val token = SecureTokens.randomBase62Bytes()
        assertEquals(SecureTokens.sha256Hex(token), SecureTokens.sha256Hex(token))
        assertNotEquals(SecureTokens.sha256Hex(token), SecureTokens.sha256Hex(token + "x"))
    }

    @Test
    fun `refresh token hash stored in DB is the sha256 of the raw token`() {
        val raw = SecureTokens.randomBase62Bytes()
        assertEquals(SecureTokens.sha256Hex(raw), RefreshTokenService.hash(raw))
        assertEquals(64, RefreshTokenService.hash(raw).length)
        assertTrue(!RefreshTokenService.hash(raw).contains(raw))
    }

    @Test
    fun `isBase62 rejects other characters`() {
        assertTrue(!SecureTokens.isBase62(""))
        assertTrue(!SecureTokens.isBase62("abc-def"))
        assertTrue(!SecureTokens.isBase62("abc def"))
        assertTrue(SecureTokens.isBase62("0Az9"))
    }
}
