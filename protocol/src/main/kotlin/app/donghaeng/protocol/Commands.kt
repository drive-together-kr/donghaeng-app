package app.donghaeng.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

/**
 * CMD 종류 (서버 → 차량, `dh/v1/vehicle/{vid}/cmd`) — docs/03 §5.5.
 * 봉투의 [Envelope.type] 에는 이 enum 의 이름이 그대로 들어간다.
 */
@Serializable
enum class CmdType {
    /** 그룹 합류 완료 — [GroupJoined] */
    GROUP_JOINED,

    /** 선두 경로 따라가기 제안 — [RouteFollowProposal] */
    ROUTE_FOLLOW_PROPOSAL,

    /** 휴게소 제안 — [RestStopProposal] */
    REST_STOP_PROPOSAL,

    /** 그룹 종료 — [GroupEnded] */
    GROUP_ENDED,

    /** 차량 토큰 폐기(페어링 해제) — [TokenRevoked] */
    TOKEN_REVOKED,
}

/**
 * `GROUP_JOINED` 본문. 차량이 구독해야 할 그룹 토픽을 함께 내려준다.
 */
@Serializable
data class GroupJoined(
    val groupId: String,
    val name: String,
    val leaderVehicleId: String,
    val destination: Place,
    val topics: GroupTopics,
)

/** 그룹 토픽 쌍 (docs/03 §2.1 `mqttTopics` 와 동일 형태) */
@Serializable
data class GroupTopics(
    val state: String,
    val event: String,
)

/**
 * `ROUTE_FOLLOW_PROPOSAL` 본문. 수락 시 차량 앱이 내비에 목적지·경유지를 반영하고 [Ack] 를 보낸다.
 */
@Serializable
data class RouteFollowProposal(
    val proposalId: String,
    val destination: Place,
    val waypoints: List<Place> = emptyList(),
    val speech: String,
)

/**
 * `REST_STOP_PROPOSAL` 본문. 60초 투표 후 과반 수락 시 경유지 추가 제안이 이어진다.
 *
 * @param reason 제안 사유 코드(예: LOW_ENERGY, LONG_DRIVE, PRESET)
 */
@Serializable
data class RestStopProposal(
    val proposalId: String,
    val poi: RestAreaPoi,
    val reason: String,
    val speech: String,
)

/** 휴게소 POI (docs/03 §3 `/poi/rest-areas` 응답 항목과 동일 형태) */
@Serializable
data class RestAreaPoi(
    val poiId: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val distanceKm: Double,
    val hasEvCharger: Boolean = false,
)

/** `GROUP_ENDED` 본문. [reason] = `LEADER_ENDED`(방장 종료) | `ALL_LEFT`(마지막 멤버가 나감). `ALL_ARRIVED` 는 예약값(아직 발행하지 않음, docs/03 §5.4) */
@Serializable
data class GroupEnded(
    val groupId: String,
    val reason: String,
)

/** `TOKEN_REVOKED` 본문 — 빈 객체 `{}` */
@Serializable
object TokenRevoked

/**
 * CMD 종류 → 본문 직렬화기 레지스트리 ([vehicleEventSerializer] 와 같은 용도).
 */
fun cmdSerializer(type: CmdType): KSerializer<out Any> = when (type) {
    CmdType.GROUP_JOINED -> GroupJoined.serializer()
    CmdType.ROUTE_FOLLOW_PROPOSAL -> RouteFollowProposal.serializer()
    CmdType.REST_STOP_PROPOSAL -> RestStopProposal.serializer()
    CmdType.GROUP_ENDED -> GroupEnded.serializer()
    CmdType.TOKEN_REVOKED -> TokenRevoked.serializer()
}
