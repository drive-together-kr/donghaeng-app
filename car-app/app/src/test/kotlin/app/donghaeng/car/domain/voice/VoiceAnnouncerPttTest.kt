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

/** F16 — VOICE_MESSAGE 문구는 VoicePlayback 몫(중복 방지), 무전 중에는 NORMAL·LOW 를 붙잡고 HIGH 이상은 그대로 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceAnnouncerPttTest {

    private class RecordingVoice : VoiceAdapter {
        val spoken = mutableListOf<String>()
        override val status: StateFlow<VoiceAdapterStatus> = MutableStateFlow(VoiceAdapterStatus(ready = true))
        override suspend fun speak(text: String, priority: Priority) { spoken += text }
        override fun stop() = Unit
    }

    private val events = MutableSharedFlow<ReceivedGroupEvent>(extraBufferCapacity = 16)
    private val voice = RecordingVoice()
    private var seq = 0

    private fun event(type: GroupEventType, speech: String, severity: Severity = Severity.INFO, now: Long) =
        ReceivedGroupEvent(GroupEvent("evt_${seq++}", type.name, "veh_OTHER", severity, speech, JsonObject(emptyMap())), envelopeTsMs = now, receivedAtMs = now)

    @Test
    fun `VOICE_MESSAGE 는 읽지 않는다`() = runTest {
        val a = VoiceAnnouncer(events, { "veh_ME" }, voice, dispatcher = StandardTestDispatcher(testScheduler), clock = { testScheduler.currentTime })
        a.start(); runCurrent()
        events.emit(event(GroupEventType.VOICE_MESSAGE, "아빠 차 무전", now = testScheduler.currentTime))
        advanceTimeBy(10_000); runCurrent()
        assertTrue(voice.spoken.isEmpty())
        assertEquals(1, a.state.value.handedToPlayback)
        a.stop()
    }

    @Test
    fun `무전 중(held) — NORMAL 은 기다리고 HIGH 는 바로, 풀리면 이어 읽는다`() = runTest {
        val a = VoiceAnnouncer(events, { "veh_ME" }, voice, dispatcher = StandardTestDispatcher(testScheduler), clock = { testScheduler.currentTime })
        a.start(); runCurrent()
        a.setHeld(true)
        val now = testScheduler.currentTime
        events.emit(event(GroupEventType.LOW_ENERGY, "엄마 차 연료가 12% 남았어요.", Severity.WARN, now))
        runCurrent()
        assertTrue(voice.spoken.isEmpty())
        assertEquals(1, a.state.value.queued)
        events.emit(event(GroupEventType.HARD_BRAKE, "앞쪽 아빠 차가 급제동했어요.", Severity.WARN, now))
        runCurrent()
        assertEquals(listOf("앞쪽 아빠 차가 급제동했어요."), voice.spoken)
        advanceTimeBy(8_000); runCurrent()
        assertEquals("아직 붙잡혀 있다", 1, voice.spoken.size)
        a.setHeld(false)
        runCurrent()
        assertEquals(listOf("앞쪽 아빠 차가 급제동했어요.", "엄마 차 연료가 12% 남았어요."), voice.spoken)
        a.stop()
    }
}
