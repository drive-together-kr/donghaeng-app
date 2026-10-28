package app.donghaeng.car.feature.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T32: 설정 제목 3초 안에 5번 탭 → 숨은 메뉴 */
class HiddenTapCounterTest {

    @Test
    fun `five taps within three seconds open the menu once`() {
        val c = HiddenTapCounter()
        repeat(4) { assertFalse(c.tap(1_000L + it * 500)) }
        assertTrue(c.tap(3_000L))
        // 다시 처음부터 센다
        assertFalse(c.tap(3_100L))
    }

    @Test
    fun `slow taps do not add up`() {
        val c = HiddenTapCounter()
        listOf(0L, 900L, 1_800L, 2_700L).forEach { assertFalse(c.tap(it)) }
        // 첫 탭에서 3.1 s — 창 밖이라 4번째로만 센다
        assertFalse(c.tap(3_100L))
        assertTrue(c.tap(3_200L))
    }

    @Test
    fun `reset forgets earlier taps`() {
        val c = HiddenTapCounter()
        repeat(4) { c.tap(it * 100L) }
        c.reset()
        assertFalse(c.tap(500L))
    }
}
