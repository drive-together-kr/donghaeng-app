package app.donghaeng.car.adapter.voice

import app.donghaeng.car.domain.model.Priority
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 음성 체인 — 경로 선택(Android → Pleos → 화면만), 선택 중 발화, 연속 실패 강등, 지연 통계, 디버그 토큰 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChainedVoiceAdapterTest {

    /** 후보 대역 — 상태를 손으로 바꾸고, 받은 문장을 기록하고, 신호를 흘린다 */
    private class FakeRoute(override val path: VoicePath, engine: String) : VoiceRoute {
        val spoken = mutableListOf<Triple<String, Priority, String?>>()
        var stops = 0
        private val _status = MutableStateFlow(VoiceAdapterStatus(engine = engine))
        override val status: StateFlow<VoiceAdapterStatus> = _status
        val out = MutableSharedFlow<UtteranceSignal>(extraBufferCapacity = 64)
        override val signals: SharedFlow<UtteranceSignal> = out
        fun ready(detail: String = "준비") = _status.update { it.copy(ready = true, detail = detail) }
        fun unavailable(detail: String) = _status.update { it.copy(ready = false, detail = detail) }
        override suspend fun speak(text: String, priority: Priority) = speak(text, priority, null)
        override suspend fun speak(text: String, priority: Priority, tag: String?) { spoken += Triple(text, priority, tag) }
        override fun stop() { stops++ }
    }

    private val android = FakeRoute(VoicePath.ANDROID_TTS, "com.example.tts")
    private val pleos = FakeRoute(VoicePath.PLEOS_TTS, PLEOS_LABEL)

    private fun TestScope.chain(vararg routes: VoiceRoute) =
        ChainedVoiceAdapter(routes.toList(), backgroundScope, clock = { testScheduler.currentTime }).also { runCurrent() }

    @Test
    fun `android ready wins - pleos is not used`() = runTest {
        val c = chain(android, pleos)
        android.ready(); pleos.ready(); runCurrent()
        assertEquals(VoicePath.ANDROID_TTS, c.status.value.path)
        assertEquals(true, c.status.value.ready)
        assertFalse(c.status.value.selecting)
        assertEquals("com.example.tts", c.status.value.engine)
        assertEquals("1순위 준비", c.status.value.pathReason)
        c.speak("아빠 차가 휴게소에 도착했어요.", Priority.LOW, "evt_1")
        assertEquals(listOf("evt_1"), android.spoken.map { it.third })
        assertTrue(pleos.spoken.isEmpty())
    }

    @Test
    fun `android unavailable falls to pleos with the reason`() = runTest {
        val c = chain(android, pleos)
        android.unavailable("초기화 실패 status=-1 (엔진 없음)")
        pleos.ready(); runCurrent()
        val s = c.status.value
        assertEquals(VoicePath.PLEOS_TTS, s.path)
        assertEquals("ANDROID_TTS 불가(초기화 실패 status=-1 (엔진 없음))", s.pathReason)
        assertFalse(s.selecting)
        c.speak("문장", Priority.HIGH, "evt_2")
        assertEquals(1, pleos.spoken.size)
        assertEquals(listOf(VoicePath.ANDROID_TTS, VoicePath.PLEOS_TTS), s.candidates.map { it.path })
        assertEquals(listOf(false, true), s.candidates.map { it.ready })
    }

    @Test
    fun `both unavailable means display only - speak emits NoAudio and counts a fallback`() = runTest {
        val c = chain(android, pleos)
        val got = mutableListOf<UtteranceSignal>()
        backgroundScope.launch { c.signals.collect { got += it } }
        android.unavailable("ko_KR 미지원 isLanguageAvailable=-2"); pleos.unavailable("Pleos CaaS 없음"); runCurrent()
        assertEquals(VoicePath.DISPLAY_ONLY, c.status.value.path)
        assertEquals(false, c.status.value.ready)
        c.speak("문장", Priority.NORMAL, "evt_3"); runCurrent()
        assertEquals(1, c.status.value.fallbackCount)
        assertEquals(UtteranceSignal.NoAudio("evt_3", testScheduler.currentTime), got.single())
        assertTrue(android.spoken.isEmpty() && pleos.spoken.isEmpty())
    }

    @Test
    fun `fake flavor chain without pleos goes android then display only`() = runTest {
        val c = chain(android)
        assertNull("초기화 중이고 아무것도 준비 안 됨 → init", c.status.value.ready)
        assertEquals(VoicePath.DISPLAY_ONLY, c.status.value.path)
        android.unavailable("초기화 실패 status=-1 (엔진 없음)"); runCurrent()
        assertEquals(VoicePath.DISPLAY_ONLY, c.status.value.path)
        assertEquals(false, c.status.value.ready)
    }

    @Test
    fun `while android is still loading an utterance goes to the best ready route, then android takes over`() = runTest {
        val c = chain(android, pleos)
        pleos.ready(); runCurrent()
        assertEquals(VoicePath.PLEOS_TTS, c.status.value.path)
        assertTrue("1순위가 아직 초기화 중", c.status.value.selecting)
        assertEquals(true, c.status.value.ready)
        c.speak("선택 중 문장", Priority.NORMAL, "evt_4")
        assertEquals(1, pleos.spoken.size)
        advanceTimeBy(12_000); android.ready(); runCurrent()
        assertEquals(VoicePath.ANDROID_TTS, c.status.value.path)
        assertFalse(c.status.value.selecting)
        c.speak("다음 문장", Priority.NORMAL, "evt_5")
        assertEquals(1, android.spoken.size)
        assertEquals(1, pleos.spoken.size)
    }

    @Test
    fun `nothing ready during selection is display only (init), selection limit settles, a late route still lifts the path`() = runTest {
        val c = chain(android, pleos)
        c.speak("문장", Priority.LOW, null)
        assertEquals(1, c.status.value.fallbackCount)
        assertNull(c.status.value.ready)
        assertTrue(c.status.value.selecting)
        advanceTimeBy(ChainedVoiceAdapter.SELECT_TIMEOUT_MS + 1); runCurrent()
        assertFalse("상한이 지나면 확정", c.status.value.selecting)
        assertEquals(false, c.status.value.ready)
        assertEquals("ANDROID_TTS 초기화 30s 넘음 · PLEOS_TTS 초기화 30s 넘음", c.status.value.pathReason)
        pleos.ready(); runCurrent()
        assertEquals(VoicePath.PLEOS_TTS, c.status.value.path)
        android.ready(); runCurrent()
        assertEquals("상한 뒤에 준비돼도 올라간다", VoicePath.ANDROID_TTS, c.status.value.path)
    }

    @Test
    fun `three consecutive failures demote the active route, a start resets the count`() = runTest {
        val c = chain(android, pleos)
        android.ready(); pleos.ready(); runCurrent()
        val t = testScheduler.currentTime
        android.out.emit(UtteranceSignal.Failed(VoicePath.ANDROID_TTS, "a", t, "onError code=-3"))
        android.out.emit(UtteranceSignal.Failed(VoicePath.ANDROID_TTS, "b", t, "onError code=-3"))
        android.out.emit(UtteranceSignal.Started(VoicePath.ANDROID_TTS, "c", t, 400))   // 시작 → 0 으로
        android.out.emit(UtteranceSignal.Failed(VoicePath.ANDROID_TTS, "d", t, "onError code=-3"))
        android.out.emit(UtteranceSignal.Failed(VoicePath.ANDROID_TTS, "e", t, "onError code=-3"))
        runCurrent()
        assertEquals(VoicePath.ANDROID_TTS, c.status.value.path)
        assertEquals(2, c.status.value.candidates[0].consecutiveFailures)
        android.out.emit(UtteranceSignal.Failed(VoicePath.ANDROID_TTS, "f", t, "speak result=-1")); runCurrent()
        val s = c.status.value
        assertEquals(VoicePath.PLEOS_TTS, s.path)
        assertTrue(s.candidates[0].demoted)
        assertEquals("ANDROID_TTS 강등(연속 실패)", s.pathReason)
        assertEquals(5, s.failedCount)
        c.speak("강등 뒤", Priority.NORMAL, "g")
        assertEquals(1, pleos.spoken.size)
        // Pleos 도 연속 3번 실패하면 화면만
        repeat(3) { pleos.out.emit(UtteranceSignal.Failed(VoicePath.PLEOS_TTS, "p$it", t, "시작 시간 초과 15s")) }
        runCurrent()
        assertEquals(VoicePath.DISPLAY_ONLY, c.status.value.path)
        assertEquals(false, c.status.value.ready)
    }

    @Test
    fun `dropped and interrupted utterances are not failures`() = runTest {
        val c = chain(android, pleos)
        android.ready(); runCurrent()
        repeat(5) { android.out.emit(UtteranceSignal.Dropped(VoicePath.ANDROID_TTS, "x$it", 0, "interrupted")) }
        runCurrent()
        assertEquals(VoicePath.ANDROID_TTS, c.status.value.path)
        assertEquals(0, c.status.value.failedCount)
    }

    @Test
    fun `latency stats per route - last avg max over the recent ten, signals are passed on with the tag`() = runTest {
        val c = chain(android, pleos)
        val got = mutableListOf<UtteranceSignal>()
        backgroundScope.launch { c.signals.collect { got += it } }
        android.unavailable("엔진 없음"); pleos.ready(); runCurrent()
        listOf(5_200L, 6_100L, 8_800L).forEach { pleos.out.emit(UtteranceSignal.Started(VoicePath.PLEOS_TTS, "evt_$it", 0, it)) }
        runCurrent()
        assertEquals(VoiceLatencyStats(lastMs = 8_800, avgMs = 6_700, maxMs = 8_800, count = 3), c.status.value.latency)
        assertEquals(listOf("evt_5200", "evt_6100", "evt_8800"), got.map { it.tag })
        assertEquals(VoiceLatencyStats(), c.status.value.candidates[0].latency)
    }

    @Test
    fun `latency window keeps only the last ten samples`() {
        val w = VoiceLatencyWindow()
        (1..12).forEach { w.add(it * 1_000L) }
        val s = w.stats()
        assertEquals(10, s.count)
        assertEquals(12_000L, s.lastMs)
        assertEquals(12_000L, s.maxMs)
        assertEquals("3..12 초의 평균", 7_500L, s.avgMs)
        assertEquals(VoiceLatencyStats(), VoiceLatencyWindow().stats())
    }

    @Test
    fun `stop reaches every route, a critical sentence silences the other routes first`() = runTest {
        val c = chain(android, pleos)
        c.stop()
        assertEquals(1, android.stops)
        assertEquals(1, pleos.stops)
        android.ready(); runCurrent()
        c.speak("에어백", Priority.CRITICAL, "evt_airbag")
        assertEquals("경로가 막 바뀌어 Pleos 가 읽고 있어도 겹치지 않게", 2, pleos.stops)
        assertEquals("자기 경로는 QUEUE_FLUSH 로 끊으므로 stop 하지 않는다", 1, android.stops)
        assertEquals("evt_airbag", android.spoken.single().third)
    }

    @Test
    fun `policy - first ready wins, pending higher route marks selecting, demoted routes are skipped`() {
        val p = VoiceChainPolicy
        val a = VoiceChainPolicy.Candidate(VoicePath.ANDROID_TTS, ready = null)
        val b = VoiceChainPolicy.Candidate(VoicePath.PLEOS_TTS, ready = true)
        assertEquals(VoiceChainPolicy.Choice(VoicePath.PLEOS_TTS, true, "ANDROID_TTS 초기화 중 (선택 중)"), p.choose(listOf(a, b), 1_000, 30_000))
        assertEquals(VoiceChainPolicy.Choice(VoicePath.PLEOS_TTS, false, "ANDROID_TTS 초기화 30s 넘음"), p.choose(listOf(a, b), 30_000, 30_000))
        assertEquals(VoicePath.PLEOS_TTS, p.choose(listOf(a.copy(ready = true, demoted = true), b), 0, 30_000).path)
        assertEquals(VoiceChainPolicy.Choice(VoicePath.DISPLAY_ONLY, false, "소리 후보 없음"), p.choose(emptyList(), 0, 30_000))
    }

    @Test
    fun `debug tokens - ready means any sound route, engine without spaces, display only has no engine`() {
        assertEquals(
            "tts=ready ttsEngine=Pleos_TextToSpeech_ON_DEVICE ttsPath=PLEOS_TTS ttsSelecting=true",
            VoiceAdapterStatus(engine = PLEOS_LABEL, ready = true, path = VoicePath.PLEOS_TTS, selecting = true).debugTokens(),
        )
        assertEquals(
            "tts=off ttsEngine=- ttsPath=DISPLAY_ONLY ttsSelecting=false",
            VoiceAdapterStatus(engine = "무엇이든", ready = false, path = VoicePath.DISPLAY_ONLY).debugTokens(),
        )
        assertEquals("tts=init ttsEngine=- ttsPath=- ttsSelecting=false", VoiceAdapterStatus().debugTokens())
    }

    private companion object {
        const val PLEOS_LABEL = "Pleos TextToSpeech ON_DEVICE"
    }
}
