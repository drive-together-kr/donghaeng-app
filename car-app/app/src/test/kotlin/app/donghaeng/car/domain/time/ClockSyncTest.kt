package app.donghaeng.car.domain.time

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ClockSyncTest {

    @Test
    fun `offset comes from the probe with the shortest round trip`() = runTest {
        // 로컬 시계: 호출마다 1 ms 씩 흐른다. 서버는 로컬보다 정확히 +57_600_000 ms(16시간) 앞서 있다
        var local = 1_000_000L
        val clock = { local++ }
        val skew = 57_600_000L
        var call = 0
        val rtts = listOf(40L, 6L, 30L)   // 두 번째 표본이 가장 빠르다
        val sync = ClockSync(
            fetchServerTimeMs = {
                val rtt = rtts[call++]
                local += rtt - 1          // 왕복에 rtt 만큼 걸린 것처럼
                (local - rtt / 2) + skew  // 서버는 왕복의 중간 시점에 응답했다
            },
            clock = clock, probes = 3,
        )
        assertFalse(sync.state.value.synced)
        assertEquals(local, sync.serverNow())        // 동기화 전엔 로컬 시계 그대로 (serverNow 가 clock() 을 한 번 소모해 local 이 1 늘어난다)

        assertTrue(sync.syncOnce())
        val s = sync.state.value
        assertTrue(s.synced)
        assertEquals(6L, s.rttMs)
        assertTrue("offset ≈ skew (±3 ms), was ${s.offsetMs}", kotlin.math.abs(s.offsetMs!! - skew) <= 3)
        assertEquals(3, s.samples)
        assertEquals(1, s.syncs)
        assertNull(s.lastError)
        val now = local
        assertTrue(kotlin.math.abs(sync.serverNow() - (now + skew)) <= 4)
        assertTrue(kotlin.math.abs(sync.toLocalMs(now + skew) - now) <= 4)
    }

    @Test
    fun `all probes failing leaves the clock unsynced and records the error`() = runTest {
        val sync = ClockSync(fetchServerTimeMs = { throw java.io.IOException("down") }, clock = { 5L }, probes = 2)
        assertFalse(sync.syncOnce())
        assertFalse(sync.state.value.synced)
        assertEquals("IOException", sync.state.value.lastError)
        assertEquals(0, sync.state.value.samples)
    }
}
