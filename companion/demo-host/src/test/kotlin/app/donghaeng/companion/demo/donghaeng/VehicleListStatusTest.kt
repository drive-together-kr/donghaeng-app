package app.donghaeng.companion.demo.donghaeng

import app.donghaeng.companion.sdk.MyVehicle
import org.junit.Assert.assertEquals
import org.junit.Test

/** RD-4 — 마이카 탭 등: 불러오기 실패·불러오는 중을 '계정에 연결된 차가 없어요(QR 등록)'로 보이지 않는다 */
class VehicleListStatusTest {

    private val car = MyVehicle("veh_a", "아빠 차", "KIA", "EV9", isPrimary = true)

    @Test
    fun `빈 목록 + 불러오기 실패(NETWORK·Unauthorized)는 '차 없음'이 아니라 ERROR`() {
        assertEquals(VehicleListStatus.ERROR, VehicleListStatus.of(emptyList(), loading = false, error = "서버에 연결할 수 없어요"))
    }

    @Test
    fun `빈 목록 + 불러오는 중은 LOADING - 실패 뒤 다시 불러오는 동안에도`() {
        assertEquals(VehicleListStatus.LOADING, VehicleListStatus.of(emptyList(), loading = true, error = null))
        assertEquals(VehicleListStatus.LOADING, VehicleListStatus.of(emptyList(), loading = true, error = "서버에 연결할 수 없어요"))
    }

    @Test
    fun `불러왔는데 비었을 때만 EMPTY(등록 안내)`() {
        assertEquals(VehicleListStatus.EMPTY, VehicleListStatus.of(emptyList(), loading = false, error = null))
    }

    @Test
    fun `차가 있으면 다시 불러오는 중·실패여도 받아 둔 목록`() {
        assertEquals(VehicleListStatus.LIST, VehicleListStatus.of(listOf(car), loading = true, error = "x"))
        assertEquals(VehicleListStatus.LIST, VehicleListStatus.of(listOf(car), loading = false, error = null))
    }
}
