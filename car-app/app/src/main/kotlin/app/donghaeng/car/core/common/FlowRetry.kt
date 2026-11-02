package app.donghaeng.car.core.common

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.retryWhen

/**
 * 예외로 끝난 흐름을 지수 백오프([baseMs] → 두 배씩 → [maxMs])로 다시 구독한다(A044).
 * `shareIn` 앞에 두면 구독자가 계속 붙어 있어도(싱글턴이 늘 구독) 실패한 연결을 다시 시도한다 — `WhileSubscribed` 만으로는
 * 구독자 수가 0 이 되지 않는 한 다시 띄우지 않는다. 정상 완료(close())는 다시 구독하지 않는다(예: Car API 가 아예 없는 기기).
 */
fun <T> Flow<T>.retryWithBackoff(
    baseMs: Long = 1_000L,
    maxMs: Long = 30_000L,
    onRetry: (attempt: Long, cause: Throwable, delayMs: Long) -> Unit = { _, _, _ -> },
): Flow<T> = retryWhen { cause, attempt ->
    if (cause is CancellationException) return@retryWhen false
    val delayMs = backoffDelayMs(attempt, baseMs, maxMs)
    onRetry(attempt, cause, delayMs)
    delay(delayMs)
    true
}

/** [attempt](0부터) 번째 재시도 대기 — base, 2·base, 4·base … 최대 [maxMs] */
fun backoffDelayMs(attempt: Long, baseMs: Long, maxMs: Long): Long =
    (baseMs shl attempt.coerceIn(0, 20).toInt()).coerceIn(baseMs, maxMs)
