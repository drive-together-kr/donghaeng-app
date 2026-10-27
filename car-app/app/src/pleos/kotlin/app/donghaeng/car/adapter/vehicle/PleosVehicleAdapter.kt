package app.donghaeng.car.adapter.vehicle

import ai.pleos.playground.vehicle.Vehicle
import android.content.Context
import app.donghaeng.car.core.common.DhLog
import app.donghaeng.car.domain.model.Gear
import app.donghaeng.car.domain.model.TurnSignalState
import app.donghaeng.car.domain.model.VehicleCapabilities
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Pleos Vehicle SDK 2.3.2 어댑터 — **T04 시점에는 골격만** (Hilt 에 바인딩되지 않음, pleos flavor 도 Fake 를 쓴다).
 *
 * SDK 시그니처는 Gradle 캐시의 `Vehicle-2.3.2.aar` 를 javap 로 확인했다(공식 샘플은 Door 만 다룬다):
 * ```
 * Vehicle(context).initialize() / release()
 * vehicle.getCarInfo().getVINInfo(onComplete: (String) -> Unit, onFailed: (Exception) -> Unit)        // T11 vehicleId
 * vehicle.getFuel().checkFuelPercentCapabilities(onComplete: (CapabilityConfig) -> Unit, onFailed)
 * vehicle.getFuel().registerFuelPercent(FuelPercentListener) / unregisterFuelPercent(listener)        // T22
 * vehicle.getEvBattery().registerEvBatteryStateOfCharge(EvBatteryStateOfChargeListener)
 * vehicle.getBrake().registerBrakePedalStatus(BrakePedalStatusListener)
 * vehicle.getTurnSignal().registerTurnSignalState(TurnSignalStateListener)   // 값: constant.area.TurnSignalValue
 * vehicle.getSafety().registerSeatAirbagDeployed(SeatArea, SeatAirbagDeployedListener)
 * vehicle.getGear()  // ← 2.3.2 에는 Gear 도메인이 있다(docs/10 R2 는 "없음" 가정). T22 에서 Car API 보다 먼저 시도할 것.
 * ```
 * 규칙(CLAUDE.md 3): 콜백은 callbackFlow + awaitClose 에서 **같은 리스너로** unregister. `release()` 는 모든 콜백 이후.
 *
 * TODO(T11): [vehicleId] — getVINInfo → 실패 시 getCRNInfo → 둘 다 실패면 null. suspendCancellableCoroutine 으로 감싼다.
 * TODO(T22): 나머지 Flow + [capabilities] (checkXxxCapabilities 결과를 VehicleCapabilities.raw 에 요약).
 */
class PleosVehicleAdapter(context: Context) : VehicleAdapter {

    private val appContext = context.applicationContext
    private var vehicle: Vehicle? = null

    /** SDK 바인딩. 서비스 앱(ai.pleos.playground.service.vehicle)이 없으면 예외 → 로그만 남기고 false. */
    fun initialize(): Boolean = try {
        if (vehicle == null) vehicle = Vehicle(appContext).also { it.initialize() }
        true
    } catch (e: Exception) {
        DhLog.w("PleosVehicleAdapter initialize 실패 — Fake 로 동작해야 한다", e)
        vehicle = null
        false
    }

    /** 모든 구독이 끝난 뒤에만 호출한다. */
    fun release() {
        runCatching { vehicle?.release() }.onFailure { DhLog.w("PleosVehicleAdapter release 실패", it) }
        vehicle = null
    }

    override suspend fun vehicleId(): String? = null                     // TODO(T11)
    override fun fuelPercent(): Flow<Int?> = emptyFlow()                 // TODO(T22)
    override fun evStateOfCharge(): Flow<Int?> = emptyFlow()             // TODO(T22)
    override fun brakePedal(): Flow<Boolean> = emptyFlow()               // TODO(T22)
    override fun turnSignal(): Flow<TurnSignalState> = emptyFlow()       // TODO(T22)
    override fun airbagDeployed(): Flow<Boolean> = emptyFlow()           // TODO(T22)
    override fun speedKph(): Flow<Float?> = emptyFlow()                  // TODO(T22) Car API PERF_VEHICLE_SPEED
    override fun gear(): Flow<Gear?> = emptyFlow()                       // TODO(T22) vehicle.getGear() → 실패 시 Car API
    override suspend fun capabilities(): VehicleCapabilities = VehicleCapabilities.NONE   // TODO(T22)
}
