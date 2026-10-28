package app.donghaeng.car.adapter.voice

import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `speakAndWait 대기 — onDone 성공, onError·onStop(QUEUE_FLUSH 로 지워짐) 실패, 모르는 id 무시`() {
        val w = UtteranceWaiters()
        val ask = CompletableDeferred<Boolean>()
        val announce = CompletableDeferred<Boolean>()
        w.register("dh-1", ask)
        w.register("dh-3", announce)
        assertFalse("묵음 utterance(-s)는 기다리는 쪽이 없다", w.finish("dh-1-s", ok = true))
        // CRITICAL 이 큐를 비웠다 — 지워진 "보낼까요?" 에는 onStop 만 온다
        assertTrue(w.finish("dh-1", ok = false))
        assertTrue(ask.isCompleted)
        assertEquals(false, ask.getCompleted())
        assertFalse("한 번만 푼다", w.finish("dh-1", ok = true))
        assertTrue(w.finish("dh-3", ok = true))
        assertEquals(true, announce.getCompleted())
        assertEquals(0, w.size)

        val pending = CompletableDeferred<Boolean>()
        w.register("dh-5", pending)
        w.failAll()
        assertEquals(false, pending.getCompleted())
        assertFalse(w.finish(null, ok = true))
    }
}
