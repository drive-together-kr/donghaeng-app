package app.donghaeng.group

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InviteTokensTest {

    @Test
    fun `토큰은 inv_ 접두사 + Base62, 매번 다르고 해시는 64자 hex`() {
        val a = InviteTokens.generate()
        val b = InviteTokens.generate()
        assertTrue(a.startsWith("inv_"), a)
        assertTrue(a.length in 40..InviteTokens.MAX_LENGTH, a)
        assertNotEquals(a, b)
        assertEquals(a, InviteTokens.normalize(" $a "))
        val hash = InviteTokens.hash(a)
        assertEquals(64, hash.length)
        assertTrue(hash.all { it in "0123456789abcdef" })
        assertNotEquals(hash, InviteTokens.hash(b))
    }

    @Test
    fun `형식이 틀린 토큰은 normalize 가 거른다`() {
        assertNull(InviteTokens.normalize(null))
        assertNull(InviteTokens.normalize(""))
        assertNull(InviteTokens.normalize("inv_"))
        assertNull(InviteTokens.normalize("abc"))
        assertNull(InviteTokens.normalize("inv_has space"))
        assertNull(InviteTokens.normalize("inv_" + "x".repeat(InviteTokens.MAX_LENGTH)))
        assertNull(InviteTokens.normalize("inv_ab-cd"))
        assertEquals("inv_abcXYZ019", InviteTokens.normalize("inv_abcXYZ019"))
    }
}
