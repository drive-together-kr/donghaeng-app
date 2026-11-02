package app.donghaeng.car.domain.voice

import app.donghaeng.car.adapter.voice.UtteranceSignal
import app.donghaeng.car.adapter.voice.VoiceAdapter
import app.donghaeng.car.adapter.voice.VoiceAdapterStatus
import app.donghaeng.car.adapter.voice.VoicePath
import app.donghaeng.car.domain.model.Priority
import app.donghaeng.car.domain.session.ReceivedGroupEvent
import app.donghaeng.protocol.GroupEvent
import app.donghaeng.protocol.GroupEventType
import app.donghaeng.protocol.Severity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 음성 큐와 어댑터 사이 — 한 번에 한 문장(끝 신호를 기다림, B011), 녹음 시작 때 넘긴 NORMAL·LOW 를 멈추고 다시 줄 세움(B013),
 * 그룹이 끝나면 옛 그룹 문장 버림(B060), '음성 안내' 스위치는 메모리 값만(저장은 A120 UserSettings — RB-5).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceAnnouncerQueueTest {

    /**
     * Pleos 처럼 느린 직렬 엔진 대역 — 넘겨받은 순서(FIFO)대로 [startMs] 뒤 시작, [readMs] 뒤 끝. 끝 신호를 낸다([reportsEnd]).
     * [cancel]: 대기 중이면 빼고(Dropped cancelled), 재생 차례면 끊는다(Dropped interrupted).
     */
    private class SlowSerialVoice(scope: CoroutineScope, private val startMs: Long, private val readMs: Long) : VoiceAdapter {
        val handed = mutableListOf<String?>()
        val played = mutableListOf<String?>()
        val cancelled = mutableListOf<String>()
        private val queue = ArrayDeque<String?>()
        private val wake = Channel<Unit>(Channel.CONFLATED)
        private var current: String? = null
        private var currentJob: Job? = null
        private val out = MutableSharedFlow<UtteranceSignal>(extraBufferCapacity = 64)
        override val signals: SharedFlow<UtteranceSignal> = out
        override val reportsEnd: Boolean = true
        override val status: StateFlow<VoiceAdapterStatus> = MutableStateFlow(VoiceAdapterStatus(ready = true))

        init {
            scope.launch {
                while (true) {
                    val tag = queue.removeFirstOrNull()
                    if (tag == null) { wake.receive(); continue }
                    current = tag
                    coroutineScope {
                        val j = launch {
                            delay(startMs)
                            played += tag
                            out.emit(UtteranceSignal.Started(VoicePath.PLEOS_TTS, tag, 0, startMs))
                            delay(readMs)
                        }
                        currentJob = j
                        j.join()
                        out.emit(if (j.isCancelled) UtteranceSignal.Dropped(VoicePath.PLEOS_TTS, tag, 0, "interrupted") else UtteranceSignal.Done(VoicePath.PLEOS_TTS, tag, 0))
                    }
                    current = null
                }
            }
        }

        override suspend fun speak(text: String, priority: Priority) = speak(text, priority, null)
        override suspend fun speak(text: String, priority: Priority, tag: String?) {
            handed += tag
            queue.addLast(tag)
            wake.trySend(Unit)
        }
        override fun cancel(tag: String) {
            cancelled += tag
            if (current == tag) currentJob?.cancel()
            else if (queue.remove(tag)) out.tryEmit(UtteranceSignal.Dropped(VoicePath.PLEOS_TTS, tag, 0, "cancelled"))
        }
        override fun stop() = Unit
    }

    /** 끝 신호 없는 대역 — 받은 문장만 기록 */
    private class RecordingVoice : VoiceAdapter {
        val spoken = mutableListOf<String>()
        override val status: StateFlow<VoiceAdapterStatus> = MutableStateFlow(VoiceAdapterStatus(ready = true))
        override suspend fun speak(text: String, priority: Priority) { spoken += text }
        override fun stop() = Unit
    }

    private val events = MutableSharedFlow<ReceivedGroupEvent>(extraBufferCapacity = 32)

    private fun TestScope.event(id: String, type: GroupEventType, speech: String, severity: Severity = Severity.INFO, source: String? = "veh_A") =
        ReceivedGroupEvent(GroupEvent(id, type.name, source, severity, speech, JsonObject(emptyMap())), envelopeTsMs = testScheduler.currentTime, receivedAtMs = testScheduler.currentTime)

    private fun TestScope.announcer(voice: VoiceAdapter, groupIds: kotlinx.coroutines.flow.Flow<String?> = kotlinx.coroutines.flow.emptyFlow()) =
        VoiceAnnouncer(
            events, { "veh_ME" }, voice, dispatcher = StandardTestDispatcher(testScheduler), clock = { testScheduler.currentTime },
            groupIds = groupIds,
        )

    @Test
    fun `HARD_BRAKE is read right after the current sentence even when LOW sentences arrived first on a slow engine (B011)`() = runTest {
        // Pleos 부하: 요청 → 시작 7 s, 읽기 3 s
        val voice = SlowSerialVoice(backgroundScope, startMs = 7_000, readMs = 3_000)
        val a = announcer(voice)
        a.start(); runCurrent()
        try {
            events.emit(event("low_1", GroupEventType.MEMBER_JOINED, "삼촌 차가 그룹에 들어왔어요.")); runCurrent()
            events.emit(event("low_2", GroupEventType.STOPPED, "엄마 차가 멈췄어요.")); runCurrent()
            events.emit(event("low_3", GroupEventType.DESTINATION_ARRIVED, "이모 차가 도착했어요.")); runCurrent()
            advanceTimeBy(6_500); runCurrent()
            assertEquals("어댑터에는 한 문장만 넘긴다(끝 신호를 기다림)", listOf("low_1"), voice.handed)
            events.emit(event("brake", GroupEventType.HARD_BRAKE, "앞쪽 아빠 차가 급제동했어요.", Severity.WARN)); runCurrent()
            advanceTimeBy(30_000); runCurrent()
            assertEquals("급제동이 둘째로 읽힌다(LOW 3개 뒤에 밀리지 않음)", listOf("low_1", "brake"), voice.played.take(2))
            assertEquals(listOf("low_1", "brake", "low_2"), voice.handed.take(3))
        } finally {
            a.stop()
        }
    }

    @Test
    fun `opening the mic stops a NORMAL sentence already handed to the engine and reads it again after the hold (B013)`() = runTest {
        val voice = SlowSerialVoice(backgroundScope, startMs = 5_000, readMs = 2_000)
        val a = announcer(voice)
        a.start(); runCurrent()
        try {
            events.emit(event("low_energy", GroupEventType.LOW_ENERGY, "엄마 차 연료가 12% 남았어요.", Severity.WARN)); runCurrent()
            advanceTimeBy(1_000); runCurrent()
            assertEquals(listOf("low_energy"), voice.handed)
            // 무전 버튼 → 녹음을 열기 직전(PttController.micOpening) + 상태 기계 붙잡기(PttRuntime)
            a.yieldToMic()
            a.setHeld(true)
            runCurrent()
            assertEquals("넘긴 문장만 버린다", listOf("low_energy"), voice.cancelled)
            advanceTimeBy(10_000); runCurrent()
            assertTrue("녹음 중에는 나오지 않는다", voice.played.isEmpty())
            assertEquals("무전 뒤 다시 읽으려고 큐에 다시 섰다", 1, a.state.value.queued)
            a.setHeld(false); runCurrent()
            advanceTimeBy(10_000); runCurrent()
            assertEquals(listOf("low_energy", "low_energy"), voice.handed)
            assertEquals("끝까지 한 번 읽는다", listOf("low_energy"), voice.played)
            assertEquals(1, a.state.value.yieldedToMic)
        } finally {
            a.stop()
        }
    }

    @Test
    fun `a HIGH sentence on the engine is not stopped when the mic opens`() = runTest {
        val voice = SlowSerialVoice(backgroundScope, startMs = 5_000, readMs = 2_000)
        val a = announcer(voice)
        a.start(); runCurrent()
        try {
            events.emit(event("brake", GroupEventType.HARD_BRAKE, "앞쪽 아빠 차가 급제동했어요.", Severity.WARN)); runCurrent()
            advanceTimeBy(1_000); runCurrent()
            a.yieldToMic(); runCurrent()
            assertTrue(voice.cancelled.isEmpty())
            advanceTimeBy(10_000); runCurrent()
            assertEquals(listOf("brake"), voice.played)
        } finally {
            a.stop()
        }
    }

    @Test
    fun `the short mic hold ends by itself when the recorder never starts`() = runTest {
        val voice = RecordingVoice()
        val a = announcer(voice)
        a.start(); runCurrent()
        try {
            a.yieldToMic()
            advanceTimeBy(500); runCurrent()
            events.emit(event("pm", GroupEventType.PASSENGER_MESSAGE, "동승자 메시지. 천천히 와")); runCurrent()
            advanceTimeBy(2_000); runCurrent()
            assertTrue("녹음 시작 붙잡기 동안은 기다린다", voice.spoken.isEmpty())
            advanceTimeBy(1_000); runCurrent()
            assertEquals("3초가 지나면 스스로 풀린다(붙잡기가 영영 걸리지 않게)", listOf("동승자 메시지. 천천히 와"), voice.spoken)
        } finally {
            a.stop()
        }
    }

    @Test
    fun `when the group ends, sentences queued from it are dropped except the group-ended one (B060)`() = runTest {
        val voice = RecordingVoice()
        val group = MutableStateFlow<String?>("grp_1")
        val a = announcer(voice, groupIds = group)
        a.start(); runCurrent()
        try {
            a.setHeld(true)   // 받은 무전 재생 중 — NORMAL·LOW 가 쌓인다
            events.emit(event("stop", GroupEventType.STOPPED, "삼촌 차가 멈췄어요.")); runCurrent()
            events.emit(event("pm", GroupEventType.PASSENGER_MESSAGE, "동승자 메시지. 다 왔어")); runCurrent()
            events.emit(event("end", GroupEventType.GROUP_ENDED, "그룹이 종료됐어요.", source = null)); runCurrent()
            assertEquals(3, a.state.value.queued)
            group.value = null; runCurrent()
            a.setHeld(false); runCurrent()
            advanceTimeBy(30_000); runCurrent()
            assertEquals(listOf("그룹이 종료됐어요."), voice.spoken)
            assertEquals(2, a.state.value.droppedOldGroup)

            // 바로 다른 그룹으로 — 옛 그룹 문장이 새 그룹 소식처럼 들리지 않는다
            group.value = "grp_2"; runCurrent()
            a.setHeld(true)
            events.emit(event("join", GroupEventType.MEMBER_JOINED, "이모 차가 그룹에 들어왔어요.")); runCurrent()
            group.value = "grp_3"; runCurrent()
            a.setHeld(false); runCurrent()
            advanceTimeBy(30_000); runCurrent()
            assertFalse(voice.spoken.contains("이모 차가 그룹에 들어왔어요."))
        } finally {
            a.stop()
        }
    }

    @Test
    fun `the voice switch is memory only - a diagnostic toggle does not survive a restart, saving is UserSettings' job (RB-5)`() = runTest {
        val a = announcer(RecordingVoice())
        a.setEnabled(false)                       // 진단 카드 [음성 안내 끄기]·디버그 리시버 voice
        assertFalse(a.state.value.enabled)
        val restarted = announcer(RecordingVoice())
        assertTrue("앱을 다시 시작하면 켜진 채 시작 — 설정 화면에서 끈 값만 A120 UserSettings 가 다시 넣는다", restarted.state.value.enabled)
        // 저장 경로가 둘이 되지 않게(설정 화면 저장 dh_settings 와 따로 dh_voice 에 진단 토글까지 저장하던 B066 판) — 음성 엔진 쪽은 SharedPreferences 를 쓰지 않는다
        val base = listOf(java.io.File("src"), java.io.File("app/src")).first { java.io.File(it, "main/kotlin").isDirectory }
        val voiceDomain = java.io.File(base, "main/kotlin/app/donghaeng/car/domain/voice")
        assertTrue(voiceDomain.isDirectory)
        val offenders = voiceDomain.walkTopDown().filter { it.isFile && it.extension == "kt" && it.readText().contains("getSharedPreferences") }.map { it.name }.toList()
        assertEquals("domain/voice 는 음성 안내 스위치를 저장하지 않는다", emptyList<String>(), offenders)
    }
}
