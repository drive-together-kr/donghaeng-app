package app.donghaeng.car.domain.latency

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** 최근접 순위법: P_p = 정렬 표본의 ⌈p·n/100⌉ 번째(1부터) — tools/demo/test_latency.py 와 같은 기대값 */
class LatencyPercentilesTest {

    @Test
    fun `n=1 - P50 and P95 are the only sample`() {
        val s = LatencyPercentiles.summarize(listOf(42L))!!
        assertEquals(LatencySummary(n = 1, p50 = 42, p95 = 42, min = 42, max = 42), s)
    }

    @Test
    fun `n=20 - P95 is the 19th smallest and P50 the 10th`() {
        // 1..20 을 섞어서 넣는다 — 안에서 정렬한다
        val samples = (1L..20L).shuffled(java.util.Random(7))
        val s = LatencyPercentiles.summarize(samples)!!
        assertEquals(20, s.n)
        assertEquals(19L, s.p95)   // ⌈0.95·20⌉ = 19 → 19번째
        assertEquals(10L, s.p50)   // ⌈0.50·20⌉ = 10
        assertEquals(1L, s.min)
        assertEquals(20L, s.max)
    }

    @Test
    fun `small n - no interpolation, values are real samples`() {
        // n=2: P50 = 작은 쪽(⌈1⌉ = 1), P95 = 큰 쪽(⌈1.9⌉ = 2)
        val two = LatencyPercentiles.summarize(listOf(300L, 100L))!!
        assertEquals(100L, two.p50); assertEquals(300L, two.p95)
        // n=21: P95 = ⌈19.95⌉ = 20번째, P50 = ⌈10.5⌉ = 11번째
        val s21 = LatencyPercentiles.summarize((1L..21L).toList())!!
        assertEquals(20L, s21.p95); assertEquals(11L, s21.p50)
        // n=100: 정수 산술 — P95 = 95번째 정확히(부동소수 반올림으로 96 이 되지 않는다)
        val s100 = LatencyPercentiles.summarize((1L..100L).toList())!!
        assertEquals(95L, s100.p95); assertEquals(50L, s100.p50)
    }

    @Test
    fun `empty is null and bad arguments are rejected`() {
        assertNull(LatencyPercentiles.summarize(emptyList()))
        assertThrows(IllegalArgumentException::class.java) { LatencyPercentiles.nearestRank(emptyList(), 50) }
        assertThrows(IllegalArgumentException::class.java) { LatencyPercentiles.nearestRank(listOf(1L), 0) }
        assertEquals(7L, LatencyPercentiles.nearestRank(listOf(1L, 7L), 100))
    }
}
