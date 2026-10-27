package app.donghaeng.car.adapter.vehicle

import app.donghaeng.car.domain.model.Gear
import app.donghaeng.car.domain.model.TurnSignalState
import app.donghaeng.car.domain.model.VehicleCapabilities
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.math.PI
import kotlin.math.sin

/**
 * Fake 차량 어댑터 — SDK 없이 그럴듯한 값을 흘린다(fake flavor 기본, pleos flavor 도 T22 전까지 이걸 쓴다).
 *
 * 값은 모두 결정적(deterministic)이라 단위 테스트에서 가상 시간으로 검증할 수 있다.
 * - 연료: [initialFuelPercent] 에서 시작해 [fuelDropEveryTicks] 틱마다 1%p 감소(0 에서 멈춤)
 * - EV SoC: 내연기관 차량을 흉내 내므로 null(UI 에서는 숨김)
 * - 브레이크: 20틱 주기로 3틱 동안 ON (급제동 판정 테스트용)
 * - 방향지시등: OFF → LEFT → OFF → RIGHT 를 10틱 주기로 순환
 * - 속도: 0~80 km/h 사인파(60틱 주기)
 * - 기어: 항상 D
 *
 * @param tickMillis 한 틱의 길이. 실제 1 Hz, 테스트에서는 runTest 가상 시간으로 건너뛴다.
 */
class FakeVehicleAdapter(
    private val tickMillis: Long = 1_000L,
    private val initialFuelPercent: Int = 62,
    private val fuelDropEveryTicks: Int = 30,
    private val fakeVehicleId: String = FAKE_VEHICLE_ID,
) : VehicleAdapter {

    override suspend fun vehicleId(): String = fakeVehicleId

    override fun fuelPercent(): Flow<Int?> = ticking { tick ->
        (initialFuelPercent - tick / fuelDropEveryTicks).coerceAtLeast(0)
    }

    override fun evStateOfCharge(): Flow<Int?> = ticking { null }

    override fun brakePedal(): Flow<Boolean> = ticking { tick -> tick % 20 in 10..12 }

    override fun turnSignal(): Flow<TurnSignalState> = ticking { tick ->
        when ((tick / 10) % 4) {
            1 -> TurnSignalState.LEFT
            3 -> TurnSignalState.RIGHT
            else -> TurnSignalState.OFF
        }
    }

    override fun airbagDeployed(): Flow<Boolean> = ticking { false }

    override fun speedKph(): Flow<Float?> = ticking { tick ->
        val phase = (tick % 60) / 60.0 * 2 * PI
        (40.0 + 40.0 * sin(phase - PI / 2)).toFloat()   // 0 에서 출발해 80 까지 올랐다가 내려온다
    }

    override fun gear(): Flow<Gear?> = ticking { Gear.D }

    override suspend fun capabilities(): VehicleCapabilities = VehicleCapabilities(
        vehicleId = true,
        fuelPercent = true,
        evStateOfCharge = false,
        brakePedal = true,
        turnSignal = true,
        hazard = false,
        airbag = true,
        speed = true,
        gear = true,
        raw = mapOf("adapter" to "fake"),
    )

    /** 첫 값은 즉시, 이후 [tickMillis] 간격으로 끝없이 흘린다. 수집이 취소되면 멈춘다. */
    private fun <T> ticking(valueAt: (tick: Int) -> T): Flow<T> = flow {
        var tick = 0
        while (true) {
            emit(valueAt(tick))
            tick++
            delay(tickMillis)
        }
    }

    companion object {
        /** 가짜 차량 ID — 실제 VIN 형식과 겹치지 않게 접두사를 둔다 */
        const val FAKE_VEHICLE_ID = "FAKE-DH-0001"
    }
}
