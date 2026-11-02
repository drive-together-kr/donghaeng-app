package app.donghaeng.common.text

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** C105: 차량·사용자 문자열 정리 — 제어·서식(양방향) 문자 제거, 공백 접기, 길이 자르기 */
class TextSanitizerTest {

    @Test
    fun `control and format characters become spaces and whitespace collapses`() {
        assertEquals("덕평 휴게소", TextSanitizer.normalize("  덕평‮휴게소\n"))
        assertEquals("a b", TextSanitizer.normalize("a\u0000​\tb"))
        assertEquals("", TextSanitizer.normalize(null))
        assertEquals("", TextSanitizer.normalize("\u0007‎"))
    }

    @Test
    fun `clean cuts to max without splitting a surrogate pair and returns null for blank`() {
        assertEquals("가".repeat(5), TextSanitizer.clean("가".repeat(50), 5))
        // 이모지(서로게이트 쌍)가 경계에 걸리면 반쪽을 남기지 않는다
        val emoji = "🚗" // 🚗
        assertEquals("ab", TextSanitizer.clean("ab$emoji", 3))
        assertEquals("ab$emoji", TextSanitizer.clean("ab$emoji", 4))
        assertNull(TextSanitizer.clean("   ", 10))
        assertNull(TextSanitizer.clean(null, 10))
        assertEquals("abc", TextSanitizer.clean("abc", 10))
    }
}
