package app.donghaeng.car.domain.voice

import app.donghaeng.car.adapter.voice.VoiceAdapter
import app.donghaeng.car.adapter.voice.VoiceAdapterStatus
import app.donghaeng.car.domain.model.Priority
import app.donghaeng.car.domain.session.ReceivedGroupEvent
import app.donghaeng.protocol.GroupEvent
import app.donghaeng.protocol.GroupEventType
import app.donghaeng.protocol.Severity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceAnnouncerTest {

    private class RecordingVoice : VoiceAdapter {
        val spoken = mutableListOf<Pair<String, Priority>>()
        var stops = 0
        override val status: StateFlow<VoiceAdapterStatus> = MutableStateFlow(VoiceAdapterStatus(ready = true))
        override suspend fun speak(text: String, priority: Priority) { spoken += text to priority }
        override fun stop() { stops++ }
    }

    private val events = MutableSharedFlow<ReceivedGroupEvent>(extraBufferCapacity = 32)
    private val voice = RecordingVoice()
    private var seq = 0

    private fun event(type: GroupEventType, speech: String?, source: String? = "veh_OTHER", severity: Severity = Severity.INFO, ageMs: Long = 0, now: Long) =
        ReceivedGroupEvent(GroupEvent("evt_${seq++}", type.name, source, severity, speech, JsonObject(emptyMap())), envelopeTsMs = now - ageMs, receivedAtMs = now)

    private fun announcer(scheduler: kotlinx.coroutines.test.TestCoroutineScheduler) =
        VoiceAnnouncer(events, { "veh_ME" }, voice, dispatcher = StandardTestDispatcher(scheduler), clock = { scheduler.currentTime })

    @Test
    fun `speaks fresh events from other cars by priority, skips own vehicle events, stale and duplicates`() = runTest {
        val a = announcer(testScheduler)
        a.start()
        runCurrent()   // 수집기·워커가 구독을 시작할 시간
        try {
            val now = testScheduler.currentTime
            events.emit(event(GroupEventType.MEMBER_JOINED, "삼촌 차가 그룹에 들어왔어요.", now = now))                 // LOW → 즉시
            runCurrent()
            assertEquals(1, voice.spoken.size)
            events.emit(event(GroupEventType.LOW_ENERGY, "엄마 차 연료가 12% 남았어요.", severity = Severity.WARN, now = now))          // NORMAL
            events.emit(event(GroupEventType.HARD_BRAKE, "앞쪽 아빠 차가 급제동했어요. 주의하세요.", severity = Severity.WARN, now = now))  // HIGH → 먼저 읽힘
            runCurrent()
            assertEquals("첫 문장을 읽는 동안은 큐에 머문다", 1, voice.spoken.size)
            assertEquals(2, a.state.value.queued)
            advanceTimeBy(60_000); runCurrent()
            assertEquals(listOf(Priority.LOW, Priority.HIGH, Priority.NORMAL), voice.spoken.map { it.second })
            assertEquals("앞쪽 아빠 차가 급제동했어요. 주의하세요.", voice.spoken[1].first)
            assertEquals(3, a.state.value.spoken)

            val t2 = testScheduler.currentTime
            events.emit(event(GroupEventType.HARD_BRAKE, "앞쪽 나가 급제동했어요.", source = "veh_ME", now = t2))         // 내 차 → 생략
            events.emit(event(GroupEventType.PASSENGER_MESSAGE, "나에서 온 메시지. 천천히 와", source = "veh_ME", now = t2)) // 내 차 출처여도 읽음
            events.emit(event(GroupEventType.LOW_ENERGY, "오래된 소식", ageMs = 6_000, now = t2))                        // 5초 넘음 → 생략
            events.emit(event(GroupEventType.PASSENGER_MESSAGE, "나에서 온 메시지. 천천히 와", source = "veh_ME", now = t2)) // 중복 → 생략
            events.emit(event(GroupEventType.STOPPED, null, now = t2))                                                  // 문구 없음 → 생략
            advanceTimeBy(60_000); runCurrent()
            assertEquals(4, voice.spoken.size)
            assertEquals("나에서 온 메시지. 천천히 와", voice.spoken.last().first)
            val s = a.state.value
            assertEquals(1, s.skippedOwn); assertEquals(1, s.skippedStale); assertEquals(1, s.skippedDuplicate); assertEquals(1, s.skippedNoSpeech)
            assertEquals("PASSENGER_MESSAGE", s.lastSpokenType)
        } finally {
            a.stop()
        }
    }

    @Test
    fun `critical events interrupt immediately and disabling drops everything`() = runTest {
        val a = announcer(testScheduler)
        a.start()
        runCurrent()
        try {
            val now = testScheduler.currentTime
            events.emit(event(GroupEventType.LOW_ENERGY, "엄마 차 연료가 12% 남았어요.", severity = Severity.WARN, now = now))
            runCurrent()
            events.emit(event(GroupEventType.MEMBER_JOINED, "삼촌 차가 그룹에 들어왔어요.", now = now))
            runCurrent()
            assertEquals(1, voice.spoken.size)
            assertEquals(1, a.state.value.queued)
            events.emit(event(GroupEventType.AIRBAG_DEPLOYED, "엄마 차 에어백이 터졌어요. 확인해 주세요.", severity = Severity.ALERT, now = now))
            runCurrent()
            assertEquals(Priority.CRITICAL, voice.spoken.last().second)
            assertEquals("critical clears the queue", 0, a.state.value.queued)
            advanceTimeBy(60_000); runCurrent()
            assertEquals("queued LOW event was dropped by the critical one", 2, voice.spoken.size)

            a.setEnabled(false)
            runCurrent()
            assertTrue(voice.stops >= 1)
            events.emit(event(GroupEventType.HARD_BRAKE, "앞쪽 아빠 차가 급제동했어요.", severity = Severity.WARN, now = testScheduler.currentTime))
            advanceTimeBy(5_000); runCurrent()
            assertEquals(2, voice.spoken.size)
            assertEquals(1, a.state.value.skippedDisabled)
        } finally {
            a.stop()
        }
    }

    @Test
    fun `priority mapping`() {
        assertEquals(Priority.CRITICAL, VoiceAnnouncer.priorityOf(GroupEventType.AIRBAG_DEPLOYED, Severity.ALERT))
        assertEquals(Priority.HIGH, VoiceAnnouncer.priorityOf(GroupEventType.HARD_BRAKE, Severity.WARN))
        assertEquals(Priority.NORMAL, VoiceAnnouncer.priorityOf(GroupEventType.LOW_ENERGY, Severity.WARN))
        assertEquals(Priority.NORMAL, VoiceAnnouncer.priorityOf(GroupEventType.PASSENGER_MESSAGE, Severity.INFO))
        assertEquals(Priority.LOW, VoiceAnnouncer.priorityOf(GroupEventType.MEMBER_JOINED, Severity.INFO))
        assertEquals("ALERT bumps to HIGH", Priority.HIGH, VoiceAnnouncer.priorityOf(GroupEventType.MEMBER_JOINED, Severity.ALERT))
        assertEquals(Priority.LOW, VoiceAnnouncer.priorityOf(null, Severity.INFO))
    }
}
