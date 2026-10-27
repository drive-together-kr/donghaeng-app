package app.donghaeng.car.domain.location

import app.donghaeng.car.core.common.Geo
import app.donghaeng.car.domain.model.Fix

/**
 * 연속 위치로 속도 계산 (docs/05 §3.2: Car API 실패 시 Haversine / Δt, 3샘플 이동평균).
 *
 * - Δt < [minDtMs]: 너무 촘촘한 샘플은 건너뛰고 이전 기준점을 유지한다(0 으로 나누기·GPS 지터 방지).
 * - Δt > [maxDtMs]: 끊겼다 다시 온 것 → 창을 비우고 새로 시작.
 * - [maxSpeedKph] 초과: 위치 튐으로 보고 버린다.
 * - 소스가 바뀌면 호출 쪽에서 [reset] 한다(소스마다 좌표 기준이 조금씩 달라 가짜 속도가 생긴다).
 */
class SpeedEstimator(
    private val window: Int = 3,
    private val minDtMs: Long = 500L,
    private val maxDtMs: Long = 10_000L,
    private val maxSpeedKph: Float = 250f,
) {
    private var anchor: Fix? = null
    private val samples = ArrayDeque<Float>()

    /** 현재 추정 속도(km/h). 샘플이 없으면 null */
    val speedKph: Float?
        get() = if (samples.isEmpty()) null else (samples.sum() / samples.size)

    /** 새 Fix 반영 후 현재 추정 속도 */
    fun push(fix: Fix): Float? {
        val prev = anchor
        if (prev == null) {
            anchor = fix
            return speedKph
        }
        val dt = fix.ts - prev.ts
        when {
            dt < minDtMs -> return speedKph           // 기준점 유지
            dt > maxDtMs -> {
                samples.clear()
                anchor = fix
                return null
            }
        }
        anchor = fix
        val meters = Geo.distanceM(prev.lat, prev.lon, fix.lat, fix.lon)
        val kph = (meters / (dt / 1000.0) * 3.6).toFloat()
        if (kph > maxSpeedKph) return speedKph        // 위치 튐
        samples.addLast(kph)
        while (samples.size > window) samples.removeFirst()
        return speedKph
    }

    fun reset() {
        anchor = null
        samples.clear()
    }
}
