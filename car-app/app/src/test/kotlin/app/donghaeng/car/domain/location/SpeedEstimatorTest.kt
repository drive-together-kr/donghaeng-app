package app.donghaeng.car.domain.location

import app.donghaeng.car.domain.model.Fix
import app.donghaeng.protocol.LocSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeedEstimatorTest {

    /** 북쪽으로 초당 약 16.7 m (≈ 60 km/h) */
    private fun moving(n: Int, stepDeg: Double = 0.00015, dtMs: Long = 1_000L): List<Fix> =
        (0 until n).map { i -> Fix(lat = 37.0 + i * stepDeg, lon = 127.0, heading = 0f, accuracy = 5f, ts = i * dtMs, source = LocSource.NAVI) }

    @Test
    fun `needs two samples then averages up to three`() {
        val est = SpeedEstimator()
        val fixes = moving(5)
        assertNull(est.push(fixes[0]))
        val first = est.push(fixes[1])!!
        assertEquals(60.0f, first, 1.5f)
        fixes.drop(2).forEach { est.push(it) }
        assertEquals(60.0f, est.speedKph!!, 1.5f)
    }

    @Test
    fun `stationary fixes give zero`() {
        val est = SpeedEstimator()
        repeat(4) { i -> est.push(Fix(37.0, 127.0, null, null, i * 1_000L, LocSource.ANDROID)) }
        assertEquals(0f, est.speedKph!!, 0.01f)
    }

    @Test
    fun `too dense samples keep the previous anchor`() {
        val est = SpeedEstimator()
        est.push(Fix(37.0, 127.0, null, null, 0L, LocSource.NAVI))
        assertNull(est.push(Fix(37.0001, 127.0, null, null, 100L, LocSource.NAVI)))   // 100 ms → 무시
        val v = est.push(Fix(37.00015, 127.0, null, null, 1_000L, LocSource.NAVI))!!  // 기준점은 t=0
        assertEquals(60.0f, v, 1.5f)
    }

    @Test
    fun `a long gap resets the window`() {
        val est = SpeedEstimator()
        moving(4).forEach { est.push(it) }
        assertNull(est.push(Fix(37.1, 127.0, null, null, 3_000L + 20_000L, LocSource.NAVI)))
        assertNull(est.speedKph)
    }

    @Test
    fun `implausible jumps are ignored`() {
        val est = SpeedEstimator()
        moving(3).forEach { est.push(it) }
        val before = est.speedKph!!
        val after = est.push(Fix(38.0, 127.0, null, null, 3_000L, LocSource.NAVI))!!   // 1초에 111 km → 튐
        assertEquals(before, after, 0.01f)
    }

    @Test
    fun `reset forgets everything`() {
        val est = SpeedEstimator()
        moving(3).forEach { est.push(it) }
        est.reset()
        assertNull(est.speedKph)
        assertNull(est.push(moving(1).first()))
    }
}
