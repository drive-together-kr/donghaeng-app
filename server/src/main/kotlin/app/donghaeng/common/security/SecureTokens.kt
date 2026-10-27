package app.donghaeng.common.security

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * 불투명 토큰(refresh, device code)용 난수·해시 도우미.
 * 토큰 원문은 클라이언트만 갖고, DB 에는 [sha256Hex] 만 저장한다 (docs/06 §5).
 */
object SecureTokens {
    const val BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

    private val random = SecureRandom()
    private val base62Pattern = Regex("^[0-9A-Za-z]+$")

    /** 무작위 [byteCount] 바이트를 Base62 로 — 32바이트면 43자 안팎 (≈256비트). refresh 토큰용. */
    fun randomBase62Bytes(byteCount: Int = 32): String {
        require(byteCount > 0)
        val bytes = ByteArray(byteCount).also { random.nextBytes(it) }
        return encodeBase62(bytes)
    }

    /** Base62 글자 [length] 개 (글자마다 균등 난수). device code 용. */
    fun randomBase62Chars(length: Int = 32): String {
        require(length > 0)
        return String(CharArray(length) { BASE62[random.nextInt(BASE62.length)] })
    }

    fun isBase62(s: String): Boolean = base62Pattern.matches(s)

    fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun encodeBase62(bytes: ByteArray): String {
        var n = BigInteger(1, bytes)
        if (n.signum() == 0) return "0"
        val radix = BigInteger.valueOf(62)
        val sb = StringBuilder()
        while (n.signum() > 0) {
            val (q, r) = n.divideAndRemainder(radix)
            sb.append(BASE62[r.toInt()])
            n = q
        }
        return sb.reverse().toString()
    }
}
