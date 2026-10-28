package app.donghaeng.car.domain.voice

import app.donghaeng.car.adapter.voice.UtteranceSignal
import app.donghaeng.car.adapter.voice.VoiceAdapter
import app.donghaeng.car.adapter.voice.VoiceAdapterStatus
import app.donghaeng.car.adapter.voice.VoiceLatencyStats
import app.donghaeng.car.adapter.voice.VoicePath
import app.donghaeng.car.domain.model.Priority
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** eventId 별 음성 시작 신호 + 지금 경로·평균 지연 → 표면이 보는 VoiceCue */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceCueTrackerTest {

    private class FakeVoice : VoiceAdapter {
        val st = MutableStateFlow(VoiceAdapterStatus())
        val out = MutableSharedFlow<UtteranceSignal>(extraBufferCapacity = 16)
        override val status: StateFlow<VoiceAdapterStatus> = st
        override val signals: SharedFlow<UtteranceSignal> = out
        override suspend fun speak(text: String, priority: Priority) = Unit
        override fun stop() = Unit
    }

    @Test
    fun `starts are keyed by tag with the first start time, path and average follow the chain status`() = runTest {
        val v = FakeVoice()
        val t = VoiceCueTracker(v, backgroundScope)
        runCurrent()
        v.st.value = VoiceAdapterStatus(path = VoicePath.PLEOS_TTS, latency = VoiceLatencyStats(lastMs = 6_000, avgMs = 6_000, maxMs = 6_000, count = 1))
        v.out.emit(UtteranceSignal.Started(VoicePath.PLEOS_TTS, "evt_1", 1_000, 6_000))
        v.out.emit(UtteranceSignal.Started(VoicePath.PLEOS_TTS, "evt_1", 9_000, 1_000))   // 같은 꼬리표 → 처음 값 유지
        v.out.emit(UtteranceSignal.Started(VoicePath.PLEOS_TTS, null, 2_000, 1_000))      // 꼬리표 없는 문장(테스트 발화)은 무시
        v.out.emit(UtteranceSignal.Failed(VoicePath.PLEOS_TTS, "evt_2", 3_000, "x"))      // 시작이 아니면 무시
        runCurrent()
        assertEquals(VoiceCue(VoicePath.PLEOS_TTS, 6_000, mapOf("evt_1" to 1_000L)), t.state.value)
    }

    @Test
    fun `only the latest 32 starts are kept`() {
        var m = emptyMap<String, Long>()
        (1..40).forEach { m = VoiceCueTracker.add(m, "evt_$it", it.toLong()) }
        assertEquals(VoiceCueTracker.MAX_STARTS, m.size)
        assertEquals("evt_9", m.keys.first())
        assertEquals("evt_40", m.keys.last())
    }
}
