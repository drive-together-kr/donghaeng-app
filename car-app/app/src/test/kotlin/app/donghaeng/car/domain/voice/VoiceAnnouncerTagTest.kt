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
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** 이벤트 문장에는 eventId 꼬리표가 붙는다 — 어댑터 시작 신호로 표면이 위험 이벤트 강조를 푼다(VoiceCueTracker) */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceAnnouncerTagTest {

    private class TaggingVoice : VoiceAdapter {
        val tags = mutableListOf<Pair<Priority, String?>>()
        override val status: StateFlow<VoiceAdapterStatus> = MutableStateFlow(VoiceAdapterStatus(ready = true))
        override suspend fun speak(text: String, priority: Priority) = speak(text, priority, null)
        override suspend fun speak(text: String, priority: Priority, tag: String?) { tags += priority to tag }
        override fun stop() = Unit
    }

    @Test
    fun `event sentences carry the event id, test sentences carry none`() = runTest {
        val voice = TaggingVoice()
        val events = MutableSharedFlow<ReceivedGroupEvent>(extraBufferCapacity = 8)
        val a = VoiceAnnouncer(events, { "veh_ME" }, voice, dispatcher = StandardTestDispatcher(testScheduler), clock = { testScheduler.currentTime })
        a.start(); runCurrent()
        try {
            val now = testScheduler.currentTime
            fun e(id: String, type: GroupEventType, severity: Severity) =
                ReceivedGroupEvent(GroupEvent(id, type.name, "veh_A", severity, "$id 문구", JsonObject(emptyMap())), envelopeTsMs = now, receivedAtMs = now)
            events.emit(e("evt_brake", GroupEventType.HARD_BRAKE, Severity.WARN)); runCurrent()
            events.emit(e("evt_airbag", GroupEventType.AIRBAG_DEPLOYED, Severity.ALERT)); runCurrent()
            a.speakTest("예시", type = "SAMPLE"); runCurrent()
            assertEquals(listOf(Priority.HIGH to "evt_brake", Priority.CRITICAL to "evt_airbag", Priority.LOW to null), voice.tags)
            assertEquals("SAMPLE", a.state.value.lastSpokenType)
        } finally {
            a.stop()
        }
    }
}
