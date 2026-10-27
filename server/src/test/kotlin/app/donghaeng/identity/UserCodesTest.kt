package app.donghaeng.identity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UserCodesTest {

    private val allowed = UserCodes.ALPHABET.toSet()

    @Test
    fun `codes are 6 chars from the confusion-free alphabet`() {
        val codes = List(2000) { UserCodes.generate() }
        codes.forEach { code ->
            assertEquals(UserCodes.LENGTH, code.length, code)
            assertTrue(code.all { it in allowed }, "invalid char in $code")
        }
        // 0/O/1/I/L 은 알파벳에 없다
        listOf('0', 'O', '1', 'I', 'L').forEach { assertTrue(it !in allowed, "$it must not be in alphabet") }
        assertTrue(codes.toSet().size > 1990, "codes should be random: ${codes.toSet().size}")
    }

    @Test
    fun `normalize uppercases and strips hyphens and spaces`() {
        assertEquals("KQ74MZ", UserCodes.normalize("kq7-4mz"))
        assertEquals("KQ74MZ", UserCodes.normalize(" KQ7 4MZ "))
        assertEquals("KQ74MZ", UserCodes.normalize("KQ74MZ"))
    }

    @Test
    fun `normalize rejects wrong length or characters`() {
        assertNull(UserCodes.normalize(null))
        assertNull(UserCodes.normalize(""))
        assertNull(UserCodes.normalize("KQ74M"))      // 5자
        assertNull(UserCodes.normalize("KQ74MZ2"))    // 7자
        assertNull(UserCodes.normalize("KQ740Z"))     // 0 은 알파벳 밖
        assertNull(UserCodes.normalize("KQ74M!"))
    }
}
