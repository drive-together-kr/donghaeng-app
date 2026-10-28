package app.donghaeng.car.adapter.audio

import java.io.File

/**
 * 무전 녹음 결과 한 개 (F16). [file] 은 앱 캐시(`cache/ptt/out`)에 있고, 보냈거나 버리면 지운다.
 *
 * @param mime 서버 계약(docs/03 §2.6) 값 — `audio/mp4`(AAC-LC m4a, 기본) · `audio/aac` · `audio/wav`(디버그 주입)
 * @param injected 마이크가 아니라 [VoiceRecorder.injectNext] 로 넣은 파일인가(디버그·시연)
 */
data class RecordedClip(
    val file: File,
    val mime: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val injected: Boolean = false,
)

/** [VoiceRecorder.start] 결과 */
sealed interface RecordStart {
    data object Started : RecordStart

    /** 마이크·인코더를 열지 못했다(권한·장치·포커스) — 사유는 로그용 짧은 영어 */
    data class Failed(val reason: String) : RecordStart
}

/** [VoiceRecorder.stop] 결과 */
sealed interface RecordStop {
    data class Clip(val clip: RecordedClip) : RecordStop

    /** 결과 파일을 만들지 못했다. [elapsedMs] 가 짧으면(MediaRecorder 는 데이터가 거의 없으면 stop 에서 실패한다) 호출 쪽이 '너무 짧아요'로 본다 */
    data class Failed(val reason: String, val elapsedMs: Long) : RecordStop
}

/**
 * 무전 녹음 어댑터 (F16, docs/05 § 무전).
 *
 * - 기기: [AndroidVoiceRecorder] — `MediaRecorder` AAC-LC MPEG-4 16 kHz 모노 24 kbps → 캐시 파일, 녹음 동안 오디오 포커스 `GAIN_TRANSIENT_EXCLUSIVE`
 *   (내비 안내 음성이 녹음에 섞이지 않게, 끝나면 반환). AVD(`hw.audioInput=no`)는 진폭 0 인 무음 파일이 된다.
 * - 테스트: [FakeVoiceRecorder].
 * - 디버그·시연: [injectNext] 로 넣은 파일(WAV·m4a)을 **다음 한 번의 녹음 결과**로 쓴다 — 녹음 화면·시간 흐름은 그대로, 마이크만 열지 않는다.
 *
 * 녹음 내용은 어떤 로그에도 남기지 않는다(길이·바이트 수만).
 */
interface VoiceRecorder {
    /** 녹음을 시작한다. 이미 녹음 중이면 이전 녹음을 버리고 새로 시작한다 */
    fun start(): RecordStart

    /** 지난 호출 이후 입력 크기 0..1(파형 막대용, 100 ms 마다 부른다). 녹음 중이 아니면 0 */
    fun currentLevel(): Float

    /** 녹음을 끝내고 결과 파일을 돌려준다 */
    suspend fun stop(): RecordStop

    /** 녹음을 버린다(파일 삭제, 포커스 반환). 녹음 중이 아니면 아무것도 안 한다 */
    fun cancel()

    /** 다음 녹음 한 번을 [file] 로 대신한다(디버그 `ptt_inject_record`). 파일은 복사해 쓰므로 원본은 그대로 */
    fun injectNext(file: File)

    /** [injectNext] 로 넣어 둔 파일이 아직 쓰이지 않았는가 — 마이크 권한이 없어도 주입 녹음은 진행한다 */
    val hasPendingInjection: Boolean
}

/** 무전 오디오 MIME — 서버 계약(docs/03 §2.6)의 세 값 */
object AudioMime {
    const val MP4 = "audio/mp4"
    const val AAC = "audio/aac"
    const val WAV = "audio/wav"

    /** 파일 확장자 → MIME. 모르는 확장자는 null(주입 거부) */
    fun ofFileName(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
        "m4a", "mp4" -> MP4
        "aac" -> AAC
        "wav" -> WAV
        else -> null
    }

    /** MIME → 캐시 파일 확장자 */
    fun extensionOf(mime: String): String = when (mime.lowercase()) {
        WAV, "audio/x-wav", "audio/wave" -> "wav"
        AAC -> "aac"
        else -> "m4a"
    }
}

/**
 * RIFF/WAVE PCM 헤더 해석(순수) — 디버그 주입 파일의 길이(ms)와 인식기(Vosk)에 흘릴 PCM 위치를 구한다.
 * `fmt ` 청크 뒤에 `LIST` 같은 다른 청크가 있어도 `data` 청크를 찾아간다.
 */
data class WavInfo(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    /** PCM 시작 바이트 위치 */
    val dataOffset: Int,
    val dataBytes: Int,
) {
    val durationMs: Long
        get() {
            val bytesPerSecond = sampleRate.toLong() * channels * (bitsPerSample / 8)
            return if (bytesPerSecond <= 0) 0 else dataBytes * 1000L / bytesPerSecond
        }

    /** Vosk 소형 모델 입력(16 kHz·모노·16 bit) 그대로 흘릴 수 있는가 */
    val isPcm16Mono16k: Boolean get() = sampleRate == 16_000 && channels == 1 && bitsPerSample == 16

    companion object {
        /** 헤더(앞부분 몇 KB면 충분)를 해석한다. WAV 가 아니거나 깨졌으면 null */
        fun parse(header: ByteArray, fileLength: Long = header.size.toLong()): WavInfo? {
            if (header.size < 12) return null
            if (ascii(header, 0, 4) != "RIFF" || ascii(header, 8, 4) != "WAVE") return null
            var pos = 12
            var rate = 0
            var channels = 0
            var bits = 0
            var format = 0
            while (pos + 8 <= header.size) {
                val id = ascii(header, pos, 4)
                val size = le32(header, pos + 4)
                val body = pos + 8
                when (id) {
                    "fmt " -> {
                        if (body + 16 > header.size) return null
                        format = le16(header, body)
                        channels = le16(header, body + 2)
                        rate = le32(header, body + 4)
                        bits = le16(header, body + 14)
                    }
                    "data" -> {
                        if (format != 1 && format != 0xFFFE) return null   // PCM(1) 또는 WAVE_FORMAT_EXTENSIBLE 만
                        if (rate <= 0 || channels <= 0 || bits <= 0) return null
                        // 스트리밍으로 만든 WAV 는 크기가 0 또는 0xFFFFFFFF 일 수 있다 → 파일 길이로 계산
                        val available = (fileLength - body).coerceAtLeast(0)
                        val bytes = if (size <= 0 || size > available) available.toInt() else size
                        return WavInfo(rate, channels, bits, body, bytes)
                    }
                }
                if (size < 0) return null
                pos = body + size + (size and 1)   // 청크는 짝수 바이트로 맞춘다
            }
            return null
        }

        private fun ascii(b: ByteArray, at: Int, n: Int): String =
            if (at + n > b.size) "" else String(b, at, n, Charsets.US_ASCII)

        private fun le16(b: ByteArray, at: Int): Int = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

        private fun le32(b: ByteArray, at: Int): Int =
            (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)
    }
}
