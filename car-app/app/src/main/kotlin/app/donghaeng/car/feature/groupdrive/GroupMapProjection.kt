package app.donghaeng.car.feature.groupdrive

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max

/** 화면 위 한 점(캔버스 좌표, px) */
data class MapPoint(val x: Float, val y: Float)

/**
 * 경량 지도 투영 (T25, docs/05 §2 "Compose Canvas 경량 지도"): 기준점(선두차) 중심의 등장방형(equirectangular) 근사 — 수 km 범위에서 충분하다.
 * 북쪽이 위. [metersPerPx] 는 모든 멤버가 화면 안에 들어오도록 자동으로 정한다(최소 [minRadiusM] m 반경).
 */
class GroupMapProjection(
    val centerLat: Double,
    val centerLon: Double,
    val widthPx: Float,
    val heightPx: Float,
    val metersPerPx: Float,
) {
    private val cosLat = cos(Math.toRadians(centerLat))

    /** 기준점 기준 동쪽(+x m)·북쪽(+y m) 거리 */
    fun offsetM(lat: Double, lon: Double): Pair<Double, Double> =
        Pair((lon - centerLon) * M_PER_DEG_LON_EQ * cosLat, (lat - centerLat) * M_PER_DEG_LAT)

    fun toPoint(lat: Double, lon: Double): MapPoint {
        val (ex, ny) = offsetM(lat, lon)
        return MapPoint((widthPx / 2 + ex / metersPerPx).toFloat(), (heightPx / 2 - ny / metersPerPx).toFloat())
    }

    /** 점이 화면 안(여백 [marginPx] 포함)에 있는가 */
    fun contains(p: MapPoint, marginPx: Float = 0f): Boolean =
        p.x >= -marginPx && p.x <= widthPx + marginPx && p.y >= -marginPx && p.y <= heightPx + marginPx

    /** 화면 밖 점을 가리키는 가장자리 위치(중심에서 그 방향으로 뻗어 테두리 안쪽 [insetPx] 에 닿는 점) */
    fun edgePointToward(p: MapPoint, insetPx: Float): MapPoint {
        val cx = widthPx / 2
        val cy = heightPx / 2
        val dx = p.x - cx
        val dy = p.y - cy
        if (dx == 0f && dy == 0f) return MapPoint(cx, cy)
        val halfW = widthPx / 2 - insetPx
        val halfH = heightPx / 2 - insetPx
        val t = minOf(if (dx != 0f) halfW / kotlin.math.abs(dx) else Float.MAX_VALUE, if (dy != 0f) halfH / kotlin.math.abs(dy) else Float.MAX_VALUE)
        return MapPoint(cx + dx * t, cy + dy * t)
    }

    /** 거리 링에 쓸 "보기 좋은" 간격(m): 화면 반지름의 약 1/3 ~ 1/2 */
    fun ringStepM(): Int {
        val radiusM = minOf(widthPx, heightPx) / 2 * metersPerPx
        val target = radiusM / 2.5
        return NICE_STEPS_M.firstOrNull { it >= target } ?: NICE_STEPS_M.last()
    }

    companion object {
        const val M_PER_DEG_LAT = 110_574.0
        const val M_PER_DEG_LON_EQ = 111_320.0
        private val NICE_STEPS_M = listOf(50, 100, 200, 250, 500, 1_000, 2_000, 5_000, 10_000, 20_000, 50_000)

        /**
         * 모든 [points] 가 들어오도록 축척을 정한다. [fillRatio] 만큼만 쓰고 나머지는 여백.
         * @param minRadiusM 멤버가 한 대뿐이거나 모여 있을 때의 최소 표시 반경(m)
         */
        fun fit(
            centerLat: Double,
            centerLon: Double,
            points: List<Pair<Double, Double>>,
            widthPx: Float,
            heightPx: Float,
            minRadiusM: Double = 300.0,
            fillRatio: Float = 0.78f,
        ): GroupMapProjection {
            val cosLat = cos(Math.toRadians(centerLat))
            var maxDist = minRadiusM
            points.forEach { (lat, lon) ->
                val ex = (lon - centerLon) * M_PER_DEG_LON_EQ * cosLat
                val ny = (lat - centerLat) * M_PER_DEG_LAT
                maxDist = max(maxDist, hypot(ex, ny))
            }
            val radiusPx = max(1f, minOf(widthPx, heightPx) / 2 * fillRatio)
            return GroupMapProjection(centerLat, centerLon, widthPx, heightPx, (maxDist / radiusPx).toFloat())
        }
    }
}
