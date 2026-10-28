package app.donghaeng.car.domain.latency

/**
 * 누적 표본 요약 (진단 `latency_dump`·`net_rtt`, docs/05 §3.4). 단위는 표본과 같다(메시지 지연은 ms, 왕복 프로브는 µs).
 *
 * @param n 표본 수(1 이상 — 표본이 없으면 요약 자체가 null)
 */
data class LatencySummary(val n: Int, val p50: Long, val p95: Long, val min: Long, val max: Long)

/**
 * 백분위 — **최근접 순위법(nearest-rank)**. 오름차순 정렬한 표본 x₁ ≤ … ≤ xₙ 에서
 * `P_p = x_r`, `r = ⌈p·n / 100⌉`(1 ≤ r ≤ n). 보간하지 않으므로 값은 언제나 실제 표본 중 하나다.
 *
 * - n = 1 이면 P50 = P95 = 그 표본.
 * - n = 20 이면 P95 = 19번째(r = ⌈19⌉), P50 = 10번째. n = 2 의 P50 은 작은 쪽(r = 1).
 * - 정수 산술(`(p·n + 99) / 100`)로 계산해 부동소수 반올림이 순위를 바꾸지 않는다.
 * - `tools/demo/latency.py` 의 `percentile()` 과 같은 정의다(local·cloud 비교표가 같은 방식으로 계산되게).
 */
object LatencyPercentiles {

    /** [sorted] 는 오름차순·비어 있지 않아야 한다. [percent] 1..100 */
    fun nearestRank(sorted: List<Long>, percent: Int): Long {
        require(sorted.isNotEmpty()) { "표본이 없다" }
        require(percent in 1..100) { "percent=$percent" }
        val rank = ((percent.toLong() * sorted.size + 99) / 100).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    /** 표본이 없으면 null. 순서는 상관없다(안에서 정렬한다) */
    fun summarize(samples: Collection<Long>): LatencySummary? {
        if (samples.isEmpty()) return null
        val sorted = samples.sorted()
        return LatencySummary(
            n = sorted.size,
            p50 = nearestRank(sorted, 50),
            p95 = nearestRank(sorted, 95),
            min = sorted.first(),
            max = sorted.last(),
        )
    }
}
