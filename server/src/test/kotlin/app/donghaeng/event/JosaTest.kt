package app.donghaeng.event

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JosaTest {

    @Test
    fun `받침 있는 한글은 이-은-을-과`() {
        assertEquals("이", Josa.iGa("아빠 차량"))
        assertEquals("은", Josa.eunNeun("정민"))
        assertEquals("을", Josa.eulReul("휴게소 입구인 것"))
        assertEquals("과", Josa.gwaWa("엄마 차량"))
    }

    @Test
    fun `받침 없는 한글은 가-는-를-와`() {
        assertEquals("가", Josa.iGa("아빠 차"))
        assertEquals("는", Josa.eunNeun("엄마 차"))
        assertEquals("를", Josa.eulReul("강릉 바다"))
        assertEquals("와", Josa.gwaWa("할머니"))
    }

    @Test
    fun `으로-로 는 ㄹ 받침을 따로 본다`() {
        assertEquals("으로", Josa.euroRo("부산"))
        assertEquals("로", Josa.euroRo("서울"))
        assertEquals("로", Josa.euroRo("제주"))
    }

    @Test
    fun `숫자는 읽는 소리로, 영문·기호는 받침 없음으로 본다`() {
        assertTrue(Josa.hasBatchim("차량 1"))   // 일
        assertTrue(Josa.hasBatchim("차량 3"))   // 삼
        assertFalse(Josa.hasBatchim("차량 2"))  // 이
        assertFalse(Josa.hasBatchim("차량 9"))  // 구
        assertFalse(Josa.hasBatchim("EV9 Car"))
        assertEquals("가", Josa.iGa("Demo Car"))
    }

    @Test
    fun `끝의 공백·괄호·따옴표는 무시한다`() {
        assertEquals("이", Josa.iGa("아빠 차량 "))
        assertEquals("가", Josa.iGa("아빠 차)"))
        assertEquals("이", Josa.iGa("\"정민\""))
        assertEquals("가", Josa.iGa(""))
        assertEquals("아빠 차가", Josa.attach("아빠 차", "이", "가"))
    }
}
