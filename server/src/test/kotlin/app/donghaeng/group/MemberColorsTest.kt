package app.donghaeng.group

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemberColorsTest {

    @Test
    fun `팔레트는 10색 RRGGBB 이고 비어 있는 첫 색을 준다`() {
        assertEquals(10, MemberColors.PALETTE.size)
        assertEquals(10, MemberColors.PALETTE.toSet().size)
        MemberColors.PALETTE.forEach { assertTrue(Regex("^#[0-9A-F]{6}$").matches(it), it) }
        assertEquals(MemberColors.PALETTE[0], MemberColors.next(emptyList()))
        assertEquals(MemberColors.PALETTE[1], MemberColors.next(listOf(MemberColors.PALETTE[0])))
        // 가운데가 비면 그 색을 재사용한다 (대소문자 무시)
        assertEquals(MemberColors.PALETTE[1], MemberColors.next(listOf(MemberColors.PALETTE[0].lowercase(), MemberColors.PALETTE[2])))
    }

    @Test
    fun `팔레트가 다 차면 처음부터 다시 돈다`() {
        val color = MemberColors.next(MemberColors.PALETTE)
        assertTrue(color in MemberColors.PALETTE)
    }
}
