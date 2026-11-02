package app.donghaeng.car.domain.vehicle

import app.donghaeng.car.adapter.vehicle.FakeVehicleAdapter
import app.donghaeng.car.adapter.vehicle.VehicleAdapter
import app.donghaeng.car.domain.model.Gear
import app.donghaeng.car.domain.model.TurnSignalState
import app.donghaeng.car.domain.model.VehicleCapabilities
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VehicleSignalsTest {

    @Test
    fun `collects every fake signal and counts updates`() = runTest {
        val signals = VehicleSignals(FakeVehicleAdapter(tickMillis = 1_000), retryDelayMs = 30_000, dispatcher = StandardTestDispatcher(testScheduler), clock = { testScheduler.currentTime })
        signals.start()
        advanceTimeBy(3_000); runCurrent()
        val s = signals.state.value
        assertTrue(s.running)
        assertEquals(62, s.fuelPercent)
        assertNull(s.evSoc)                       // 내연기관 흉내 → null 이지만 스트림은 산다
        assertEquals(false, s.brakePedal)
        assertEquals(TurnSignalState.OFF, s.turnSignal)
        assertEquals(false, s.airbagDeployed)
        assertNotNull(s.speedKph)
        assertEquals(Gear.D, s.gear)
        assertEquals(4, s.updates["fuel"])        // t=0,1,2,3
        assertEquals(4, s.updates["soc"])
        assertEquals(3_000L, s.lastTs["gear"])
        assertEquals("구독 중", s.streams["speed"])

        advanceTimeBy(9_000); runCurrent()        // t=12: 브레이크 ON 구간(10..12)
        assertEquals(true, signals.state.value.brakePedal)
        assertEquals(TurnSignalState.LEFT, signals.state.value.turnSignal)
        signals.stop()
        assertFalse(signals.state.value.running)
    }

    @Test
    fun `finished or failing streams are retried without stopping the others`() = runTest {
        var speedAttempts = 0
        val adapter = object : VehicleAdapter by FakeVehicleAdapter(tickMillis = 1_000) {
            override fun speedKph(): Flow<Float?> = flow {
                speedAttempts++
                if (speedAttempts == 1) throw IllegalStateException("car api down")
                emit(42f)
            }
            override fun gear(): Flow<Gear?> = emptyFlow()          // 미지원
            override suspend fun capabilities(): VehicleCapabilities = VehicleCapabilities.NONE
        }
        val signals = VehicleSignals(adapter, retryDelayMs = 5_000, dispatcher = StandardTestDispatcher(testScheduler))
        signals.start()
        runCurrent()
        assertTrue(signals.state.value.streams["speed"]!!.startsWith("오류"))
        assertTrue(signals.state.value.streams["gear"]!!.startsWith("끝남"))
        assertNull(signals.state.value.speedKph)
        assertEquals(62, signals.state.value.fuelPercent)
        advanceTimeBy(5_001); runCurrent()
        assertEquals(2, speedAttempts)
        assertEquals(42f, signals.state.value.speedKph)
        assertEquals(1, signals.state.value.updates["speed"])
        signals.restart()
        runCurrent()
        assertEquals(1, signals.state.value.restarts)
        assertEquals(3, speedAttempts)
        signals.stop()
    }

    @Test
    fun `a single null value marks an unsupported signal and still completes`() = runTest {
        val adapter = object : VehicleAdapter by FakeVehicleAdapter(tickMillis = 1_000) {
            override fun fuelPercent(): Flow<Int?> = flowOf(null)
        }
        val signals = VehicleSignals(adapter, retryDelayMs = 60_000, dispatcher = StandardTestDispatcher(testScheduler))
        signals.start()
        runCurrent()
        assertNull(signals.state.value.fuelPercent)
        assertEquals(1, signals.state.value.updates["fuel"])
        assertTrue(signals.state.value.streams["fuel"]!!.startsWith("끝남"))
        signals.stop()
    }

    @Test
    fun `A048 - a permanently unsupported stream is resubscribed with a growing interval, a real value resets it`() = runTest {
        var fuelAttempts = 0
        var supported = false
        val adapter = object : VehicleAdapter by FakeVehicleAdapter(tickMillis = 1_000) {
            override fun fuelPercent(): Flow<Int?> = flow {
                fuelAttempts++
                if (supported) emit(50) else emit(null)   // 미지원 = null 한 번 → 끝
            }
        }
        val signals = VehicleSignals(adapter, retryDelayMs = 30_000, dispatcher = StandardTestDispatcher(testScheduler))
        signals.start()
        try {
        runCurrent()
        assertEquals(1, fuelAttempts)
        // 예전: 30 s 마다(4분이면 9번). 지금: 30 → 60 → 120 s …
        advanceTimeBy(240_000); runCurrent()
        assertEquals("30+60+120 = 210 s 안에 세 번 더", 4, fuelAttempts)
        assertTrue(signals.state.value.streams["fuel"]!!.contains("240s"))
        // 권한 승인 등 restart → 바로 다시, 간격도 처음부터
        supported = true
        signals.restart(); runCurrent()
        assertEquals(5, fuelAttempts)
        advanceTimeBy(30_001); runCurrent()
        assertEquals("값을 받으면 다시 30 s", 6, fuelAttempts)
        } finally { signals.stop() }
        assertEquals(listOf(30_000L, 30_000L, 60_000L, 120_000L, 600_000L), listOf(0, 1, 2, 3, 9).map { VehicleSignals.retryDelayFor(it, 30_000, 600_000) })
    }
}
