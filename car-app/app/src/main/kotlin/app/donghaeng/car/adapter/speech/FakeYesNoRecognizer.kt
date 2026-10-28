package app.donghaeng.car.adapter.speech

import app.donghaeng.car.domain.ptt.YesNo
import kotlinx.coroutines.delay
import java.io.File

/**
 * 테스트용 '네/아니' 단계 (F16, CLAUDE.md 규칙 2) — 대본([script])대로 [ListenResult] 를 돌려준다.
 * 대본 한 줄 = (걸리는 시간 ms, 결과). 대본이 떨어지면 [timeoutMs] 끝까지 기다렸다가 NONE.
 */
class FakeYesNoRecognizer(
    override val kind: RecognizerKind,
    var unavailable: String? = null,
    var wavUnavailable: String? = "wav-unsupported",
) : YesNoRecognizer {
    val script = ArrayDeque<Pair<Long, ListenResult>>()
    var listens = 0
        private set
    var wavListens = 0
        private set
    var prepares = 0
        private set

    fun then(afterMs: Long, result: ListenResult): FakeYesNoRecognizer = apply { script.addLast(afterMs to result) }

    override fun unavailableReason(): String? = unavailable

    override fun wavUnavailableReason(): String? = wavUnavailable

    override suspend fun prepare() {
        prepares++
    }

    override suspend fun listen(timeoutMs: Long): ListenResult {
        listens++
        return play(timeoutMs)
    }

    override suspend fun listenWav(file: File, timeoutMs: Long): ListenResult {
        wavListens++
        return play(timeoutMs)
    }

    private suspend fun play(timeoutMs: Long): ListenResult {
        val (after, result) = script.removeFirstOrNull() ?: run {
            delay(timeoutMs)
            return ListenResult.Heard(YesNo.NONE)
        }
        if (after >= timeoutMs) {
            delay(timeoutMs)
            return ListenResult.Heard(YesNo.NONE)
        }
        delay(after)
        return result
    }
}
