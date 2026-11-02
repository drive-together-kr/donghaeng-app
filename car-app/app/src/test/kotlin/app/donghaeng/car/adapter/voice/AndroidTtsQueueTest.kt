package app.donghaeng.car.adapter.voice

import android.speech.tts.TextToSpeech
import app.donghaeng.car.domain.model.Priority
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Android TTS 큐(순수) — 포커스 계산(B050·B063: ERROR 를 돌려준 utterance 는 바로 빼고, 콜백이 끝내 없으면 상한 뒤 정리),
 * id 원자성(B065), 꼬리표 하나만 버리기(B012·B013: 재생 차례가 되는 순간 멈추고 뒤 문장은 다시 넣음), QUEUE_FLUSH 끊김.
 */
class AndroidTtsQueueTest {

    /** 엔진 대역 — 받은 호출을 기록하고, 반환값을 정할 수 있다 */
    private class FakeEngine : TtsEnginePort {
        data class Call(val kind: String, val id: String, val queueMode: Int, val text: String? = null)
        val calls = Collections.synchronizedList(mutableListOf<Call>())
        var silenceResult = TextToSpeech.SUCCESS
        var speakResult = TextToSpeech.SUCCESS
        var stops = 0
        override fun playSilence(durationMs: Long, queueMode: Int, utteranceId: String): Int {
            calls += Call("silence", utteranceId, queueMode)
            return silenceResult
        }
        override fun speak(text: String, queueMode: Int, utteranceId: String): Int {
            calls += Call("speak", utteranceId, queueMode, text)
            return speakResult
        }
        override fun stop() { stops++ }
        fun spoken(): List<Call> = calls.filter { it.kind == "speak" }
    }

    private class FakeFocus : AudioFocusPort {
        var requests = 0
        var abandons = 0
        override fun request(): Boolean { requests++; return true }
        override fun abandon() { abandons++ }
    }

    private var now = 0L
    private val engine = FakeEngine()
    private val focus = FakeFocus()
    private val signals = Collections.synchronizedList(mutableListOf<UtteranceSignal>())
    private val q = AndroidTtsQueue(VoicePath.ANDROID_TTS, engine, focus, clock = { now }, emit = { signals += it })

    @Test
    fun `silence and speak both returning ERROR leave nothing outstanding and give the focus back (B050)`() {
        engine.silenceResult = TextToSpeech.ERROR
        engine.speakResult = TextToSpeech.ERROR
        assertEquals(AndroidTtsQueue.Enqueued.FAILED, q.enqueue("엔진이 끊겼다", Priority.NORMAL, "evt_1"))
        assertEquals("콜백이 오지 않을 utterance 는 세지 않는다", 0, q.outstanding)
        assertEquals("포커스(덕킹)를 놓는다", 1, focus.abandons)
        assertTrue(signals.single() is UtteranceSignal.Failed)
        // 엔진이 살아나 다음 문장이 정상으로 끝나면 포커스를 다시 놓는다(옛 몫이 남지 않는다)
        engine.silenceResult = TextToSpeech.SUCCESS
        engine.speakResult = TextToSpeech.SUCCESS
        q.enqueue("다음 문장", Priority.NORMAL, "evt_2")
        val (silence, body) = engine.calls.takeLast(2)
        q.onDone(silence.id)
        q.onStart(body.id); q.onAudio(body.id); q.onDone(body.id)
        assertEquals(0, q.outstanding)
        assertEquals(2, focus.abandons)
    }

    @Test
    fun `a failed silence hands the queue mode to the body so CRITICAL still flushes`() {
        engine.silenceResult = TextToSpeech.ERROR
        q.enqueue("에어백", Priority.CRITICAL, "evt_airbag")
        val body = engine.spoken().single()
        assertEquals(TextToSpeech.QUEUE_FLUSH, body.queueMode)
        assertEquals("묵음 몫은 바로 뺐다", 1, q.outstanding)
        q.onDone(body.id)
        assertEquals(0, q.outstanding)
        assertEquals(1, focus.abandons)
    }

    @Test
    fun `utterances whose callbacks never come are purged after the limit (B063)`() {
        q.enqueue("재생 도중 엔진이 죽음", Priority.NORMAL, "evt_1")
        assertEquals(2, q.outstanding)
        now += AndroidTtsQueue.STALE_MS - 1
        q.purgeStale()
        assertEquals(2, q.outstanding)
        now += 2
        q.purgeStale()
        assertEquals(0, q.outstanding)
        assertEquals(1, focus.abandons)
        assertEquals(UtteranceSignal.Dropped(VoicePath.ANDROID_TTS, "evt_1", now, "stale"), signals.last())
    }

    @Test
    fun `utterance ids stay unique under concurrent speak calls (B065)`() {
        val threads = 8
        val perThread = 500
        val pool = Executors.newFixedThreadPool(threads)
        val go = CountDownLatch(1)
        repeat(threads) { t -> pool.execute { go.await(); repeat(perThread) { q.enqueue("문장 $t-$it", Priority.NORMAL, "t$t-$it") } } }
        go.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))
        val ids = engine.spoken().map { it.id }
        assertEquals(threads * perThread, ids.size)
        assertEquals("같은 id 가 두 번 나가지 않는다(신호 유실 방지)", ids.size, ids.toSet().size)
    }

    @Test
    fun `started is the later of onStart and the first audio, done ends it`() {
        q.enqueue("아빠 차가 휴게소에 도착했어요.", Priority.LOW, "evt_1")
        val (silence, body) = engine.calls
        q.onStart(silence.id)
        now = 200; q.onStart(body.id)
        assertTrue("onStart 만으로는 시작이 아니다", signals.isEmpty())
        now = 3_400; q.onAudio(body.id)
        assertEquals(UtteranceSignal.Started(VoicePath.ANDROID_TTS, "evt_1", 3_400, 3_400), signals.single())
        q.onDone(silence.id)
        now = 5_000; q.onDone(body.id)
        assertEquals(UtteranceSignal.Done(VoicePath.ANDROID_TTS, "evt_1", 5_000), signals.last())
    }

    @Test
    fun `cancel before its turn drops it at once, stops the engine when its turn comes and re-queues the sentences behind it (B012)`() {
        q.enqueue("앞 문장", Priority.HIGH, "evt_brake")
        q.enqueue("보낼까요?", Priority.NORMAL, "wait-1")
        q.enqueue("뒤 문장", Priority.LOW, "evt_join")
        val first = engine.calls.toList()
        // 앞 문장 재생 중에 "보낼까요?" 를 버린다 — 바로 Dropped, 엔진은 아직 건드리지 않는다
        q.onStart(first[0].id); q.onStart(first[1].id); q.onAudio(first[1].id)
        q.cancel("wait-1")
        assertEquals(UtteranceSignal.Dropped(VoicePath.ANDROID_TTS, "wait-1", 0, "cancelled"), signals.last())
        assertEquals(0, engine.stops)
        q.onDone(first[0].id); q.onDone(first[1].id)
        assertEquals(UtteranceSignal.Done(VoicePath.ANDROID_TTS, "evt_brake", 0), signals.last())
        // "보낼까요?" 의 묵음이 재생 차례 → 멈추고, 뒤 문장을 같은 꼬리표로 다시 넣는다
        q.onStart(first[2].id)
        assertEquals(1, engine.stops)
        val requeued = engine.calls.drop(first.size)
        assertEquals(listOf("silence", "speak"), requeued.map { it.kind })
        assertEquals("뒤 문장", requeued[1].text)
        assertEquals(TextToSpeech.QUEUE_ADD, requeued[0].queueMode)
        // 멈춤으로 오는 옛 id 의 onStop 은 무시 — 뒤 문장은 끊긴 것으로 알리지 않는다
        first.drop(2).forEach { q.onStop(it.id) }
        assertTrue(signals.none { it is UtteranceSignal.Dropped && it.tag == "evt_join" })
        // 다시 넣은 뒤 문장은 같은 꼬리표·처음 요청 시각으로 끝까지
        now = 9_000
        q.onStart(requeued[0].id); q.onStart(requeued[1].id); q.onAudio(requeued[1].id)
        assertEquals(UtteranceSignal.Started(VoicePath.ANDROID_TTS, "evt_join", 9_000, 9_000), signals.last())
        q.onDone(requeued[0].id); q.onDone(requeued[1].id)
        assertEquals(UtteranceSignal.Done(VoicePath.ANDROID_TTS, "evt_join", 9_000), signals.last())
        assertEquals(0, q.outstanding)
    }

    @Test
    fun `cancel while it is playing stops at once, a waiter is released as failed`() {
        val waiter = CompletableDeferred<Boolean>()
        q.enqueue("녹음 직전 안내", Priority.NORMAL, "evt_1", waiter)
        val (silence, body) = engine.calls
        q.onStart(silence.id)
        q.cancel("evt_1")
        assertEquals(1, engine.stops)
        assertEquals(false, waiter.getCompleted())
        q.onStop(silence.id); q.onStop(body.id)
        assertEquals("취소 신호는 한 번만", 1, signals.count { it is UtteranceSignal.Dropped })
        assertEquals(0, q.outstanding)
        q.cancel("evt_1")
        assertEquals("이미 버린 꼬리표는 무시", 1, engine.stops)
    }

    @Test
    fun `QUEUE_FLUSH stops end the flushed sentences as interrupted and the focus is counted exactly`() {
        q.enqueue("저잔량", Priority.NORMAL, "evt_low")
        val old = engine.calls.toList()
        q.enqueue("에어백", Priority.CRITICAL, "evt_airbag")
        val critical = engine.calls.drop(old.size)
        assertEquals(TextToSpeech.QUEUE_FLUSH, critical[0].queueMode)
        old.forEach { q.onStop(it.id) }
        assertEquals(UtteranceSignal.Dropped(VoicePath.ANDROID_TTS, "evt_low", 0, "interrupted"), signals.single())
        assertFalse("에어백이 아직 남았다", q.outstanding == 0)
        critical.forEach { q.onDone(it.id) }
        assertEquals(0, q.outstanding)
        assertEquals(1, focus.abandons)
    }

    @Test
    fun `QUEUE_FLUSH - silences flushed before their turn get no callback, the focus is still given back when the critical sentence ends (RB-2)`() {
        // 엔진 큐 = [문장1 재생 중, 문장2 묵음 대기, 문장2 본문 대기] — PTT 안내·○○ 무전 알림이 줄 선 상태
        q.enqueue("보냈어요.", Priority.NORMAL, "evt_1")
        q.enqueue("아빠 차 무전", Priority.NORMAL, "evt_2")
        val (s1, b1, s2, b2) = engine.calls.toList()
        q.onStart(s1.id); q.onDone(s1.id)
        q.onStart(b1.id); q.onAudio(b1.id)
        q.enqueue("에어백이 터졌어요.", Priority.CRITICAL, "evt_airbag")
        val critical = engine.calls.drop(4)
        assertEquals(TextToSpeech.QUEUE_FLUSH, critical[0].queueMode)
        // AOSP(android-37): 지워진 본문에는 onStop 이 온다(SynthesisPlaybackQueueItem.stop NOT_RUN → dispatchEndStatus, 합성 전이면 SpeechItem.stop →
        // dispatchOnStop). 아직 재생 차례가 아니던 묵음에는 아무 콜백도 없다(SilenceSpeechItem.stopImpl 은 빈 함수, SilencePlaybackQueueItem.stop 은
        // condvar 만 연다) — 그래서 s2 에는 onStop 을 넣지 않는다
        q.onStop(b1.id); q.onStop(b2.id)
        assertEquals(listOf("evt_1", "evt_2"), signals.filterIsInstance<UtteranceSignal.Dropped>().map { it.tag })
        assertFalse("에어백이 아직 남았다", q.outstanding == 0)
        q.onStart(critical[0].id); q.onDone(critical[0].id)
        q.onStart(critical[1].id); q.onAudio(critical[1].id); q.onDone(critical[1].id)
        assertEquals("지워진 묵음 몫이 남지 않는다", 0, q.outstanding)
        assertEquals("에어백 문장이 끝나면 바로 포커스(덕킹)를 놓는다 — 60 s 정리를 기다리지 않는다", 1, focus.abandons)
        // 늦게 오는 옛 묵음 콜백(재생 중이던 묵음의 onStop 등)은 무시된다
        q.onStop(s2.id)
        assertEquals(1, focus.abandons)
        assertEquals(0, q.outstanding)
    }

    @Test
    fun `QUEUE_FLUSH while a silence is playing - its onStop is harmless and the focus is counted once (RB-2)`() {
        q.enqueue("저잔량", Priority.NORMAL, "evt_low")
        val (s1, b1) = engine.calls.toList()
        q.onStart(s1.id)                      // 선행 묵음 재생 중
        q.enqueue("에어백", Priority.CRITICAL, "evt_airbag")
        val critical = engine.calls.drop(2)
        q.onStop(s1.id); q.onStop(b1.id)      // 재생 중이던 묵음(condvar 열림 → dispatchOnStop)·본문 모두 onStop
        assertEquals(UtteranceSignal.Dropped(VoicePath.ANDROID_TTS, "evt_low", 0, "interrupted"), signals.single())
        critical.forEach { q.onDone(it.id) }
        assertEquals(0, q.outstanding)
        assertEquals(1, focus.abandons)
    }
}
