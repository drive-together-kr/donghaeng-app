package app.donghaeng.car.adapter.speech

import app.donghaeng.car.domain.ptt.YesNo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 인식 체인 폴백 순서 — Pleos STT → Android STT → Vosk → (없으면) null = 카운트다운 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecognizerChainTest {

    private val pleos = FakeYesNoRecognizer(RecognizerKind.PLEOS_STT)
    private val android = FakeYesNoRecognizer(RecognizerKind.ANDROID_STT)
    private val vosk = FakeYesNoRecognizer(RecognizerKind.VOSK, wavUnavailable = null)

    private fun TestScope.chain() = RecognizerChain(listOf(pleos, android, vosk), clock = { testScheduler.currentTime })

    @Test
    fun `쓸 수 없는 단계는 건너뛰고 첫 단계의 답을 쓴다`() = runTest {
        pleos.unavailable = "no-caas"
        android.then(800, ListenResult.Heard(YesNo.YES, 0.9f))
        val c = chain()
        assertTrue(c.hasCandidate())
        val o = c.listen(5_000)
        assertEquals(YesNo.YES, o.answer)
        assertEquals(RecognizerKind.ANDROID_STT, o.kind)
        assertEquals(listOf("PLEOS_STT=unavailable(no-caas)"), o.trail)
        assertEquals(0, pleos.listens)
        assertEquals(0, vosk.listens)
        assertEquals(800L, testScheduler.currentTime)
    }

    @Test
    fun `듣다 실패하면 남은 시간으로 다음 단계 — 영구 실패(error 9)는 다음 번에 건너뛴다`() = runTest {
        pleos.unavailable = "no-stt-permission"
        android.then(300, ListenResult.Failed("stt error=9", permanent = true))
        vosk.then(1_000, ListenResult.Heard(YesNo.NO))
        val c = chain()
        val o = c.listen(5_000)
        assertEquals(YesNo.NO, o.answer)
        assertEquals(RecognizerKind.VOSK, o.kind)
        assertTrue(o.trail.contains("ANDROID_STT=failed(stt error=9)"))
        assertEquals(1_300L, testScheduler.currentTime)

        vosk.then(500, ListenResult.Heard(YesNo.YES))
        val second = c.listen(5_000)
        assertEquals(RecognizerKind.VOSK, second.kind)
        assertEquals("영구 실패한 단계는 다시 듣지 않는다", 1, android.listens)
        assertTrue(c.describe().contains("ANDROID_STT=failed(stt error=9)"))
        assertTrue(c.describe().contains("VOSK=ready"))
    }

    @Test
    fun `일시 실패는 다음 듣기에서 다시 시도한다`() = runTest {
        pleos.unavailable = "no-caas"
        android.then(100, ListenResult.Failed("stt error=2", permanent = false))
        vosk.then(100, ListenResult.Heard(YesNo.YES))
        val c = chain()
        c.listen(5_000)
        android.then(100, ListenResult.Heard(YesNo.YES))
        val o = c.listen(5_000)
        assertEquals(RecognizerKind.ANDROID_STT, o.kind)
        assertEquals(2, android.listens)
    }

    @Test
    fun `NONE 이고 시간이 남으면 같은 단계로 다시 듣는다`() = runTest {
        pleos.unavailable = "no-caas"
        android.then(1_000, ListenResult.Heard(YesNo.NONE)).then(1_000, ListenResult.Heard(YesNo.YES))
        val o = chain().listen(5_000)
        assertEquals(YesNo.YES, o.answer)
        assertEquals(2, android.listens)
    }

    @Test
    fun `모든 단계가 없거나 실패하면 answer null — 카운트다운으로`() = runTest {
        pleos.unavailable = "no-caas"
        android.unavailable = "no-service"
        vosk.unavailable = "no-model"
        val c = chain()
        assertFalse(c.hasCandidate())
        val o = c.listen(5_000)
        assertNull(o.answer)
        assertEquals(listOf("PLEOS_STT=unavailable(no-caas)", "ANDROID_STT=unavailable(no-service)", "VOSK=unavailable(no-model)"), o.trail)

        // 듣는 중에 다 실패해도 null
        vosk.unavailable = null
        android.unavailable = null
        android.then(200, ListenResult.Failed("stt error=9", permanent = true))
        vosk.then(200, ListenResult.Failed("vosk-IOException", permanent = false))
        assertNull(chain().listen(5_000).answer)
    }

    @Test
    fun `주입된 답 — 듣기 전이면 바로, 듣는 중이면 그 듣기를 끝낸다`() = runTest {
        pleos.unavailable = "no-caas"
        android.unavailable = "no-service"
        vosk.unavailable = "no-model"
        val c = chain()
        c.injectAnswer(YesNo.YES)
        assertTrue("주입만 있어도 후보가 있다", c.hasCandidate())
        val o = c.listen(5_000)
        assertEquals(YesNo.YES, o.answer)
        assertEquals(RecognizerKind.INJECTED, o.kind)
        assertEquals(0L, testScheduler.currentTime)

        vosk.unavailable = null
        val c2 = chain()
        val pending = async { c2.listen(5_000) }   // Vosk 대본 없음 → 5초 듣는다
        runCurrent()
        advanceTimeBy(1_200)
        c2.injectAnswer(YesNo.NO)
        runCurrent()
        val r = pending.await()
        assertEquals(YesNo.NO, r.answer)
        assertEquals(RecognizerKind.INJECTED, r.kind)
        assertEquals(1_200L, testScheduler.currentTime)
        assertFalse(c2.hasPendingInjection)
    }

    @Test
    fun `WAV 주입은 WAV 를 받는 첫 단계(Vosk)에 — 마이크 권한과 무관`() = runTest {
        pleos.unavailable = "no-caas"
        vosk.unavailable = "no-mic-permission"   // 마이크 없어도 WAV 는 된다
        vosk.then(700, ListenResult.Heard(YesNo.YES, 0.95f))
        val c = chain()
        c.injectWav(File("yes_ne.wav"))
        val o = c.listen(5_000)
        assertEquals(YesNo.YES, o.answer)
        assertEquals(RecognizerKind.VOSK, o.kind)
        assertEquals(1, vosk.wavListens)
        assertEquals(0, android.listens)
        assertTrue(o.trail.contains("wav"))
    }

    @Test
    fun `카운트다운용 주입 대기 — 주입이 오면 한 번만 꺼낸다`() = runTest {
        val c = chain()
        val waiting = async { c.awaitInjected() }
        runCurrent()
        assertFalse(waiting.isCompleted)
        c.injectAnswer(YesNo.NONE)
        runCurrent()
        assertEquals(YesNo.NONE, waiting.await())
        assertFalse("꺼낸 답은 다음 듣기에 남지 않는다", c.hasPendingInjection)
    }

    @Test
    fun `준비는 쓸 수 있는 단계만`() = runTest {
        pleos.unavailable = "no-caas"
        val c = chain()
        c.prepare()
        assertEquals(0, pleos.prepares)
        assertEquals(1, android.prepares)
        assertEquals(1, vosk.prepares)
    }
}
