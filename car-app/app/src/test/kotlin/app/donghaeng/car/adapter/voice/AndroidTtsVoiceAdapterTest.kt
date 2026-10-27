package app.donghaeng.car.adapter.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** 규칙 8: TTS 로그 요약에는 어떤 빌드에서도 문장 원문(닉네임 포함)이 들어가지 않는다. */
class AndroidTtsVoiceAdapterTest {

    @Test
    fun `로그 요약은 길이와 utteranceId 만 담는다`() {
        val sentence = "아빠차 연료가 12% 남았어요"
        val described = AndroidTtsVoiceAdapter.describe(sentence, "dh-7")
        assertEquals("(len=${sentence.length} id=dh-7)", described)
        assertFalse(described.contains("아빠차"))
        assertFalse(described.contains(sentence))
    }
}
