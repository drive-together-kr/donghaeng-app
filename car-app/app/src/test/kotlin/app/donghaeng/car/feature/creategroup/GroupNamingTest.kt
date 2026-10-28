package app.donghaeng.car.feature.creategroup

import app.donghaeng.car.core.common.Josa
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 그룹 기본 이름 "{닉네임}{와|과} 같이 가기" — 받침·숫자·영문 끝 규칙과 길이 한도 */
class GroupNamingTest {

    @Test
    fun `hangul nickname picks wa or gwa by the final consonant`() {
        assertEquals("아빠 차와 같이 가기", GroupNaming.defaultName("아빠 차"))
        assertEquals("둘째 차와 같이 가기", GroupNaming.defaultName("둘째 차"))
        assertEquals("삼촌 차와 같이 가기", GroupNaming.defaultName("삼촌 차"))
        assertEquals("엄마 차량과 같이 가기", GroupNaming.defaultName("엄마 차량"))
        assertEquals("앞뒤 공백은 지운다", "할머니댁과 같이 가기", GroupNaming.defaultName("  할머니댁 "))
    }

    @Test
    fun `digits are read in sino-korean, latin letters count as no final consonant`() {
        assertEquals("EV6과 같이 가기", GroupNaming.defaultName("EV6"))     // 육
        assertEquals("1호차와 같이 가기", GroupNaming.defaultName("1호차"))  // 한글 끝
        assertEquals("차 2와 같이 가기", GroupNaming.defaultName("차 2"))     // 이
        assertEquals("차 3과 같이 가기", GroupNaming.defaultName("차 3"))     // 삼
        assertEquals("DemoCarB와 같이 가기", GroupNaming.defaultName("DemoCarB"))
        assertEquals("괄호 끝은 안쪽 글자로", "(아빠 차)와 같이 가기", GroupNaming.defaultName("(아빠 차)"))
    }

    @Test
    fun `blank or too long nickname falls back to the plain name`() {
        assertEquals("같이 가기", GroupNaming.defaultName(null))
        assertEquals("같이 가기", GroupNaming.defaultName("   "))
        val nick23 = "가".repeat(23)   // 23 + 와 + " 같이 가기"(6) = 30 → 한도 안
        assertEquals(30, GroupNaming.defaultName(nick23).length)
        assertEquals("서버 한도 30자를 넘으면 닉네임 없이", "같이 가기", GroupNaming.defaultName("가".repeat(24)))
    }

    @Test
    fun `josa matches the server rule`() {
        assertTrue(Josa.hasBatchim("엄마 차량"))
        assertFalse(Josa.hasBatchim("아빠 차"))
        assertTrue(Josa.hasBatchim("차량 0"))   // 영
        assertFalse(Josa.hasBatchim("차량 9"))  // 구
        assertFalse(Josa.hasBatchim(""))
        assertEquals("과", Josa.gwaWa("\"강릉\""))
        assertEquals("와", Josa.gwaWa("Demo Car"))
    }
}
