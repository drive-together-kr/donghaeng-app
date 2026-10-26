package app.donghaeng.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

/**
 * 차량 EVENT 종류 (차량 → 서버, `dh/v1/vehicle/{vid}/event`) — docs/03 §5.2.
 * 봉투의 [Envelope.type] 에는 이 enum 의 이름이 그대로 들어간다.
 */
@Serializable
enum class VehicleEventType {
    /** 잔량 15% 이하 최초 진입, 이후 5%p 마다 — [LowEnergy] */
    LOW_ENERGY,

    /** 브레이크 ON 상태에서 1초 내 감속 4 m/s2 이상 — [HardBrake] */
    HARD_BRAKE,

    /** 비상등 점멸 판정 — [HazardOn] */
    HAZARD_ON,

    /** 에어백 전개 콜백 — [AirbagDeployed] */
    AIRBAG_DEPLOYED,

    /** 선두차 목적지·경유지 변경 — [RouteChanged] */
    ROUTE_CHANGED,

    /** 경유지 도착 — [WaypointArrived] */
    WAYPOINT_ARRIVED,

    /** 목적지 도착 — [DestinationArrived] */
    DESTINATION_ARRIVED,

    /** 속도 0 이 60초 지속 — [Stopped] */
    STOPPED,

    /** 정차 후 재출발 — [Resumed] */
    RESUMED,

    /** 정차 중 프리셋 메시지 버튼 — [PresetMessage] */
    PRESET_MESSAGE,
}

/** [LowEnergy.kind] — 연료차 / 전기차 구분 */
@Serializable
enum class EnergyKind { FUEL, EV }

/** `LOW_ENERGY` 본문. [pct] 는 남은 잔량(%) */
@Serializable
data class LowEnergy(
    val kind: EnergyKind,
    val pct: Int,
)

/** `HARD_BRAKE` 본문. [decelMps2] 감속도(m/s2), [speedBeforeKph] 제동 직전 속도(km/h) */
@Serializable
data class HardBrake(
    val decelMps2: Double,
    val speedBeforeKph: Double,
)

/** `HAZARD_ON` 본문 — 빈 객체 `{}` */
@Serializable
object HazardOn

/** `AIRBAG_DEPLOYED` 본문 — 빈 객체 `{}` */
@Serializable
object AirbagDeployed

/** `ROUTE_CHANGED` 본문. 선두차의 새 목적지와 경유지 목록 */
@Serializable
data class RouteChanged(
    val destination: Place,
    val waypoints: List<Place> = emptyList(),
)

/** `WAYPOINT_ARRIVED` 본문 */
@Serializable
data class WaypointArrived(
    val name: String,
    val lat: Double,
    val lon: Double,
)

/** `DESTINATION_ARRIVED` 본문 */
@Serializable
data class DestinationArrived(
    val name: String,
)

/** `STOPPED` 본문. [durationS] 는 정차 판정까지 걸린 시간(s), 없으면 null */
@Serializable
data class Stopped(
    val durationS: Int? = null,
)

/** `RESUMED` 본문. [durationS] 는 총 정차 시간(s), 없으면 null */
@Serializable
data class Resumed(
    val durationS: Int? = null,
)

/** `PRESET_MESSAGE` 본문. [presetId] 는 차량 앱 프리셋 문구 ID */
@Serializable
data class PresetMessage(
    val presetId: String,
)

/**
 * 차량 이벤트 종류 → 본문 직렬화기 레지스트리.
 *
 * 라우터가 [DhJson.peek] 로 봉투의 `type` 을 읽은 뒤 알맞은 직렬화기로 `data` 를 디코딩할 때 쓴다.
 *
 * ```kotlin
 * val head = DhJson.peek(raw)
 * val type = VehicleEventType.valueOf(head.type)
 * val payload = DhJson.json.decodeFromJsonElement(vehicleEventSerializer(type), head.data)
 * ```
 */
fun vehicleEventSerializer(type: VehicleEventType): KSerializer<out Any> = when (type) {
    VehicleEventType.LOW_ENERGY -> LowEnergy.serializer()
    VehicleEventType.HARD_BRAKE -> HardBrake.serializer()
    VehicleEventType.HAZARD_ON -> HazardOn.serializer()
    VehicleEventType.AIRBAG_DEPLOYED -> AirbagDeployed.serializer()
    VehicleEventType.ROUTE_CHANGED -> RouteChanged.serializer()
    VehicleEventType.WAYPOINT_ARRIVED -> WaypointArrived.serializer()
    VehicleEventType.DESTINATION_ARRIVED -> DestinationArrived.serializer()
    VehicleEventType.STOPPED -> Stopped.serializer()
    VehicleEventType.RESUMED -> Resumed.serializer()
    VehicleEventType.PRESET_MESSAGE -> PresetMessage.serializer()
}
