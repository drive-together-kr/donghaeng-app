package app.donghaeng.car.adapter.audio

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * 테스트용 재생 대역 (F16) — 파일별 길이([durationOf], 기본 [defaultDurationMs])만큼 가상 시간으로 기다리며 [stepMs] 마다 위치를 알린다.
 * [failNext] 면 다음 한 번은 실패로 끝난다. 재생한 파일 이름은 [played] 에 남는다.
 */
class FakeVoiceClipPlayer(
    private val defaultDurationMs: Long = 3_000,
    private val stepMs: Long = 250,
    private val durationOf: (File) -> Long? = { null },
) : VoiceClipPlayer {
    val played = mutableListOf<String>()
    var failNext = false
    var playing = false
        private set

    @Volatile private var stopSignal: CompletableDeferred<Unit>? = null

    override suspend fun play(file: File, onProgress: (positionMs: Long) -> Unit): PlayOutcome {
        if (failNext) {
            failNext = false
            return PlayOutcome.Failed("fake-failure")
        }
        played += file.name
        val total = durationOf(file) ?: defaultDurationMs
        val stop = CompletableDeferred<Unit>().also { stopSignal = it }
        playing = true
        try {
            var pos = 0L
            while (pos < total) {
                onProgress(pos)
                val step = minOf(stepMs, total - pos)
                if (withTimeoutOrNull(step) { stop.await() } != null) return PlayOutcome.Stopped
                pos += step
            }
            onProgress(total)
            return PlayOutcome.Completed
        } finally {
            playing = false
            stopSignal = null
        }
    }

    override fun stop() {
        stopSignal?.complete(Unit)
    }
}
