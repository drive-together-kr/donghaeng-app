package app.donghaeng.car.feature.drive

import org.junit.Assert.assertEquals
import org.junit.Test

/** 연속 주행 시간 — 주행 구간 합산, 짧은 정차는 유지, 15분 이상 정차하면 0 */
class ContinuousDriveTimerTest {
    private val min = 60_000L

    @Test
    fun `never driven is zero, even after a long parked time`() {
        val t = ContinuousDriveTimer()
        t.onDriving(false, 0)
        assertEquals(0L, t.drivenMs(0))
        assertEquals(0L, t.drivenMs(18 * 60 * min))
    }

    @Test
    fun `driving accumulates live and short stops keep the total without adding`() {
        val t = ContinuousDriveTimer()
        t.onDriving(true, 0)
        assertEquals(40 * min, t.drivenMs(40 * min))
        t.onDriving(false, 60 * min)                 // 신호 대기·잠깐 멈춤 5분
        assertEquals(60 * min, t.drivenMs(65 * min))
        t.onDriving(true, 65 * min)
        assertEquals(120 * min, t.drivenMs(125 * min))
        t.onDriving(true, 70 * min)                  // 같은 값이 다시 와도 구간을 새로 시작하지 않는다
        assertEquals(120 * min, t.drivenMs(125 * min))
    }

    @Test
    fun `a rest of fifteen minutes or more resets to zero`() {
        val t = ContinuousDriveTimer()
        t.onDriving(true, 0)
        t.onDriving(false, 130 * min)
        assertEquals(130 * min, t.drivenMs(144 * min))           // 14분 쉼 → 그대로
        assertEquals(0L, t.drivenMs(145 * min))                  // 15분 쉼 → 0
        t.onDriving(true, 150 * min)
        assertEquals(10 * min, t.drivenMs(160 * min))            // 다시 0 부터
    }

    @Test
    fun `a rest shorter than fifteen minutes does not reset when driving resumes`() {
        val t = ContinuousDriveTimer()
        t.onDriving(true, 0)
        t.onDriving(false, 100 * min)
        t.onDriving(true, 114 * min)
        assertEquals(106 * min, t.drivenMs(120 * min))
    }
}
