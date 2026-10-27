package app.donghaeng.car.domain.model

/** 기어 상태 (AAOS GEAR_SELECTION 매핑). 미지원·알 수 없음은 Flow 에서 null 로 흘린다. */
enum class Gear { P, R, N, D, UNKNOWN }

/**
 * 방향지시등 상태.
 * HAZARD 는 좌·우 동시 표시를 SDK 가 구분해 줄 때만 쓴다 — capability 결과로 지원 여부를 확인하고
 * 미지원이면 비상등 기능은 UI 에서 숨긴다(docs/05 §3.3, docs/10 R3).
 */
enum class TurnSignalState { OFF, LEFT, RIGHT, HAZARD }

/**
 * `checkXxxCapabilities` 결과 요약 (docs/05 §3, docs/09 §3 SDK 실측 체크리스트).
 * false 인 항목은 UI 에서 숨기고 로그만 남긴다 — 크래시 금지(CLAUDE.md 규칙 4).
 *
 * @param raw 각 capability 호출의 원문 요약(디버그 화면·로그용). 좌표·개인정보 없음.
 */
data class VehicleCapabilities(
    val vehicleId: Boolean = false,
    val fuelPercent: Boolean = false,
    val evStateOfCharge: Boolean = false,
    val brakePedal: Boolean = false,
    val turnSignal: Boolean = false,
    val hazard: Boolean = false,
    val airbag: Boolean = false,
    val speed: Boolean = false,
    val gear: Boolean = false,
    val raw: Map<String, String> = emptyMap(),
) {
    companion object {
        /** 아무것도 지원하지 않음 — SDK 가 없거나 초기화 실패 시 */
        val NONE = VehicleCapabilities()
    }
}
