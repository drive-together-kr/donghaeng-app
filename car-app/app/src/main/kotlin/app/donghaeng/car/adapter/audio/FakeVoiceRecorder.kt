package app.donghaeng.car.adapter.audio

import java.io.File

/**
 * 테스트·마이크 없는 환경용 녹음 대역 (F16, CLAUDE.md 규칙 2).
 *
 * - 기본: [start] 는 성공, [stop] 은 누른 시간([clock] 차이)만큼의 가짜 m4a 파일([outDir] 에 [bytesPerSecond] 비율로 0 바이트 채움)을 돌려준다.
 * - [startFailure]·[stopFailure] 로 실패를 흉내 낸다. [injectNext] 파일이 있으면 그 파일 복사본과 [injectedDurationMs] 를 쓴다.
 */
class FakeVoiceRecorder(
    private val outDir: File,
    private val clock: () -> Long,
    private val bytesPerSecond: Int = 3_000,
) : VoiceRecorder {
    var startFailure: String? = null
    var stopFailure: String? = null
    /** 주입 파일 길이(ms) — null 이면 누른 시간 */
    var injectedDurationMs: Long? = null
    var level: Float = 0.5f

    var starts = 0
        private set
    var cancels = 0
        private set

    private var recording = false
    private var startedAt = 0L
    private var pending: File? = null
    private var injecting: File? = null

    override val hasPendingInjection: Boolean get() = pending != null

    override fun injectNext(file: File) {
        pending = file
    }

    override fun start(): RecordStart {
        startFailure?.let { return RecordStart.Failed(it) }
        starts++
        recording = true
        startedAt = clock()
        injecting = pending
        pending = null
        return RecordStart.Started
    }

    override fun currentLevel(): Float = if (recording) level else 0f

    override suspend fun stop(): RecordStop {
        val elapsed = clock() - startedAt
        if (!recording) return RecordStop.Failed("not-recording", elapsed)
        recording = false
        stopFailure?.let { return RecordStop.Failed(it, elapsed) }
        outDir.mkdirs()
        val inject = injecting
        injecting = null
        if (inject != null) {
            val mime = AudioMime.ofFileName(inject.name) ?: AudioMime.MP4
            val target = File(outDir, "fake_${starts}.${AudioMime.extensionOf(mime)}")
            inject.copyTo(target, overwrite = true)
            return RecordStop.Clip(RecordedClip(target, mime, injectedDurationMs ?: elapsed, target.length(), injected = true))
        }
        val file = File(outDir, "fake_${starts}.m4a")
        file.writeBytes(ByteArray(((elapsed * bytesPerSecond) / 1000).toInt().coerceAtLeast(1)))
        return RecordStop.Clip(RecordedClip(file, AudioMime.MP4, elapsed, file.length(), injected = false))
    }

    override fun cancel() {
        if (recording) cancels++
        recording = false
        injecting = null
    }
}
