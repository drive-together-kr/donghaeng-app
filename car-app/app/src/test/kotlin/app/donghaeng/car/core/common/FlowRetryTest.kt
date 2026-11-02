package app.donghaeng.car.core.common

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A044: CarUxRestrictionsManager 연결 실패가 프로세스 내내 '정차'로 굳지 않게 — 늘 구독 중인 shareIn 앞에서도 실패한 연결을 백오프로 다시 시도한다.
 * 정상 완료(Car API 없음)는 다시 하지 않는다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FlowRetryTest {

    @Test
    fun `a failing connection is retried with backoff while a subscriber stays, and recovers`() = runTest {
        var connects = 0
        val upstream = flow {
            connects++
            emit(false)   // 실패 동안은 '정차'
            if (connects < 3) throw IllegalStateException("CarUxRestrictionsManager unavailable")
            emit(true)    // 세 번째에 연결 — 실제 값(주행 중)
            kotlinx.coroutines.awaitCancellation()
        }
        val seen = mutableListOf<Boolean>()
        val shared = upstream.retryWithBackoff(baseMs = 1_000, maxMs = 30_000).shareIn(backgroundScope, SharingStarted.WhileSubscribed(5_000), replay = 1)
        shared.onEach { seen += it }.launchIn(backgroundScope)
        runCurrent()
        assertEquals(1, connects)
        advanceTimeBy(1_001); runCurrent()
        assertEquals(2, connects)
        advanceTimeBy(2_001); runCurrent()
        assertEquals(3, connects)
        assertEquals(true, seen.last())
    }

    @Test
    fun `a normal completion (no Car API) is not retried, backoff caps at the max`() = runTest {
        var connects = 0
        val upstream = flow { connects++; emit(false) }
        upstream.retryWithBackoff().launchIn(backgroundScope)
        advanceTimeBy(120_000); runCurrent()
        assertEquals(1, connects)
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), (0L..6L).map { backoffDelayMs(it, 1_000, 30_000) })
    }
}
