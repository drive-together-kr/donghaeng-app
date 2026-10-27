package app.donghaeng.car.adapter.vehicle

import app.donghaeng.car.domain.model.Gear
import app.donghaeng.car.domain.model.TurnSignalState
import app.donghaeng.car.domain.model.VehicleCapabilities
import kotlinx.coroutines.flow.Flow

/**
 * 차량 상태 조회 어댑터 (docs/05 §3). 조회만 — 제어(Set) API 는 쓰지 않는다(CLAUDE.md 규칙 6).
 *
 * 구현: `PleosVehicleAdapter`(pleos flavor, T11·T22) / [FakeVehicleAdapter](양쪽 flavor, 테스트·데모).
 * Pleos SDK 타입은 이 패키지 밖으로 새지 않는다(CLAUDE.md 규칙 1).
 *
 * 각 Flow 는 구독 시 SDK 리스너를 등록하고 취소 시 **같은 리스너로** 해제한다(callbackFlow + awaitClose, 규칙 3).
 * 미지원 항목은 예외 대신 빈 Flow / null 로 끝난다(규칙 4).
 */
interface VehicleAdapter {
    /** CarInfo 차량 ID. 없으면 null */
    suspend fun vehicleId(): String?

    /** Fuel.registerFuelPercent — 0~100, 미지원이면 null */
    fun fuelPercent(): Flow<Int?>

    /** EvBattery.registerEvBatteryStateOfCharge — 0~100, 미지원이면 null */
    fun evStateOfCharge(): Flow<Int?>

    /** Brake.registerBrakePedalStatus — 밟힘 여부 */
    fun brakePedal(): Flow<Boolean>

    /** TurnSignal.registerTurnSignalState */
    fun turnSignal(): Flow<TurnSignalState>

    /** Safety.registerSeatAirbagDeployed */
    fun airbagDeployed(): Flow<Boolean>

    /** AAOS CarPropertyManager PERF_VEHICLE_SPEED (km/h). 실패 시 empty */
    fun speedKph(): Flow<Float?>

    /** AAOS GEAR_SELECTION. 실패 시 empty */
    fun gear(): Flow<Gear?>

    /** 각 check*Capabilities 결과 요약 — 앱 시작 시 한 번 호출해 로그·디버그 화면에 표로 출력(docs/09 §3) */
    suspend fun capabilities(): VehicleCapabilities
}
