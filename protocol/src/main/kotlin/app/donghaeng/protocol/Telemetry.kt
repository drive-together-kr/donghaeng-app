package app.donghaeng.protocol

import kotlinx.serialization.Serializable

/**
 * TELEMETRY 본문 (차량 → 서버, 1Hz) — docs/03 §5.1, docs/04 §4.
 *
 * @param lat        WGS84 위도
 * @param lon        WGS84 경도
 * @param heading    진행 방위각(도, 0~360). 모르면 null
 * @param speedKph   속도(km/h). 위치 변화로 계산하거나 Car API 값. 모르면 null
 * @param locSource  위치 출처
 * @param gear       기어 상태 문자열("D", "P" 등). 미지원 차량이면 null
 * @param eta        목적지 ETA. 30초마다 또는 변화 시에만 포함
 * @param energy     연료/배터리 잔량. 1%p 변화 시에만 포함
 */
@Serializable
data class Telemetry(
    val lat: Double,
    val lon: Double,
    val heading: Float?,
    val speedKph: Float?,
    val locSource: LocSource,
    val gear: String? = null,
    val eta: Eta? = null,
    val energy: Energy? = null,
)

/**
 * 목적지 도착 예상 정보.
 *
 * @param destTs  도착 예상 시각(epoch ms)
 * @param remainM 남은 거리(m)
 * @param remainS 남은 시간(s)
 */
@Serializable
data class Eta(
    val destTs: Long?,
    val remainM: Int?,
    val remainS: Int?,
)

/**
 * 에너지 잔량. 내연기관은 [fuelPct], 전기차는 [socPct] 만 채운다.
 */
@Serializable
data class Energy(
    val fuelPct: Int? = null,
    val socPct: Int? = null,
)

/** 위치 출처 우선순위: NAVI → FUSED → ANDROID → FAKE (CLAUDE.md 규칙 5) */
@Serializable
enum class LocSource { NAVI, FUSED, ANDROID, FAKE }
