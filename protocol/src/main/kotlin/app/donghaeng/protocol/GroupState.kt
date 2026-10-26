package app.donghaeng.protocol

import kotlinx.serialization.Serializable

/**
 * 그룹 STATE 본문 (서버 → 그룹, 2s 주기, retained) — docs/03 §5.3.
 */
@Serializable
data class GroupState(
    val groupId: String,
    val leaderVehicleId: String,
    val members: List<MemberState>,
)

/**
 * 스냅샷 안의 멤버 한 대 상태.
 *
 * @param color              멤버 표시 색(`#RRGGBB`)
 * @param online             MQTT 접속 여부(presence 기준)
 * @param ageMs              마지막 위치 수신 후 경과(ms). 위치가 없으면 null
 * @param distanceToLeaderM  선두차까지 거리(m). 선두차 자신은 0
 */
@Serializable
data class MemberState(
    val vehicleId: String,
    val nickname: String,
    val color: String,
    val online: Boolean,
    val lat: Double?,
    val lon: Double?,
    val heading: Float?,
    val speedKph: Float?,
    val ageMs: Long?,
    val eta: Eta?,
    val energy: Energy?,
    val distanceToLeaderM: Int?,
    val status: MemberStatus,
)

/** 멤버 주행 상태 */
@Serializable
enum class MemberStatus { DRIVING, STOPPED, OFFLINE, ARRIVED }
