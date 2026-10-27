package app.donghaeng.car.domain.model

import app.donghaeng.car.core.common.CoordinateMasking
import app.donghaeng.protocol.LocSource

/**
 * 위치 한 점 (docs/05 §3 `Fix(lat, lon, heading, accuracy, ts, source)`).
 *
 * @param lat      WGS84 위도
 * @param lon      WGS84 경도
 * @param heading  진행 방위각(도, 0~360). 모르면 null
 * @param accuracy 수평 정확도(m). 모르면 null
 * @param ts       측정 시각(epoch ms)
 * @param source   위치 출처 — protocol 의 [LocSource] 를 그대로 쓴다(텔레메트리에 그대로 실린다)
 */
data class Fix(
    val lat: Double,
    val lon: Double,
    val heading: Float?,
    val accuracy: Float?,
    val ts: Long,
    val source: LocSource,
) {
    /** 로그용 문자열 — 좌표는 마스킹된다(CLAUDE.md 규칙 8). toString() 대신 이것을 쓴다. */
    fun toLogString(): String =
        "Fix${CoordinateMasking.mask(lat, lon)} heading=${heading ?: "?"} acc=${accuracy ?: "?"} src=$source ts=$ts"
}
