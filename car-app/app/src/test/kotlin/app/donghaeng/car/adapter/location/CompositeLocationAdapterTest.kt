package app.donghaeng.car.adapter.location

import app.donghaeng.car.domain.model.Fix
import app.donghaeng.protocol.LocSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CompositeLocationAdapterTest {

    /** 테스트용 소스: 가용성 토글, 1Hz 발행 여부 토글, 발행 전 지연, 예외 */
    private class Scripted(
        override val source: LocSource,
        @Volatile var available: Boolean = true,
        @Volatile var emitting: Boolean = true,
        override val staleTimeoutMs: Long = 5_000L,
        private val failAfter: Int = -1,
    ) : LocationSource {
        var subscriptions = 0
        override suspend fun isAvailable() = available
        override fun locations(): Flow<Fix> = flow {
            subscriptions++
            var n = 0
            while (true) {
                if (emitting) {
                    if (failAfter in 0..n) throw IllegalStateException("$source broke")
                    emit(Fix(lat = 37.0 + n * 0.0001, lon = 127.0, heading = 0f, accuracy = 5f, ts = n * 1_000L, source = source))
                    n++
                }
                delay(1_000)
            }
        }
    }

    private fun fix(src: LocSource) = Fix(37.0, 127.0, 0f, 3f, 0L, src)

    @Test
    fun `falls through unavailable and silent sources to the first one that delivers`() = runTest {
        val fused = Scripted(LocSource.FUSED, available = false)
        val navi = Scripted(LocSource.NAVI, emitting = false)        // 가용하지만 Fix 없음
        val fake = Scripted(LocSource.FAKE)
        val chain = CompositeLocationAdapter(listOf(fused, navi, fake), firstFixTimeoutMs = 10_000, reprobeIntervalMs = 60_000)
        val got = mutableListOf<Fix>()
        val job = chain.locations().onEach { got += it }.launchIn(this)
        advanceTimeBy(9_999); runCurrent()
        assertEquals(LocSource.NAVI, chain.status.value.active)
        assertTrue(got.isEmpty())
        advanceTimeBy(1_001); runCurrent()
        assertEquals(LocSource.FAKE, chain.status.value.active)
        assertEquals(mapOf(LocSource.FUSED to false, LocSource.NAVI to true, LocSource.FAKE to true), chain.status.value.availability)
        advanceTimeBy(5_000); runCurrent()
        assertTrue(got.size >= 5)
        assertTrue(got.all { it.source == LocSource.FAKE })
        assertEquals("NAVI: no first fix", chain.status.value.lastStop)
        job.cancel()
    }

    @Test
    fun `switches back to a higher source when the probe sees it delivering, without a gap`() = runTest {
        val navi = Scripted(LocSource.NAVI, emitting = false)
        val fake = Scripted(LocSource.FAKE)
        val chain = CompositeLocationAdapter(listOf(navi, fake), firstFixTimeoutMs = 10_000, reprobeIntervalMs = 60_000)
        val got = mutableListOf<Fix>()
        val job = chain.locations().onEach { got += it }.launchIn(this)
        advanceTimeBy(10_001); runCurrent()
        assertEquals(LocSource.FAKE, chain.status.value.active)
        advanceTimeBy(30_000); runCurrent()
        navi.emitting = true                                   // t≈40s: 내비 위치 복구
        advanceTimeBy(45_000); runCurrent()                    // t≈85s: 60s 지나 탐색 → 전환
        assertEquals(LocSource.NAVI, chain.status.value.active)
        assertEquals(LocSource.NAVI, got.last().source)
        // 전환 전후로 Fake 가 끊기지 않았는지: 1초 간격 발행이 계속됐다(85초 중 Fake 70초 이상 + Navi 일부)
        assertTrue("got=${got.size}", got.size >= 75)
        assertTrue(got.count { it.source == LocSource.NAVI } >= 2)
        job.cancel()
    }

    @Test
    fun `a failing source is reported and the chain moves on`() = runTest {
        val navi = Scripted(LocSource.NAVI, failAfter = 3)
        val fake = Scripted(LocSource.FAKE)
        val chain = CompositeLocationAdapter(listOf(navi, fake), firstFixTimeoutMs = 10_000)
        val got = mutableListOf<Fix>()
        val job = chain.locations().onEach { got += it }.launchIn(this)
        advanceTimeBy(8_000); runCurrent()
        assertEquals(3, got.count { it.source == LocSource.NAVI })
        assertEquals(LocSource.FAKE, chain.status.value.active)
        assertTrue(chain.status.value.lastError!!.contains("NAVI broke"))
        assertTrue(chain.status.value.lastStop!!.startsWith("NAVI: failed"))
        job.cancel()
    }

    @Test
    fun `when every source is unavailable it idles and retries later`() = runTest {
        val navi = Scripted(LocSource.NAVI, available = false)
        val android = Scripted(LocSource.ANDROID, available = false)
        val chain = CompositeLocationAdapter(listOf(navi, android), retryDelayMs = 5_000)
        val got = mutableListOf<Fix>()
        val job = chain.locations().onEach { got += it }.launchIn(this)
        runCurrent()
        assertNull(chain.status.value.active)
        assertEquals("all unavailable", chain.status.value.lastStop)
        android.available = true
        advanceTimeBy(5_001); runCurrent()
        assertEquals(LocSource.ANDROID, chain.status.value.active)
        advanceTimeBy(2_000); runCurrent()
        assertTrue(got.isNotEmpty())
        job.cancel()
    }

    @Test
    fun `stale source is dropped after its own timeout`() = runTest {
        val android = Scripted(LocSource.ANDROID, staleTimeoutMs = 15_000)
        val fake = Scripted(LocSource.FAKE)
        val chain = CompositeLocationAdapter(listOf(android, fake), firstFixTimeoutMs = 10_000)
        val got = mutableListOf<Fix>()
        val job = chain.locations().onEach { got += it }.launchIn(this)
        advanceTimeBy(3_000); runCurrent()
        android.emitting = false                               // GPS 끊김
        advanceTimeBy(14_000); runCurrent()
        assertEquals(LocSource.ANDROID, chain.status.value.active)   // 15초 전까진 유지
        advanceTimeBy(2_000); runCurrent()
        assertEquals(LocSource.FAKE, chain.status.value.active)
        assertEquals("ANDROID: stale", chain.status.value.lastStop)
        job.cancel()
    }
}
