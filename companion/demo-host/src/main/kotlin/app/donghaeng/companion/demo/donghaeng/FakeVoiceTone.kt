package app.donghaeng.companion.demo.donghaeng

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * "서버 없이 둘러보기"(FakeGateway) 용 무전 소리 — 녹음 대신 무전기 신호음(잡음 → 삐-삐-삐 → 잡음)을 16 kHz 모노 16-bit WAV 로 만든다.
 * 합성 음성·녹음 파일을 저장소에 넣지 않기 위해 코드로 만든다. 같은 시드라 매번 같은 소리.
 */
object FakeVoiceTone {

    const val SAMPLE_RATE = 16_000

    /** 만든 파일과 길이 */
    data class Tone(val file: File, val durationMs: Int, val sizeBytes: Int)

    /** [dir] 에 [name] 으로 쓴다(이미 있으면 다시 만들지 않는다) */
    fun write(dir: File, name: String = "fake_voice.wav"): Tone {
        dir.mkdirs()
        val file = File(dir, name)
        val pcm = samples()
        if (!file.isFile || file.length() != (44 + pcm.size * 2).toLong()) {
            file.writeBytes(wav(pcm))
        }
        return Tone(file, durationMs = pcm.size * 1000 / SAMPLE_RATE, sizeBytes = file.length().toInt())
    }

    /** 신호음 PCM(16-bit) */
    fun samples(): ShortArray {
        val random = java.util.Random(16L)
        val out = ArrayList<Short>()
        fun ms(n: Int) = n * SAMPLE_RATE / 1000
        fun noise(lengthMs: Int, gain: Double) {
            val n = ms(lengthMs)
            for (i in 0 until n) {
                val fade = min(1.0, min(i, n - i) / (ms(15).toDouble()))
                out += ((random.nextGaussian() * gain * fade).coerceIn(-1.0, 1.0) * Short.MAX_VALUE).roundToInt().toShort()
            }
        }
        fun beep(freq: Double, lengthMs: Int, gain: Double) {
            val n = ms(lengthMs)
            for (i in 0 until n) {
                val fade = min(1.0, min(i, n - i) / (ms(10).toDouble()))
                out += (sin(2 * PI * freq * i / SAMPLE_RATE) * gain * fade * Short.MAX_VALUE).roundToInt().toShort()
            }
        }
        fun silence(lengthMs: Int) = repeat(ms(lengthMs)) { out += 0.toShort() }

        noise(120, 0.12)
        silence(80)
        beep(880.0, 220, 0.45)
        silence(120)
        beep(1046.5, 220, 0.45)
        silence(120)
        beep(1318.5, 320, 0.45)
        silence(120)
        noise(100, 0.10)
        return out.toShortArray()
    }

    /** PCM → RIFF/WAVE (PCM 16-bit 모노) */
    fun wav(pcm: ShortArray): ByteArray {
        val dataBytes = pcm.size * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + dataBytes)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16) // fmt 청크 크기
            putShort(1) // PCM
            putShort(1) // 모노
            putInt(SAMPLE_RATE)
            putInt(SAMPLE_RATE * 2) // byteRate
            putShort(2) // blockAlign
            putShort(16) // bitsPerSample
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataBytes)
        }
        val body = ByteBuffer.allocate(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        pcm.forEach { body.putShort(it) }
        return ByteArrayOutputStream(44 + dataBytes).apply {
            write(header.array())
            write(body.array())
        }.toByteArray()
    }
}
