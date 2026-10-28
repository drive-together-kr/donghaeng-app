package app.donghaeng.car.adapter.speech

import android.speech.SpeechRecognizer
import app.donghaeng.car.domain.ptt.YesNo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 인식기별 결과 해석(순수 부분) — Vosk JSON, Android SpeechRecognizer 오류 코드 */
class RecognizerResultsTest {

    @Test
    fun `Vosk 결과 JSON — text 판정과 낱말 신뢰도 평균`() {
        val r = VoskResult.parse("""{"result":[{"conf":0.9,"end":1.0,"start":0.5,"word":"네"},{"conf":0.7,"end":1.4,"start":1.0,"word":"보내"}],"text":"네 보내"}""")
        assertEquals(YesNo.YES, r.answer)
        assertEquals(0.8f, r.confidence!!, 0.001f)
        assertEquals(YesNo.NO, VoskResult.parse("""{"text":"아니요"}""").answer)
        assertNull(VoskResult.parse("""{"text":"아니요"}""").confidence)
        assertEquals(YesNo.NONE, VoskResult.parse("""{"text":"[unk]"}""").answer)
        assertEquals(YesNo.NONE, VoskResult.parse("""{"text":""}""").answer)
        assertEquals("깨진 JSON 은 NONE", YesNo.NONE, VoskResult.parse("not json").answer)
        assertEquals(YesNo.NONE, VoskResult.parse(null).answer)
    }

    @Test
    fun `Vosk 문법 JSON 은 단어 배열`() {
        val g = VoskResult.GRAMMAR_JSON
        assertTrue(g.startsWith("[\"네\",\"예\""))
        assertTrue(g.endsWith("\"[unk]\"]"))
    }

    @Test
    fun `SpeechRecognizer 오류 — 말 없음은 NONE, 9 는 영구 실패(Pleos AVD 실측), 네트워크는 이번만`() {
        assertEquals(ListenResult.Heard(YesNo.NONE), AndroidSpeechYesNoRecognizer.resultOfError(SpeechRecognizer.ERROR_NO_MATCH))
        assertEquals(ListenResult.Heard(YesNo.NONE), AndroidSpeechYesNoRecognizer.resultOfError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT))
        assertEquals(ListenResult.Failed("stt error=9", permanent = true), AndroidSpeechYesNoRecognizer.resultOfError(9))
        assertEquals(ListenResult.Failed("stt error=12", permanent = true), AndroidSpeechYesNoRecognizer.resultOfError(12))
        assertEquals(ListenResult.Failed("stt error=2", permanent = false), AndroidSpeechYesNoRecognizer.resultOfError(SpeechRecognizer.ERROR_NETWORK))
        assertEquals(ListenResult.Failed("stt error=8", permanent = false), AndroidSpeechYesNoRecognizer.resultOfError(SpeechRecognizer.ERROR_RECOGNIZER_BUSY))
    }
}
