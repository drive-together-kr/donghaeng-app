package app.donghaeng.car.adapter.vehicle

import app.donghaeng.car.domain.model.Gear
import app.donghaeng.car.domain.model.TurnSignalState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FakeVehicleAdapterTest {

    private val adapter = FakeVehicleAdapter(tickMillis = 1_000L, initialFuelPercent = 62, fuelDropEveryTicks = 30)

    @Test
    fun `차량 ID 는 가짜 접두사를 가진다`() = runTest {
        val id = adapter.vehicleId()
        assertEquals(FakeVehicleAdapter.FAKE_VEHICLE_ID, id)
        assertTrue(id.startsWith("FAKE-"))
    }

    @Test
    fun `연료는 시작값에서 30틱마다 1퍼센트 줄고 0 아래로 내려가지 않는다`() = runTest {
        // 가상 시간: 91틱 = 90초를 즉시 건너뛴다
        val values = adapter.fuelPercent().take(91).toList()
        assertEquals(62, values[0])
        assertEquals(62, values[29])
        assertEquals(61, values[30])
        assertEquals(60, values[60])
        assertEquals(59, values[90])

        val floor = FakeVehicleAdapter(tickMillis = 1_000L, initialFuelPercent = 1, fuelDropEveryTicks = 1)
        val tail = floor.fuelPercent().take(4).toList()
        assertEquals(listOf(1, 0, 0, 0), tail)
    }

    @Test
    fun `EV SoC 는 내연기관 흉내라 null`() = runTest {
        assertNull(adapter.evStateOfCharge().first())
    }

    @Test
    fun `브레이크는 20틱 주기로 3틱 동안 켜진다`() = runTest {
        val values = adapter.brakePedal().take(40).toList()
        val onTicks = values.withIndex().filter { it.value }.map { it.index }
        assertEquals(listOf(10, 11, 12, 30, 31, 32), onTicks)
    }

    @Test
    fun `방향지시등은 OFF LEFT OFF RIGHT 순환`() = runTest {
        val values = adapter.turnSignal().take(40).toList()
        assertEquals(TurnSignalState.OFF, values[0])
        assertEquals(TurnSignalState.LEFT, values[10])
        assertEquals(TurnSignalState.OFF, values[20])
        assertEquals(TurnSignalState.RIGHT, values[30])
        assertFalse(values.contains(TurnSignalState.HAZARD))
    }

    @Test
    fun `속도는 0에서 80 사이 사인파`() = runTest {
        val values = adapter.speedKph().take(60).toList().map { it!! }
        assertEquals(0f, values[0], 0.01f)
        assertEquals(80f, values[30], 0.01f)
        assertTrue(values.all { it >= -0.01f && it <= 80.01f })
    }

    @Test
    fun `기어는 D, 에어백은 전개 안 됨`() = runTest {
        assertEquals(Gear.D, adapter.gear().first())
        assertFalse(adapter.airbagDeployed().first())
    }

    @Test
    fun `capabilities 는 Fake 가 지원하는 항목만 true`() = runTest {
        val caps = adapter.capabilities()
        assertTrue(caps.vehicleId)
        assertTrue(caps.fuelPercent)
        assertTrue(caps.brakePedal)
        assertTrue(caps.turnSignal)
        assertFalse(caps.evStateOfCharge)
        assertFalse(caps.hazard)
        assertEquals("fake", caps.raw["adapter"])
    }
}
