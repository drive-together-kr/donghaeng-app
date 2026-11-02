package app.donghaeng.companion.demo.donghaeng

import app.donghaeng.companion.sdk.MyVehicle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** D093·D026·D078 — 방장 차·참여 끈 차는 고를 수 없고, 고를 차가 한 대일 때만 미리 고른다 */
class JoinVehiclePolicyTest {

    private val carA = MyVehicle("veh_a", "아빠 차", "KIA", "EV9", isPrimary = true) // 먼저 페어링 = 대표 = 그룹을 만든 차
    private val carB = MyVehicle("veh_b", "둘째 차", "KIA", "K5", isPrimary = false)
    private val carC = MyVehicle("veh_c", "삼촌 차", "HYUNDAI", "SONATA", isPrimary = false)

    @Test
    fun `시연 구성(같은 계정에 A·B, A 가 방장) - 대표 차량 A 가 아니라 B 를 미리 고른다`() {
        val options = JoinVehiclePolicy.options(listOf(carA, carB), leaderVehicleId = "veh_a", participationOff = emptySet())
        assertFalse(options.first { it.vehicle == carA }.selectable)
        assertEquals("veh_b", JoinVehiclePolicy.selection(options, current = null))
        assertEquals("이 그룹 방장 차", JoinVehiclePolicy.tag(options[0]))
    }

    @Test
    fun `고를 수 있는 차가 여러 대면 미리 고르지 않는다(같은 계정의 다른 사람 차일 수 있다)`() {
        val options = JoinVehiclePolicy.options(listOf(carA, carB, carC), leaderVehicleId = null, participationOff = emptySet())
        assertNull(JoinVehiclePolicy.selection(options, current = null))
        assertEquals("veh_c", JoinVehiclePolicy.selection(options, current = "veh_c"))
    }

    @Test
    fun `참여를 꺼 둔 차는 고를 수 없다 - 설정이 실제로 효과가 있다`() {
        val options = JoinVehiclePolicy.options(listOf(carA, carB), leaderVehicleId = null, participationOff = setOf("veh_a"))
        assertEquals("veh_b", JoinVehiclePolicy.selection(options, current = "veh_a"))
        assertEquals("참여 꺼 둠", JoinVehiclePolicy.tag(options[0]))
    }

    @Test
    fun `미리보기가 늦게 와 선택한 차가 방장 차로 밝혀지면 선택을 풀거나 바꾼다`() {
        val before = JoinVehiclePolicy.options(listOf(carA, carB, carC), leaderVehicleId = null, participationOff = emptySet())
        assertEquals("veh_a", JoinVehiclePolicy.selection(before, current = "veh_a"))
        val after = JoinVehiclePolicy.options(listOf(carA, carB, carC), leaderVehicleId = "veh_a", participationOff = emptySet())
        assertNull(JoinVehiclePolicy.selection(after, current = "veh_a"))
    }

    @Test
    fun `태그 - 대표가 아닌 내 차는 '공유받은 차량'이 아니라 '내 차'`() {
        val options = JoinVehiclePolicy.options(listOf(carA, carB), leaderVehicleId = null, participationOff = emptySet())
        assertEquals(listOf("대표 차량", "내 차"), options.map(JoinVehiclePolicy::tag))
    }
}
