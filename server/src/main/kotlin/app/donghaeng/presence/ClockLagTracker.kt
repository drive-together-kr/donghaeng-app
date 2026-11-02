package app.donghaeng.presence

import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * 차량별 '봉투 ts → 서버 수신' 지연의 기준값 (계약 4, C017·C099).
 *
 * 텔레메트리(1 Hz)마다 `수신 − 봉투 ts` 표본을 넣으면 최근 10~20분(10분 칸 두 개)의 **최솟값**을 들고 있는다 = 그 차 시계와 서버 시계의 차이 + 최소 망 지연.
 * - ClockSync 가 맞은 차(봉투 ts = 서버 시계)는 수십~수백 ms.
 * - 시계가 늦은데 동기화가 안 된 차는 그만큼 큰 값 → 위험 이벤트 늦음 판정에서 빼서 **새 이벤트를 늦다고 오판하지 않는다**.
 * - 망이 끊긴 동안 쌓였다가 한꺼번에 오는 표본은 지연이 커서 최솟값을 바꾸지 못한다(끊기기 전의 작은 값이 이전 칸에 남는다) → 쌓였던 이벤트는 늦다고 판정된다.
 *   차량 MQTT 세션은 약 330 s 뒤 사라지므로 쌓인 표본은 10분 칸 경계를 한 번 넘을 뿐이다.
 * 메모리 안의 값이라 서버가 다시 시작하면 비어 있다(그동안은 보정 없이 판정).
 */
@Component
class ClockLagTracker {

    /** [bucket] = 수신 시각 / [BUCKET_MS] 칸, 그 칸의 최솟값과 바로 앞 칸의 최솟값 */
    private data class Lag(val bucket: Long, val currentMin: Long, val previousMin: Long?)

    private val lags = ConcurrentHashMap<String, Lag>()

    /** 표본 하나: [receivedAtMs] = 서버 수신 시각, [sentAtMs] = 봉투 ts */
    fun record(vehicleId: String, sentAtMs: Long, receivedAtMs: Long) {
        val sample = receivedAtMs - sentAtMs
        val bucket = Math.floorDiv(receivedAtMs, BUCKET_MS)
        lags.compute(vehicleId) { _, old ->
            when {
                old == null || bucket > old.bucket + 1 -> Lag(bucket, sample, null)
                bucket == old.bucket + 1 -> Lag(bucket, sample, old.currentMin)
                bucket == old.bucket -> old.copy(currentMin = minOf(old.currentMin, sample))
                else -> old.copy(previousMin = old.previousMin?.let { minOf(it, sample) }) // 시계가 거꾸로 — 무시에 가깝게
            }
        }
    }

    /** 최근 창의 최소 지연(ms, 음수 = 차 시계가 빠름). 표본이 없거나 너무 오래됐으면 null */
    fun lagMs(vehicleId: String, nowMs: Long): Long? {
        val lag = lags[vehicleId] ?: return null
        val bucket = Math.floorDiv(nowMs, BUCKET_MS)
        return when (lag.bucket) {
            bucket -> lag.previousMin?.let { minOf(it, lag.currentMin) } ?: lag.currentMin
            bucket - 1 -> lag.currentMin
            else -> null
        }
    }

    companion object {
        const val BUCKET_MS = 10 * 60_000L
    }
}
