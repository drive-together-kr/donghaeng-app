package app.donghaeng.car.adapter.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** 규칙 8: TTS 로그 요약에는 어떤 빌드에서도 문장 원문(닉네임 포함)이 들어가지 않는다. + 실제 소리 시작 시각 판정 */
class AndroidTtsVoiceAdapterTest {

    @Test
    fun `소리 시작은 재생 차례(onStart)와 엔진 오디오 도착 중 늦은 쪽 - 둘 다 와야 정해진다`() {
        // sherpa-onnx 부하: onStart 는 선행 묵음 뒤 0.2 s 에 오지만 합성이 끝나 오디오가 오는 건 3.4 s — 시작은 3.4 s
        assertEquals(3_400L, AndroidTtsVoiceAdapter.soundStartAt(startAt = 200, audioAt = 3_400))
        // 합성이 빨라 오디오가 묵음 중(0.1 s)에 먼저 옴 — 재생 차례가 시작
        assertEquals(200L, AndroidTtsVoiceAdapter.soundStartAt(startAt = 200, audioAt = 100))
        assertNull("onStart 만으로는 시작이 아니다", AndroidTtsVoiceAdapter.soundStartAt(startAt = 200, audioAt = null))
        assertNull(AndroidTtsVoiceAdapter.soundStartAt(startAt = null, audioAt = 100))
    }

    @Test
    fun `로그 요약은 길이와 utteranceId 만 담는다`() {
        val sentence = "아빠차 연료가 12% 남았어요"
        val described = AndroidTtsVoiceAdapter.describe(sentence, "dh-7")
        assertEquals("(len=${sentence.length} id=dh-7)", described)
        assertFalse(described.contains("아빠차"))
        assertFalse(described.contains(sentence))
    }
}
