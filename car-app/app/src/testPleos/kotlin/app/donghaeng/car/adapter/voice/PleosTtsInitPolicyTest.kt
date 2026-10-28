package app.donghaeng.car.adapter.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pleos TTS initialize 시간 초과·다시 시도 규칙 — 늦음은 표시만, 한 시도 상한 뒤 놓고 5 s → 20 s 뒤 다시, 3번이면 불가 확정 */
class PleosTtsInitPolicyTest {

    @Test
    fun `slow mark comes before giving up an attempt`() {
        assertEquals(15_000L, PleosTtsInitPolicy.SLOW_MS)
        assertTrue("늦은 onSuccess 를 받을 틈이 있어야 한다", PleosTtsInitPolicy.GIVE_UP_MS > PleosTtsInitPolicy.SLOW_MS)
    }

    @Test
    fun `two retries with backoff, then final`() {
        assertEquals(5_000L, PleosTtsInitPolicy.retryDelayMs(1))
        assertEquals(20_000L, PleosTtsInitPolicy.retryDelayMs(2))
        assertNull("세 번째 시도까지 실패하면 불가 확정", PleosTtsInitPolicy.retryDelayMs(PleosTtsInitPolicy.MAX_ATTEMPTS))
        assertNull(PleosTtsInitPolicy.retryDelayMs(9))
    }
}
