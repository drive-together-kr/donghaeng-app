package app.donghaeng.car.domain.ptt

import android.view.KeyEvent
import app.donghaeng.car.adapter.audio.FakeVoiceClipPlayer
import app.donghaeng.car.adapter.audio.FakeVoiceRecorder
import app.donghaeng.car.adapter.speech.FakeYesNoRecognizer
import app.donghaeng.car.adapter.speech.ListenResult
import app.donghaeng.car.adapter.speech.RecognizerChain
import app.donghaeng.car.adapter.speech.RecognizerKind
import app.donghaeng.car.adapter.trigger.FakeVoiceTrigger
import app.donghaeng.car.adapter.trigger.PttTriggerSource
import app.donghaeng.car.adapter.voice.VoiceAdapter
import app.donghaeng.car.adapter.voice.VoiceAdapterStatus
import app.donghaeng.car.domain.model.Priority
import app.donghaeng.car.domain.session.GroupSession
import app.donghaeng.car.domain.session.ReceivedGroupEvent
import app.donghaeng.car.feature.drive.PttPhase
import app.donghaeng.car.feature.drive.PttUi
import app.donghaeng.car.surface.PttUiMapper
import app.donghaeng.protocol.DhJson
import app.donghaeng.protocol.GroupEvent
import app.donghaeng.protocol.GroupTopics
import app.donghaeng.protocol.Place
import app.donghaeng.protocol.Severity
import app.donghaeng.protocol.VoiceMessagePayload
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** F16 배선 — 세션 → 트리거, 키 → 상태 기계, 보내기 중 재생 미룸, 재생 중 누르면 끊고 녹음, 음성 안내 붙잡기, SurfaceState.ptt 로 내보내기 */
@OptIn(ExperimentalCoroutinesApi::class)
class PttRuntimeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class QuietVoice : VoiceAdapter {
        override val status: StateFlow<VoiceAdapterStatus> = MutableStateFlow(VoiceAdapterStatus(ready = true))
        override suspend fun speak(text: String, priority: Priority) = Unit
        override fun stop() = Unit
    }

    @Test
    fun `핸들 키로 녹음 → 네(Vosk) → 보냄, 녹음 중 온 무전은 끝난 뒤 재생`() = runTest {
        val scheduler = testScheduler
        val clock = { scheduler.currentTime }
        val dispatcher = StandardTestDispatcher(scheduler)
        val session = MutableStateFlow<GroupSession?>(null)
        val events = MutableSharedFlow<ReceivedGroupEvent>(extraBufferCapacity = 8)
        val trigger = FakeVoiceTrigger(PttSettings(InMemoryPttSettingsStore(true)), clock)
        val vosk = FakeYesNoRecognizer(RecognizerKind.VOSK).then(600, ListenResult.Heard(YesNo.YES, 0.9f))
        val android = FakeYesNoRecognizer(RecognizerKind.ANDROID_STT).then(100, ListenResult.Failed("stt error=9", permanent = true))
        val chain = RecognizerChain(listOf(android, vosk), clock)
        val uploads = mutableListOf<Long>()
        val controller = PttController(
            recorder = FakeVoiceRecorder(tmp.newFolder(), clock), listener = chain, voice = QuietVoice(),
            sender = { _, clip -> uploads += clip.durationMs; SendOutcome.Sent("vm_up") },
            groupId = { session.value?.groupId }, recipients = { 1 },
            dispatcher = dispatcher, clock = clock,
        )
        val dir = tmp.newFolder()
        val player = FakeVoiceClipPlayer(defaultDurationMs = 2_000)
        val playback = VoicePlayback(
            events = events, myVehicleId = { "veh_ME" }, groupState = { null }, session = session, serverNow = clock,
            fetcher = { _, id, _ -> File(dir, "$id.m4a").apply { writeBytes(ByteArray(4)) } },
            player = player, voice = QuietVoice(), dispatcher = dispatcher,
        )
        val holds = mutableListOf<Boolean>()
        var ui = PttUi()
        val runtime = PttRuntime(trigger, controller, playback, session, holdAnnouncer = { holds += it }, publish = { c, p, u -> ui = PttUiMapper.map(c, p, u.size) }, dispatcher = dispatcher)
        runtime.start()
        runCurrent()
        assertFalse("그룹 전에는 세션 없음", trigger.sessionActive)

        session.value = GroupSession("grp_1", "귀성길", "veh_A", Place(37.0, 127.0, "집"), GroupTopics("s", "e"), 0L)
        runCurrent()
        assertTrue(trigger.sessionActive)

        // 핸들 키(MediaSession) → 녹음
        assertTrue(trigger.simulateMediaKey(KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.ACTION_DOWN, eventTimeMs = 0))
        runCurrent()
        assertEquals(PttPhase.RECORDING, ui.phase)
        assertEquals("녹음 중에는 음성 안내를 붙잡는다", listOf(false, true), holds)

        // 녹음 중 받은 무전 → 미룸
        events.tryEmit(
            ReceivedGroupEvent(
                GroupEvent("evt_v", "VOICE_MESSAGE", "veh_A", Severity.INFO, "아빠 차 무전", DhJson.payloadOf(VoiceMessagePayload("vm_in", 2_000, "audio/mp4", 8_000, 0L))),
                envelopeTsMs = scheduler.currentTime, receivedAtMs = scheduler.currentTime,
            ),
        )
        runCurrent()
        advanceTimeBy(2_000); runCurrent()
        assertTrue(player.played.isEmpty())
        assertEquals(1, ui.queuedIncoming)

        // 다시 누름 → 확인 → Android STT error 9 → Vosk 네 → 보냄
        assertTrue(trigger.simulateMediaKey(KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.ACTION_DOWN, eventTimeMs = 2_000))
        runCurrent()
        assertEquals(PttPhase.CONFIRMING, ui.phase)
        advanceTimeBy(700); runCurrent()
        assertEquals(listOf(2_000L), uploads)
        assertEquals(PttStage.SENT, controller.state.value.stage)
        assertEquals(1, controller.state.value.sentTo)
        assertEquals(YesNo.YES, controller.state.value.lastAnswer)
        assertEquals(RecognizerKind.VOSK, controller.state.value.lastRecognizer)

        // 보내기가 끝나자 미뤄 둔 무전 재생 — 화면은 'N대에 보냈어요' 3초보다 재생이 먼저
        assertEquals(listOf("vm_in.m4a"), player.played)
        assertEquals(PttPhase.PLAYING, ui.phase)
        assertEquals(0, ui.queuedIncoming)

        // 재생 중 누르면 끊고 새 녹음
        advanceTimeBy(500); runCurrent()
        trigger.press(PttTriggerSource.PANEL)
        runCurrent()
        assertEquals(PastStatus.INTERRUPTED, playback.state.value.past.first().status)
        assertEquals(PttPhase.RECORDING, ui.phase)

        // 그룹 끝 → 세션 내림·녹음 버림
        session.value = null
        runCurrent()
        assertFalse(trigger.sessionActive)
        assertEquals(PttPhase.IDLE, ui.phase)
        assertFalse(holds.last())
        runtime.stop()
    }
}
