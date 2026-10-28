package app.donghaeng.car.adapter.voice

import app.donghaeng.car.domain.model.Priority
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 발화 id 없는 콜백형 엔진(Pleos TTS) 순서 맞추기 — 한 문장씩, 시작 지연, 시간 초과, 급한 문장 끼어들기, 큐 대기 상한, 포커스 */
@OptIn(ExperimentalCoroutinesApi::class)
class SerialUtterancePlayerTest {

    private class FakeBackend : SerialUtterancePlayer.Backend {
        val spoken = mutableListOf<String>()
        var stops = 0
        var failNext = false
        override suspend fun speak(text: String) {
            if (failNext) { failNext = false; throw IllegalStateException("서비스 끊김") }
            spoken += text
        }
        override fun stop() { stops++ }
    }

    private class FakeFocus : AudioFocusPort {
        var requests = 0
        var abandons = 0
        override fun request(): Boolean { requests++; return true }
        override fun abandon() { abandons++ }
    }

    private val backend = FakeBackend()
    private val focus = FakeFocus()
    private val signals = mutableListOf<UtteranceSignal>()

    private fun TestScope.player() = SerialUtterancePlayer(
        path = VoicePath.PLEOS_TTS, backend = backend, focus = focus, scope = backgroundScope,
        clock = { testScheduler.currentTime }, emit = { signals += it },
    ).also { runCurrent() }

    @Test
    fun `one sentence at a time - start latency is measured from the request, the next waits for done`() = runTest {
        val p = player()
        p.enqueue("첫 문장", Priority.NORMAL, "evt_1")
        p.enqueue("둘째 문장", Priority.NORMAL, "evt_2")
        runCurrent()
        assertEquals(listOf("첫 문장"), backend.spoken)
        advanceTimeBy(6_000); p.onStart(); runCurrent()
        assertEquals(UtteranceSignal.Started(VoicePath.PLEOS_TTS, "evt_1", 6_000, 6_000), signals.last())
        assertEquals("앞 문장이 끝나기 전에는 넘기지 않는다", 1, backend.spoken.size)
        advanceTimeBy(2_000); p.onDone(); runCurrent()
        assertEquals(UtteranceSignal.Done(VoicePath.PLEOS_TTS, "evt_1", 8_000), signals.last())
        assertEquals(listOf("첫 문장", "둘째 문장"), backend.spoken)
        advanceTimeBy(1_000); p.onStart(); runCurrent()
        assertEquals("둘째 문장 지연 = 요청(0 s) → 시작(9 s), 큐 대기 포함", 9_000L, (signals.last() as UtteranceSignal.Started).latencyMs)
        p.onDone(); runCurrent()
        assertEquals("포커스는 한 번 잡고 큐가 비면 반납", 1, focus.requests)
        assertEquals(1, focus.abandons)
    }

    @Test
    fun `no start within the limit is a failure and the engine is stopped`() = runTest {
        val p = player()
        p.enqueue("문장", Priority.HIGH, "evt_1")
        runCurrent()
        advanceTimeBy(SerialUtterancePlayer.START_TIMEOUT_MS + SerialUtterancePlayer.STOP_SETTLE_MS + 10); runCurrent()
        val f = signals.single() as UtteranceSignal.Failed
        assertEquals("evt_1", f.tag)
        assertEquals("시작 시간 초과 15s", f.reason)
        assertEquals(1, backend.stops)
    }

    @Test
    fun `engine error before start is a failure, speak exception is a failure`() = runTest {
        val p = player()
        p.enqueue("문장", Priority.NORMAL, "evt_1")
        runCurrent()
        p.onError("ON_DEVICE_ENGINE_ERROR"); runCurrent()
        assertEquals(UtteranceSignal.Failed(VoicePath.PLEOS_TTS, "evt_1", 0, "ON_DEVICE_ENGINE_ERROR"), signals.last())
        backend.failNext = true
        p.enqueue("다음", Priority.NORMAL, "evt_2"); runCurrent()
        assertEquals("speak 예외 IllegalStateException", (signals.last() as UtteranceSignal.Failed).reason)
    }

    @Test
    fun `critical flushes the queue, interrupts the current sentence and is spoken next`() = runTest {
        val p = player()
        p.enqueue("저잔량", Priority.NORMAL, "evt_low")
        p.enqueue("합류", Priority.LOW, "evt_join")
        runCurrent()
        p.onStart(); runCurrent()
        p.enqueue("에어백", Priority.CRITICAL, "evt_airbag"); runCurrent()
        assertEquals(1, backend.stops)
        assertEquals(UtteranceSignal.Dropped(VoicePath.PLEOS_TTS, "evt_join", 0, "flushed"), signals.first { it is UtteranceSignal.Dropped })
        p.onStop(); runCurrent()
        assertTrue(signals.contains(UtteranceSignal.Dropped(VoicePath.PLEOS_TTS, "evt_low", 0, "interrupted")))
        assertEquals(listOf("저잔량", "에어백"), backend.spoken)
        advanceTimeBy(500); p.onStart(); runCurrent()
        assertEquals("evt_airbag", (signals.last() as UtteranceSignal.Started).tag)
    }

    @Test
    fun `a sentence that waited too long in the queue is dropped, not failed`() = runTest {
        val p = player()
        val long = "가".repeat(40)   // 끝 신호 상한 20 s + 40 × 0.3 s = 32 s — 그동안 둘째 문장이 30 s 넘게 기다린다
        p.enqueue(long, Priority.NORMAL, "evt_1")
        p.enqueue("오래 기다린 문장", Priority.NORMAL, "evt_2")
        runCurrent()
        advanceTimeBy(1_000); p.onStart(); runCurrent()
        advanceTimeBy(31_000); p.onDone(); runCurrent()
        assertEquals(UtteranceSignal.Dropped(VoicePath.PLEOS_TTS, "evt_2", 32_000, "queued-too-long"), signals.last())
        assertEquals(listOf(long), backend.spoken)
    }

    @Test
    fun `missing done after start is treated as done so the queue keeps moving`() = runTest {
        val p = player()
        p.enqueue("가", Priority.NORMAL, "evt_1")
        p.enqueue("나", Priority.NORMAL, "evt_2")
        runCurrent()
        p.onStart(); runCurrent()
        advanceTimeBy(SerialUtterancePlayer.DONE_BASE_MS + SerialUtterancePlayer.DONE_PER_CHAR_MS + 10); runCurrent()
        assertTrue(signals.any { it is UtteranceSignal.Done && it.tag == "evt_1" })
        assertEquals(listOf("가", "나"), backend.spoken)
    }

    @Test
    fun `stopAll drops the queue and stops the engine`() = runTest {
        val p = player()
        p.enqueue("가", Priority.NORMAL, "evt_1")
        p.enqueue("나", Priority.NORMAL, "evt_2")
        runCurrent()
        p.stopAll(); runCurrent()
        p.onStop(); runCurrent()
        assertEquals(1, backend.stops)
        assertTrue(signals.contains(UtteranceSignal.Dropped(VoicePath.PLEOS_TTS, "evt_2", 0, "stopped")))
        assertTrue(signals.contains(UtteranceSignal.Dropped(VoicePath.PLEOS_TTS, "evt_1", 0, "interrupted")))
        assertEquals(0, p.pending)
    }
}
