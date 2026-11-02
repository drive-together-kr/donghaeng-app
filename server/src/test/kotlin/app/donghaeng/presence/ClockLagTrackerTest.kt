package app.donghaeng.presence

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 계약 4 — 차량별 '봉투 ts → 서버 수신' 최소 지연 기준값 */
class ClockLagTrackerTest {

    private val t0 = 1_793_000_000_000L - 1_793_000_000_000L % ClockLagTracker.BUCKET_MS // 칸의 시작

    @Test
    fun `keeps the minimum delay so a queued flush after an outage does not raise the baseline`() {
        val tracker = ClockLagTracker()
        // 차 시계가 90 s 늦다: 정상 텔레메트리는 지연 90.1~90.3 s
        tracker.record("veh_A", sentAtMs = t0 - 90_200, receivedAtMs = t0)
        tracker.record("veh_A", sentAtMs = t0 + 1_000 - 90_100, receivedAtMs = t0 + 1_000)
        assertEquals(90_100, tracker.lagMs("veh_A", t0 + 2_000))
        // 4분 끊겼다가 쌓였던 표본이 한꺼번에(지연 4분+) — 기준값은 그대로
        tracker.record("veh_A", sentAtMs = t0 + 2_000 - 90_000, receivedAtMs = t0 + 242_000)
        assertEquals(90_100, tracker.lagMs("veh_A", t0 + 242_000))
    }

    @Test
    fun `the previous 10-minute bucket is kept across one boundary and forgotten after two`() {
        val tracker = ClockLagTracker()
        tracker.record("veh_A", sentAtMs = t0 + 500_000 - 200, receivedAtMs = t0 + 500_000)   // 칸 0, 200 ms
        val nextBucket = t0 + ClockLagTracker.BUCKET_MS + 30_000
        tracker.record("veh_A", sentAtMs = nextBucket - 240_000, receivedAtMs = nextBucket)       // 칸 1, 끊긴 뒤 밀린 표본 4분
        assertEquals(200, tracker.lagMs("veh_A", nextBucket), "경계를 넘어도 끊기기 전 최솟값이 남는다")
        assertEquals(240_000, tracker.lagMs("veh_A", nextBucket + ClockLagTracker.BUCKET_MS), "다음 칸에서는 직전 칸만")
        assertNull(tracker.lagMs("veh_A", nextBucket + 2 * ClockLagTracker.BUCKET_MS), "너무 오래됐으면 모름")
        assertNull(tracker.lagMs("veh_Z", t0))
    }

    @Test
    fun `a clock sync drops the baseline at once`() {
        val tracker = ClockLagTracker()
        tracker.record("veh_A", sentAtMs = t0 - 90_000, receivedAtMs = t0)
        // ClockSync 가 맞은 뒤 봉투 ts = 서버 시계 → 지연 150 ms
        tracker.record("veh_A", sentAtMs = t0 + 1_000 - 150, receivedAtMs = t0 + 1_000)
        assertEquals(150, tracker.lagMs("veh_A", t0 + 1_000))
    }
}
