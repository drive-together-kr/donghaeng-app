package app.donghaeng.car.domain.ptt

import app.donghaeng.car.adapter.audio.FakeVoiceClipPlayer
import app.donghaeng.car.adapter.audio.FakeVoiceRecorder
import app.donghaeng.car.adapter.speech.FakeYesNoRecognizer
import app.donghaeng.car.adapter.speech.ListenResult
import app.donghaeng.car.adapter.speech.RecognizerChain
import app.donghaeng.car.adapter.speech.RecognizerKind
import app.donghaeng.car.adapter.trigger.FakeVoiceTrigger
import app.donghaeng.car.adapter.trigger.PttTriggerSource
import app.donghaeng.car.adapter.voice.SpeakResult
import app.donghaeng.car.adapter.voice.VoiceAdapter
import app.donghaeng.car.adapter.voice.VoiceAdapterStatus
import app.donghaeng.car.domain.model.Priority
import app.donghaeng.car.domain.session.GroupSession
import app.donghaeng.car.domain.session.ReceivedGroupEvent
import app.donghaeng.car.feature.drive.PttDisplay
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * F16 사용자 결정 2026-10-28 ③ — 받은 무전 재생 중 누름: 재생을 끊고 녹음 시작(그대로) + 끊긴 무전 '다 못 들음' 표시 +
 * **내 녹음·확인 흐름이 IDLE 로 끝나면(보냄·취소·보관·짧음·실패 어느 쪽이든) 처음부터 자동 재생**, 다 들으면 들음.
 * 끊김 → 표시 → 흐름 끝 → 처음부터 → 들음 / 흐름 중 다른 무전 도착 순서 / 그룹 종료 시 버림 / 사이클 번호 판정(순수).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PttResumeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 알림은 [announceMs] 걸리고, PTT 안내(speakAndWait "보낼까요?")는 바로 끝난다 */
    private class Voice : VoiceAdapter {
        val waited = mutableListOf<String>()
        var announceMs = 0L
        override val status: StateFlow<VoiceAdapterStatus> = MutableStateFlow(VoiceAdapterStatus(ready = true))
        override suspend fun speak(text: String, priority: Priority) = Unit
        override suspend fun speakAndWait(text: String, priority: Priority, timeoutMs: Long): SpeakResult {
            waited += text
            if (text.endsWith("무전")) delay(announceMs)
            return SpeakResult.SPOKEN
        }
        override fun stop() = Unit
    }

    private inner class Rig(scope: TestScope, answer: YesNo? = YesNo.NO) {
        val scheduler = scope.testScheduler
        val clock = { scheduler.currentTime }
        val dispatcher = StandardTestDispatcher(scheduler)
        val session = MutableStateFlow<GroupSession?>(null)
        val events = MutableSharedFlow<ReceivedGroupEvent>(extraBufferCapacity = 8)
        val trigger = FakeVoiceTrigger(PttSettings(InMemoryPttSettingsStore(true)), clock)
        val vosk = FakeYesNoRecognizer(RecognizerKind.VOSK).apply { if (answer != null) then(300, ListenResult.Heard(answer, 0.9f)) }
        val chain = RecognizerChain(listOf(vosk), clock)
        val uploads = mutableListOf<Long>()
        val controller = PttController(
            recorder = FakeVoiceRecorder(tmp.newFolder(), clock), listener = chain, voice = Voice(),
            sender = { _, clip -> uploads += clip.durationMs; SendOutcome.Sent("vm_up") },
            groupId = { session.value?.groupId }, recipients = { 1 },
            dispatcher = dispatcher, clock = clock,
        )
        val dir: File = tmp.newFolder()
        val player = FakeVoiceClipPlayer(defaultDurationMs = 4_000)
        val voice = Voice()
        val playback = VoicePlayback(
            events = events, myVehicleId = { "veh_ME" }, groupState = { null }, session = session, serverNow = clock,
            fetcher = { _, id, _ -> File(dir, "$id.m4a").apply { writeBytes(ByteArray(4)) } },
            player = player, voice = voice, dispatcher = dispatcher,
        )
        var ui = PttUi()
        val runtime = PttRuntime(trigger, controller, playback, session, holdAnnouncer = {}, publish = { c, p, u -> ui = PttUiMapper.map(c, p, u.size) }, dispatcher = dispatcher)
        var seq = 0

        fun begin() {
            runtime.start()
            scheduler.runCurrent()
            session.value = GroupSession("grp_1", "귀성길", "veh_A", Place(37.0, 127.0, "집"), GroupTopics("s", "e"), 0L)
            scheduler.runCurrent()
        }

        fun incoming(voiceId: String, from: String = "veh_A") {
            events.tryEmit(
                ReceivedGroupEvent(
                    GroupEvent("evt_${seq++}", "VOICE_MESSAGE", from, Severity.INFO, "아빠 차 무전", DhJson.payloadOf(VoiceMessagePayload(voiceId, 4_000, "audio/mp4", 8_000, 0L))),
                    envelopeTsMs = scheduler.currentTime, receivedAtMs = scheduler.currentTime,
                ),
            )
            scheduler.runCurrent()
        }

        fun press() {
            trigger.press(PttTriggerSource.PANEL)
            scheduler.runCurrent()
        }

        val past get() = playback.state.value.past
        val stage get() = controller.state.value.stage
    }

    @Test
    fun `끊김 → 다 못 들음 → 녹음·확인(아니) → 안내 3초 동안은 아직 → IDLE 에 처음부터 → 다 들으면 들음`() = runTest {
        val r = Rig(this, answer = YesNo.NO)
        r.begin()
        r.incoming("vm_in")
        advanceTimeBy(1_500); runCurrent()
        assertTrue(r.playback.isPlaying)
        assertEquals(1_500L, r.playback.state.value.playing!!.positionMs)

        // 재생 중 누름 → 끊고 녹음(그대로)
        r.press()
        assertEquals(PttStage.RECORDING, r.stage)
        assertEquals(PttPhase.RECORDING, r.ui.phase)
        val shown = r.past.single()
        assertEquals(PastStatus.INTERRUPTED, shown.status)
        assertEquals("정차 대시보드 지난 무전 줄", "다 못 들음", PttDisplay.pastRows(r.past, serverNowMs = currentTime).single().statusText)
        assertTrue(r.playback.state.value.resumeWaiting)

        // 녹음 2초 → 끝 → '보낼까요?' → 아니 → 취소(안내 3초)
        advanceTimeBy(2_000); runCurrent()
        r.press()
        assertEquals(PttStage.CONFIRMING, r.stage)
        advanceTimeBy(300); runCurrent()
        assertEquals(PttStage.NOTICE, r.stage)
        assertEquals(PttNotice.CANCELLED, r.controller.state.value.notice)
        advanceTimeBy(2_900); runCurrent()
        assertEquals("안내(3초)가 끝나기 전 — 흐름이 아직 IDLE 이 아니다", listOf("vm_in.m4a"), r.player.played)

        // IDLE → 처음부터(알림부터)
        advanceTimeBy(100); runCurrent()
        assertEquals(PttStage.IDLE, r.stage)
        assertEquals(listOf("vm_in.m4a", "vm_in.m4a"), r.player.played)
        assertEquals(listOf("아빠 차 무전", "아빠 차 무전"), r.voice.waited)
        assertEquals(PttPhase.PLAYING, r.ui.phase)
        assertEquals("처음부터", 0, r.ui.seconds)
        assertFalse(r.playback.state.value.resumeWaiting)
        advanceTimeBy(4_000); runCurrent()
        assertEquals("다 들으면 들음", PastStatus.PLAYED, r.past.single().status)
        assertEquals(null, PttDisplay.pastRows(r.past, serverNowMs = currentTime).single().statusText)
        r.runtime.stop()
    }

    @Test
    fun `보냄으로 끝나도 IDLE 에 처음부터 — 흐름 중 도착한 무전은 끊긴 무전 다음`() = runTest {
        val r = Rig(this, answer = YesNo.YES)
        r.begin()
        r.incoming("vm_a")
        advanceTimeBy(1_000); runCurrent()
        r.press()
        assertEquals(PastStatus.INTERRUPTED, r.past.single().status)
        // 녹음하는 동안 다른 차 무전이 온다
        advanceTimeBy(500); runCurrent()
        r.incoming("vm_b", from = "veh_C")
        advanceTimeBy(1_500); runCurrent()
        r.press()   // 녹음 끝 → 네 → 보냄 → 'N대에 보냈어요' 3초
        advanceTimeBy(300); runCurrent()
        assertEquals(PttStage.SENT, r.stage)
        assertEquals(listOf(2_000L), r.uploads)
        advanceTimeBy(2_900); runCurrent()
        assertEquals("보냄 3초 동안 — 끊긴 무전도, 새 무전도 아직", listOf("vm_a.m4a"), r.player.played)
        advanceTimeBy(100); runCurrent()
        assertEquals(PttStage.IDLE, r.stage)
        assertEquals("끊긴 무전 먼저", listOf("vm_a.m4a", "vm_a.m4a"), r.player.played)
        advanceTimeBy(4_000 + VoicePlayback.GAP_MS); runCurrent()
        assertEquals("그다음 새 무전", listOf("vm_a.m4a", "vm_a.m4a", "vm_b.m4a"), r.player.played)
        advanceTimeBy(4_000); runCurrent()
        assertEquals(listOf("vm_b", "vm_a"), r.past.map { it.voiceId })
        assertTrue(r.past.all { it.status == PastStatus.PLAYED })
        r.runtime.stop()
    }

    @Test
    fun `너무 짧아요로 끝나도 IDLE 에 처음부터`() = runTest {
        val r = Rig(this, answer = null)
        r.begin()
        r.incoming("vm_a")
        advanceTimeBy(800); runCurrent()
        r.press()
        advanceTimeBy(300); runCurrent()
        r.press()   // 0.3초 → 너무 짧아요(안내 3초)
        assertEquals(PttNotice.TOO_SHORT, r.controller.state.value.notice)
        advanceTimeBy(2_900); runCurrent()
        assertEquals(listOf("vm_a.m4a"), r.player.played)
        advanceTimeBy(100); runCurrent()
        assertEquals(listOf("vm_a.m4a", "vm_a.m4a"), r.player.played)
        r.runtime.stop()
    }

    @Test
    fun `다시 틀던 무전을 또 누르면 다시 끊기고, 그 흐름이 끝나면 또 처음부터`() = runTest {
        val r = Rig(this, answer = YesNo.NO)
        r.begin()
        r.incoming("vm_a")
        advanceTimeBy(500); runCurrent()
        r.press(); advanceTimeBy(300); runCurrent(); r.press()   // 너무 짧아요
        advanceTimeBy(3_000); runCurrent()
        assertEquals(2, r.player.played.size)
        advanceTimeBy(1_000); runCurrent()
        assertTrue(r.playback.isPlaying)
        r.press()   // 다시 트는 중 또 누름
        assertEquals(PastStatus.INTERRUPTED, r.past.single().status)
        advanceTimeBy(300); runCurrent(); r.press()   // 너무 짧아요
        advanceTimeBy(3_000); runCurrent()
        assertEquals(3, r.player.played.size)
        advanceTimeBy(4_000); runCurrent()
        assertEquals(PastStatus.PLAYED, r.past.single().status)
        r.runtime.stop()
    }

    @Test
    fun `흐름 중 그룹이 끝나면 끊긴 무전은 버린다 — 다시 틀지 않는다`() = runTest {
        val r = Rig(this, answer = YesNo.NO)
        r.begin()
        r.incoming("vm_a")
        advanceTimeBy(800); runCurrent()
        r.press()
        assertEquals(PttStage.RECORDING, r.stage)
        r.session.value = null
        runCurrent()
        assertEquals(PttStage.IDLE, r.stage)
        assertTrue(r.past.isEmpty())
        assertEquals(0, r.playback.state.value.queued)
        assertFalse(File(r.dir, "vm_a.m4a").exists())
        advanceTimeBy(30_000); runCurrent()
        assertEquals(listOf("vm_a.m4a"), r.player.played)
        r.runtime.stop()
    }

    // ---------------------------------------------------------------- 사이클 번호 판정(순수)

    @Test
    fun `ResumeAfterFlow — 누름 뒤 시작된 사이클이 IDLE 로 끝나야 한 번 푼다`() {
        val w = ResumeAfterFlow()
        assertFalse(w.onState(PttState(stage = PttStage.IDLE, cycleSeq = 3, idleCycleSeq = 3)))
        // IDLE(사이클 3 끝)에서 누름 → 새 사이클 4 가 끝나야
        w.arm(PttState(stage = PttStage.IDLE, cycleSeq = 3, idleCycleSeq = 3))
        assertTrue(w.armed)
        assertFalse("누름이 닿기 전 옛 IDLE", w.onState(PttState(stage = PttStage.IDLE, cycleSeq = 3, idleCycleSeq = 3)))
        assertFalse("새 사이클 번호만 올라간 IDLE(녹음 시작 직전)", w.onState(PttState(stage = PttStage.IDLE, cycleSeq = 4, idleCycleSeq = 3)))
        assertFalse(w.onState(PttState(stage = PttStage.RECORDING, cycleSeq = 4, idleCycleSeq = 3)))
        assertFalse(w.onState(PttState(stage = PttStage.NOTICE, cycleSeq = 4, idleCycleSeq = 3)))
        assertTrue(w.onState(PttState(stage = PttStage.IDLE, cycleSeq = 4, idleCycleSeq = 4)))
        assertFalse("한 번만", w.onState(PttState(stage = PttStage.IDLE, cycleSeq = 5, idleCycleSeq = 5)))
        assertFalse(w.armed)
    }

    @Test
    fun `ResumeAfterFlow — 안내 중 누름(옛 사이클이 막 끝나도 안 풂), 전송 중 누름(지금 사이클이 끝나면 풂), 그룹 변경은 해제`() {
        val w = ResumeAfterFlow()
        // 안내(사이클 7) 중 누름 — 사이클 7 이 저절로 끝나며 남긴 IDLE 은 세지 않는다
        w.arm(PttState(stage = PttStage.NOTICE, cycleSeq = 7, idleCycleSeq = 6))
        assertFalse(w.onState(PttState(stage = PttStage.IDLE, cycleSeq = 7, idleCycleSeq = 7)))
        assertTrue(w.onState(PttState(stage = PttStage.IDLE, cycleSeq = 8, idleCycleSeq = 8)))

        // 전송 중(바쁨) 누름은 무시된다 — 지금 사이클 9 가 끝나면 푼다
        w.arm(PttState(stage = PttStage.SENDING, cycleSeq = 9, idleCycleSeq = 8))
        assertFalse(w.onState(PttState(stage = PttStage.SENT, cycleSeq = 9, idleCycleSeq = 8)))
        assertTrue(w.onState(PttState(stage = PttStage.IDLE, cycleSeq = 9, idleCycleSeq = 9)))

        w.arm(PttState(stage = PttStage.IDLE, cycleSeq = 9, idleCycleSeq = 9))
        w.disarm()
        assertFalse(w.onState(PttState(stage = PttStage.IDLE, cycleSeq = 10, idleCycleSeq = 10)))
    }
}
