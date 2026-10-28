package app.donghaeng.car.adapter.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavInfoTest {

    /** 16 kHz 모노 16 bit PCM WAV 헤더(+ 선택 LIST 청크) */
    private fun wav(dataBytes: Int, rate: Int = 16_000, channels: Int = 1, bits: Int = 16, listChunk: Boolean = false, dataSizeField: Int = dataBytes): ByteArray {
        val list = if (listChunk) 8 + 6 else 0
        val b = ByteBuffer.allocate(44 + list + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt(36 + list + dataBytes); b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(channels.toShort()); b.putInt(rate)
        b.putInt(rate * channels * bits / 8); b.putShort((channels * bits / 8).toShort()); b.putShort(bits.toShort())
        if (listChunk) { b.put("LIST".toByteArray()); b.putInt(6); b.put(ByteArray(6)) }
        b.put("data".toByteArray()); b.putInt(dataSizeField)
        return b.array()
    }

    @Test
    fun `16 kHz 모노 16 bit — 길이와 PCM 위치`() {
        val bytes = wav(dataBytes = 32_000 * 3)   // 3초
        val info = WavInfo.parse(bytes)!!
        assertEquals(16_000, info.sampleRate)
        assertEquals(1, info.channels)
        assertEquals(44, info.dataOffset)
        assertEquals(3_000L, info.durationMs)
        assertTrue(info.isPcm16Mono16k)
    }

    @Test
    fun `fmt 뒤 다른 청크를 건너뛰고, 크기 0(스트리밍)이면 파일 길이로`() {
        val withList = WavInfo.parse(wav(dataBytes = 16_000, listChunk = true))!!
        assertEquals(44 + 14, withList.dataOffset)
        assertEquals(500L, withList.durationMs)
        val streamed = wav(dataBytes = 16_000, dataSizeField = 0)
        assertEquals(500L, WavInfo.parse(streamed)!!.durationMs)
    }

    @Test
    fun `WAV 가 아니거나 형식이 다르면`() {
        assertNull(WavInfo.parse("not a wav file at all".toByteArray()))
        assertNull(WavInfo.parse(ByteArray(4)))
        assertFalse(WavInfo.parse(wav(dataBytes = 100, rate = 44_100, channels = 2))!!.isPcm16Mono16k)
    }

    @Test
    fun `MIME — 계약 세 값과 확장자`() {
        assertEquals(AudioMime.MP4, AudioMime.ofFileName("a.m4a"))
        assertEquals(AudioMime.WAV, AudioMime.ofFileName("yes_ne.WAV"))
        assertEquals(AudioMime.AAC, AudioMime.ofFileName("x.aac"))
        assertNull(AudioMime.ofFileName("x.mp3"))
        assertEquals("wav", AudioMime.extensionOf("audio/wav"))
        assertEquals("m4a", AudioMime.extensionOf("audio/mp4"))
    }
}
