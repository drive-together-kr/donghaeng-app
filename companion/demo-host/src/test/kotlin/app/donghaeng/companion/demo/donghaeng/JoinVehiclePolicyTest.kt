package app.donghaeng.companion.demo.donghaeng

import app.donghaeng.companion.sdk.MyVehicle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D093·D026·D078·RD-2 — 참여 끈 차는 고를 수 없고, 방장 차는 고를 수 있지만(그 차에 탄 채 폰으로 보기) 다른 차가 있으면 미리 고르지 않는다.
 * 미리 고르기는 고를 차가 한 대일 때만.
 */
class JoinVehiclePolicyTest {

    private val carA = MyVehicle("veh_a", "아빠 차", "KIA", "EV9", isPrimary = true) // 먼저 페어링 = 대표 = 그룹을 만든 차
    private val carB = MyVehicle("veh_b", "둘째 차", "KIA", "K5", isPrimary = false)
    private val carC = MyVehicle("veh_c", "삼촌 차", "HYUNDAI", "SONATA", isPrimary = false)

    @Test
    fun `시연 구성(같은 계정에 A·B, A 가 방장) - 대표 차량 A 가 아니라 B 를 미리 고른다`() {
        val options = JoinVehiclePolicy.options(listOf(carA, carB), leaderVehicleId = "veh_a", participationOff = emptySet())
        assertEquals("veh_b", JoinVehiclePolicy.selection(options, current = null))
        assertEquals("이 그룹 방장 차", JoinVehiclePolicy.tag(options[0]))
        assertNotNull(JoinVehiclePolicy.hint(options[0]))
        assertNull(JoinVehiclePolicy.hint(options[1]))
    }

    @Test
    fun `RD-2 - 계정에 차가 한 대(그 차가 방장)면 그 차를 고를 수 있고 미리 고른다(폰에서 자기 그룹 보기)`() {
        val options = JoinVehiclePolicy.options(listOf(carA), leaderVehicleId = "veh_a", participationOff = emptySet())
        assertTrue("방장 차를 막지 않는다", options.single().selectable)
        assertEquals("veh_a", JoinVehiclePolicy.selection(options, current = null))
        assertEquals("이 그룹 방장 차", JoinVehiclePolicy.tag(options.single()))
    }

    @Test
    fun `RD-2 - 다른 차가 있어도 방장 차를 사용자가 직접 고를 수 있다`() {
        val options = JoinVehiclePolicy.options(listOf(carA, carB), leaderVehicleId = "veh_a", participationOff = emptySet())
        assertTrue(options.first { it.vehicle == carA }.selectable)
        assertEquals("veh_a", JoinVehiclePolicy.selection(options, current = "veh_a", userPicked = true))
    }

    @Test
    fun `고를 수 있는 차가 여러 대면 미리 고르지 않는다(같은 계정의 다른 사람 차일 수 있다)`() {
        val options = JoinVehiclePolicy.options(listOf(carA, carB, carC), leaderVehicleId = null, participationOff = emptySet())
        assertNull(JoinVehiclePolicy.selection(options, current = null))
        assertEquals("veh_c", JoinVehiclePolicy.selection(options, current = "veh_c", userPicked = true))
        val withLeader = JoinVehiclePolicy.options(listOf(carA, carB, carC), leaderVehicleId = "veh_a", participationOff = emptySet())
        assertNull("방장 차를 빼도 두 대", JoinVehiclePolicy.selection(withLeader, current = null))
    }

    @Test
    fun `참여를 꺼 둔 차는 고를 수 없다 - 설정이 실제로 효과가 있다`() {
        val options = JoinVehiclePolicy.options(listOf(carA, carB), leaderVehicleId = null, participationOff = setOf("veh_a"))
        assertFalse(options[0].selectable)
        assertEquals("veh_b", JoinVehiclePolicy.selection(options, current = "veh_a", userPicked = true))
        assertEquals("참여 꺼 둠", JoinVehiclePolicy.tag(options[0]))
    }

    @Test
    fun `참여를 꺼 둔 방장 차는 고를 수 없고 태그는 참여 꺼 둠`() {
        val options = JoinVehiclePolicy.options(listOf(carA), leaderVehicleId = "veh_a", participationOff = setOf("veh_a"))
        assertFalse(options.single().selectable)
        assertNull(JoinVehiclePolicy.selection(options, current = null))
        assertEquals("참여 꺼 둠", JoinVehiclePolicy.tag(options.single()))
        assertNull(JoinVehiclePolicy.hint(options.single()))
    }

    @Test
    fun `미리보기가 늦게 오면 미리 고른 값을 다시 정한다 - 방장 차가 아닌 한 대로`() {
        // 미리보기 전: 두 대 모두 방장 여부를 모름 → 미리 고르지 않음
        val before = JoinVehiclePolicy.options(listOf(carA, carB), leaderVehicleId = null, participationOff = emptySet())
        assertNull(JoinVehiclePolicy.selection(before, current = null))
        // 미리보기: A 가 방장 → B
        val after = JoinVehiclePolicy.options(listOf(carA, carB), leaderVehicleId = "veh_a", participationOff = emptySet())
        assertEquals("veh_b", JoinVehiclePolicy.selection(after, current = null))
    }

    @Test
    fun `먼저 보인 목록이 방장 차 한 대라 미리 골랐어도 새 목록에 둘째 차가 오면 둘째 차로(시연 순서 회귀)`() {
        val stale = JoinVehiclePolicy.options(listOf(carA), leaderVehicleId = "veh_a", participationOff = emptySet())
        val auto = JoinVehiclePolicy.selection(stale, current = null)
        assertEquals("veh_a", auto)
        val fresh = JoinVehiclePolicy.options(listOf(carA, carB), leaderVehicleId = "veh_a", participationOff = emptySet())
        assertEquals("미리 고른 값은 다시 정한다", "veh_b", JoinVehiclePolicy.selection(fresh, current = auto, userPicked = false))
        assertEquals("사용자가 고른 값은 그대로", "veh_a", JoinVehiclePolicy.selection(fresh, current = auto, userPicked = true))
    }

    @Test
    fun `태그 - 대표가 아닌 내 차는 '공유받은 차량'이 아니라 '내 차'`() {
        val options = JoinVehiclePolicy.options(listOf(carA, carB), leaderVehicleId = null, participationOff = emptySet())
        assertEquals(listOf("대표 차량", "내 차"), options.map(JoinVehiclePolicy::tag))
    }
}
