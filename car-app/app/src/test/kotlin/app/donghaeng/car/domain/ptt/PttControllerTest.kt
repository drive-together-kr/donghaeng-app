package app.donghaeng.car.domain.ptt

import app.donghaeng.car.adapter.audio.FakeVoiceRecorder
import app.donghaeng.car.adapter.audio.RecordedClip
import app.donghaeng.car.adapter.speech.ChainOutcome
import app.donghaeng.car.adapter.speech.RecognizerKind
import app.donghaeng.car.adapter.speech.YesNoListener
import app.donghaeng.car.adapter.voice.SpeakResult
import app.donghaeng.car.adapter.voice.VoiceAdapter
import app.donghaeng.car.adapter.voice.VoiceAdapterStatus
import app.donghaeng.car.domain.model.Priority
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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

/** F16 보내기 상태 기계 전 경로 — 가상 시간(녹음 15 s·최소 0.7 s·확인 5 s·카운트다운 5 s·보냄/안내 3 s) */
@OptIn(ExperimentalCoroutinesApi::class)
class PttControllerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class RecordingVoice : VoiceAdapter {
        val spoken = mutableListOf<String>()
        /** speakAndWait 가 걸리는 시간(질문 읽기) */
        var promptMs = 0L
        override val status: StateFlow<VoiceAdapterStatus> = MutableStateFlow(VoiceAdapterStatus(ready = true))
        override suspend fun speak(text: String, priority: Priority) { spoken += text }
        override suspend fun speakAndWait(text: String, priority: Priority, timeoutMs: Long): SpeakResult {
            spoken += text
            delay(promptMs)
            return SpeakResult.SPOKEN
        }
        override fun stop() = Unit
    }

    /** 대본대로 답하는 체인 대역 — (걸리는 ms, 결과) */
    private class ScriptedListener : YesNoListener {
        var candidate = true
        val outcomes = ArrayDeque<Pair<Long, ChainOutcome>>()
        var listens = 0
        var prepares = 0
        override fun hasCandidate(): Boolean = candidate
        override suspend fun prepare() { prepares++ }
        override suspend fun listen(timeoutMs: Long): ChainOutcome {
            listens++
            val (after, o) = outcomes.removeFirstOrNull() ?: (timeoutMs to ChainOutcome(YesNo.NONE, RecognizerKind.VOSK))
            delay(minOf(after, timeoutMs))
            return o
        }
        override fun describe(): String = "scripted"
        fun answer(afterMs: Long, a: YesNo?) = outcomes.addLast(afterMs to ChainOutcome(a, if (a == null) null else RecognizerKind.VOSK))

        /** 디버그 주입 답(카운트다운 중) */
        val injected = kotlinx.coroutines.channels.Channel<YesNo>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        override suspend fun awaitInjected(): YesNo = injected.receive()
    }

    private inner class Rig(scope: TestScope) {
        val scheduler = scope.testScheduler
        val clock = { scheduler.currentTime }
        val recorder = FakeVoiceRecorder(tmp.newFolder(), clock)
        val listener = ScriptedListener()
        val voice = RecordingVoice()
        val sent = mutableListOf<RecordedClip>()
        val sentFileExisted = mutableListOf<Boolean>()
        var outcome: SendOutcome = SendOutcome.Sent("vm_1")
        var driving = false
        var mic = true
        val controller = PttController(
            recorder = recorder, listener = listener, voice = voice,
            sender = { _, clip -> sent += clip; sentFileExisted += clip.file.isFile; delay(SEND_MS); outcome },
            groupId = { "grp_1" }, recipients = { 2 }, micGranted = { mic }, isDriving = { driving },
            dispatcher = StandardTestDispatcher(scheduler), clock = clock, wallClock = clock,
        )
        val state get() = controller.state.value

        fun begin() {
            controller.start()
            controller.onGroupChanged("grp_1")
            scheduler.runCurrent()
        }

        fun press() {
            controller.press("TEST")
            scheduler.runCurrent()
        }

        /** 누르고 [ms] 녹음한 뒤 다시 눌러 끝낸다 */
        fun record(ms: Long) {
            press()
            scheduler.advanceTimeBy(ms)
            scheduler.runCurrent()
            press()
        }
    }

    @Test
    fun `네 — 녹음 남은 초, 질문 뒤 듣기, 보내고 3초 뒤 IDLE`() = runTest {
        val r = Rig(this)
        r.begin()
        r.listener.answer(1_000, YesNo.YES)
        r.press()
        assertEquals(PttStage.RECORDING, r.state.stage)
        assertEquals(15, r.state.remainingS)
        assertEquals(15, r.state.totalS)
        advanceTimeBy(3_000); runCurrent()
        assertEquals(12, r.state.remainingS)
        assertEquals(0.5f, r.state.level, 0.001f)
        assertEquals("녹음하는 동안 인식기를 미리 준비", 1, r.listener.prepares)
        r.press()
        assertEquals(PttStage.CONFIRMING, r.state.stage)
        assertTrue(r.state.listening)
        assertFalse(r.state.countdownMode)
        assertEquals(5, r.state.remainingS)
        assertEquals(PttPrompts.ASK, r.voice.spoken.last())
        advanceTimeBy(1_000); runCurrent()
        assertEquals(PttStage.SENDING, r.state.stage)
        assertEquals(1, r.sent.size)
        assertEquals(3_000L, r.sent[0].durationMs)
        assertTrue(r.sentFileExisted[0])
        advanceTimeBy(SEND_MS); runCurrent()
        assertEquals(PttStage.SENT, r.state.stage)
        assertEquals(2, r.state.sentTo)
        assertFalse("보낸 파일은 지운다", r.sent[0].file.exists())
        assertEquals(YesNo.YES, r.state.lastAnswer)
        advanceTimeBy(3_000); runCurrent()
        assertEquals(PttStage.IDLE, r.state.stage)
        assertEquals(1, r.state.stats.sent)
        r.controller.stop()
    }

    @Test
    fun `확인 카운트다운 — 남은 초가 줄어든다`() = runTest {
        val r = Rig(this)
        r.begin()
        r.listener.answer(4_500, YesNo.YES)
        r.record(2_000)
        advanceTimeBy(2_100); runCurrent()
        assertEquals(3, r.state.remainingS)   // 5 - 2.1 = 2.9 → 올림 3
        advanceTimeBy(1_000); runCurrent()
        assertEquals(2, r.state.remainingS)
        r.controller.stop()
    }

    @Test
    fun `아니 — 버리고 취소했어요 3초`() = runTest {
        val r = Rig(this)
        r.begin()
        r.listener.answer(800, YesNo.NO)
        r.record(2_000)
        advanceTimeBy(800); runCurrent()
        assertEquals(PttStage.NOTICE, r.state.stage)
        assertEquals(PttNotice.CANCELLED, r.state.notice)
        assertTrue(r.sent.isEmpty())
        assertTrue(r.controller.unsent.value.isEmpty())
        assertTrue(r.voice.spoken.contains(PttPrompts.CANCELLED))
        advanceTimeBy(3_000); runCurrent()
        assertEquals(PttStage.IDLE, r.state.stage)
        assertEquals(1, r.state.stats.cancelled)
        r.controller.stop()
    }

    @Test
    fun `무응답 — 보내지 않은 무전에 보관`() = runTest {
        val r = Rig(this)
        r.begin()
        r.listener.answer(5_000, YesNo.NONE)
        r.record(4_000)
        advanceTimeBy(5_000); runCurrent()
        assertEquals(PttNotice.KEPT_UNSENT, r.state.notice)
        val kept = r.controller.unsent.value.single()
        assertEquals(UnsentReason.NO_ANSWER, kept.reason)
        assertEquals(4_000L, kept.durationMs)
        assertTrue("보관한 파일은 남긴다", kept.file.isFile)
        assertTrue(r.voice.spoken.contains(PttPrompts.KEPT))
        advanceTimeBy(3_000); runCurrent()
        assertEquals(PttStage.IDLE, r.state.stage)
        assertTrue("안내가 끝나도 파일은 남는다", kept.file.isFile)
        r.controller.stop()
    }

    @Test
    fun `인식기가 하나도 없으면 카운트다운 모드 — 5초 뒤 자동 전송`() = runTest {
        val r = Rig(this)
        r.begin()
        r.listener.candidate = false
        r.record(2_000)
        assertEquals(PttStage.CONFIRMING, r.state.stage)
        assertTrue(r.state.countdownMode)
        assertFalse(r.state.listening)
        assertEquals(PttPrompts.COUNTDOWN, r.voice.spoken.last())
        assertEquals(0, r.listener.listens)
        advanceTimeBy(4_900); runCurrent()
        assertEquals(PttStage.CONFIRMING, r.state.stage)
        assertEquals(1, r.state.remainingS)
        advanceTimeBy(200); runCurrent()
        assertEquals(PttStage.SENDING, r.state.stage)
        advanceTimeBy(SEND_MS); runCurrent()
        assertEquals(PttStage.SENT, r.state.stage)
        assertEquals(1, r.state.stats.countdowns)
        r.controller.stop()
    }

    @Test
    fun `카운트다운 중 누르면 취소`() = runTest {
        val r = Rig(this)
        r.begin()
        r.listener.candidate = false
        r.record(2_000)
        advanceTimeBy(2_000); runCurrent()
        r.press()
        assertEquals(PttNotice.CANCELLED, r.state.notice)
        advanceTimeBy(10_000); runCurrent()
        assertTrue(r.sent.isEmpty())
        r.controller.stop()
    }

    @Test
    fun `카운트다운 중 주입 답 — 네는 바로 보냄, 아니는 취소, 무응답은 보관`() = runTest {
        val r = Rig(this)
        r.begin()
        r.listener.candidate = false
        r.record(2_000)
        advanceTimeBy(1_000); runCurrent()
        r.listener.injected.trySend(YesNo.YES); runCurrent()
        assertEquals(PttStage.SENDING, r.state.stage)
        assertEquals(RecognizerKind.INJECTED, r.state.lastRecognizer)
        advanceTimeBy(SEND_MS + 3_000); runCurrent()

        r.record(2_000)
        r.listener.injected.trySend(YesNo.NO); runCurrent()
        assertEquals(PttNotice.CANCELLED, r.state.notice)
        advanceTimeBy(3_000); runCurrent()

        r.record(2_000)
        r.listener.injected.trySend(YesNo.NONE); runCurrent()
        assertEquals(PttNotice.KEPT_UNSENT, r.state.notice)
        assertEquals(1, r.controller.unsent.value.size)
        assertEquals(1, r.sent.size)
        r.controller.stop()
    }

    @Test
    fun `듣다가 인식기가 모두 실패하면 카운트다운으로 바꾼다`() = runTest {
        val r = Rig(this)
        r.begin()
        r.listener.answer(300, null)
        r.record(2_000)
        advanceTimeBy(300); runCurrent()
        assertTrue(r.state.countdownMode)
        assertEquals(5, r.state.remainingS)
        advanceTimeBy(5_100); runCurrent()
        assertEquals(1, r.sent.size)
        r.controller.stop()
    }

    @Test
    fun `0_7초 미만은 너무 짧아요 — 버림`() = runTest {
        val r = Rig(this)
        r.begin()
        r.record(500)
        assertEquals(PttStage.NOTICE, r.state.stage)
        assertEquals(PttNotice.TOO_SHORT, r.state.notice)
        assertEquals(0, r.listener.listens)
        assertTrue(r.voice.spoken.contains(PttPrompts.TOO_SHORT))
        assertTrue(r.controller.unsent.value.isEmpty())
        advanceTimeBy(3_000); runCurrent()
        assertEquals(PttStage.IDLE, r.state.stage)
        assertEquals(1, r.state.stats.tooShort)
        r.controller.stop()
    }

    @Test
    fun `15초가 차면 녹음이 저절로 끝난다`() = runTest {
        val r = Rig(this)
        r.begin()
        r.listener.answer(500, YesNo.YES)
        r.press()
        advanceTimeBy(15_000); runCurrent()
        assertEquals(PttStage.CONFIRMING, r.state.stage)
        advanceTimeBy(500); runCurrent()
        assertEquals(15_000L, r.sent.single().durationMs)
        r.controller.stop()
    }

    @Test
    fun `확인 중(듣는 동안) 누르면 취소`() = runTest {
        val r = Rig(this)
        r.begin()
        r.record(2_000)
        advanceTimeBy(1_500); runCurrent()
        assertTrue(r.state.listening)
        r.press()
        assertEquals(PttNotice.CANCELLED, r.state.notice)
        advanceTimeBy(10_000); runCurrent()
        assertTrue(r.sent.isEmpty())
        assertTrue(r.controller.unsent.value.isEmpty())
        r.controller.stop()
    }

    @Test
    fun `질문을 읽는 동안 누르면 취소 — 마이크를 열지 않는다`() = runTest {
        val r = Rig(this)
        r.begin()
        r.voice.promptMs = 1_500
        r.record(2_000)
        assertEquals(PttStage.CONFIRMING, r.state.stage)
        assertFalse(r.state.listening)
        advanceTimeBy(500); runCurrent()
        r.press()
        assertEquals(PttNotice.CANCELLED, r.state.notice)
        assertEquals(0, r.listener.listens)
        r.controller.stop()
    }

    @Test
    fun `녹음이 끝나는 순간 겹친 누름은 취소로 치지 않는다`() = runTest {
        val r = Rig(this)
        r.begin()
        r.listener.answer(1_000, YesNo.YES)
        r.press()
        // 15초 자동 종료와 같은 순간에 누름이 두 번(끝내기 + 중복)
        advanceTimeBy(14_999); runCurrent()
        r.controller.press("TEST"); r.controller.press("TEST")
        runCurrent()
        advanceTimeBy(1_000); runCurrent()
        assertEquals(1, r.sent.size)
        r.controller.stop()
    }

    @Test
    fun `전송 실패 — 보내지 않은 무전(SEND_FAILED)에 두고 안내`() = runTest {
        val r = Rig(this)
        r.begin()
        r.outcome = SendOutcome.Failed("HTTP 429 VOICE_RATE_LIMITED")
        r.listener.answer(500, YesNo.YES)
        r.record(2_000)
        advanceTimeBy(500 + SEND_MS); runCurrent()
        assertEquals(PttNotice.SEND_FAILED, r.state.notice)
        val kept = r.controller.unsent.value.single()
        assertEquals(UnsentReason.SEND_FAILED, kept.reason)
        assertEquals("HTTP 429 VOICE_RATE_LIMITED", kept.failure)
        assertTrue(kept.file.isFile)
        assertTrue(r.voice.spoken.contains(PttPrompts.SEND_FAILED))
        r.controller.stop()
    }

    @Test
    fun `보내지 않은 무전 — 정차 중 다시 보내기·지우기, 주행 중에는 거절`() = runTest {
        val r = Rig(this)
        r.begin()
        r.record(2_000); advanceTimeBy(5_000); runCurrent(); advanceTimeBy(3_000); runCurrent()   // 무응답 1
        r.record(3_000); advanceTimeBy(5_000); runCurrent(); advanceTimeBy(3_000); runCurrent()   // 무응답 2
        val list = r.controller.unsent.value
        assertEquals(2, list.size)
        assertEquals("최신 먼저", 3_000L, list[0].durationMs)

        r.driving = true
        val refused = async { r.controller.resend(list[0].id) }
        runCurrent()
        assertFalse(refused.await())
        val refusedDelete = async { r.controller.deleteUnsent(list[1].id) }
        runCurrent()
        assertFalse(refusedDelete.await())

        r.driving = false
        val resend = async { r.controller.resend(list[0].id) }
        runCurrent()
        assertTrue(resend.await())
        assertEquals(PttStage.SENDING, r.state.stage)
        advanceTimeBy(SEND_MS); runCurrent()
        assertEquals(PttStage.SENT, r.state.stage)
        assertEquals(1, r.controller.unsent.value.size)
        assertFalse(list[0].file.exists())

        advanceTimeBy(3_000); runCurrent()
        val delete = async { r.controller.deleteUnsent(list[1].id) }
        runCurrent()
        assertTrue(delete.await())
        assertTrue(r.controller.unsent.value.isEmpty())
        assertFalse(list[1].file.exists())
        r.controller.stop()
    }

    @Test
    fun `보내지 않은 무전은 최대 5개 — 넘치면 가장 오래된 것을 지운다`() = runTest {
        val r = Rig(this)
        r.begin()
        val files = mutableListOf<File>()
        repeat(6) {
            r.record(1_000 + it * 100L)
            advanceTimeBy(5_000); runCurrent()
            files += r.controller.unsent.value.first().file
            advanceTimeBy(3_000); runCurrent()
        }
        assertEquals(5, r.controller.unsent.value.size)
        assertFalse(files[0].exists())
        assertTrue(files[5].exists())
        r.controller.stop()
    }

    @Test
    fun `그룹이 바뀌면 진행 중 녹음을 버리고 보내지 않은 무전을 지운다`() = runTest {
        val r = Rig(this)
        r.begin()
        r.record(2_000); advanceTimeBy(5_000); runCurrent(); advanceTimeBy(3_000); runCurrent()
        val kept = r.controller.unsent.value.single()
        r.press()
        assertEquals(PttStage.RECORDING, r.state.stage)
        r.controller.onGroupChanged(null)
        runCurrent()
        assertEquals(PttStage.IDLE, r.state.stage)
        assertEquals(1, r.recorder.cancels)
        assertTrue(r.controller.unsent.value.isEmpty())
        assertFalse(kept.file.exists())
        r.controller.stop()
    }

    @Test
    fun `마이크 권한이 없으면 녹음하지 않는다 — 주입 녹음은 진행`() = runTest {
        val r = Rig(this)
        r.begin()
        r.mic = false
        r.press()
        assertEquals(PttNotice.MIC_PERMISSION, r.state.notice)
        assertFalse(r.state.micGranted)
        assertEquals(0, r.recorder.starts)
        advanceTimeBy(3_000); runCurrent()

        val injected = tmp.newFile("yes_ne.wav").apply { writeBytes(ByteArray(64)) }
        r.recorder.injectNext(injected)
        r.recorder.injectedDurationMs = 1_800
        r.listener.answer(200, YesNo.YES)
        r.record(500)   // 누른 시간이 짧아도 주입 파일 길이로 판단
        advanceTimeBy(200 + SEND_MS); runCurrent()
        assertEquals(1_800L, r.sent.single().durationMs)
        assertTrue(r.sent.single().injected)
        r.controller.stop()
    }

    @Test
    fun `전송 중 누름은 무시, 보냄·안내 중 누름은 새 녹음`() = runTest {
        val r = Rig(this)
        r.begin()
        r.listener.answer(100, YesNo.YES)
        r.record(1_000)
        advanceTimeBy(100); runCurrent()
        assertEquals(PttStage.SENDING, r.state.stage)
        r.press()
        assertEquals(PttStage.SENDING, r.state.stage)
        assertEquals(1, r.state.stats.ignoredPresses)
        advanceTimeBy(SEND_MS); runCurrent()
        assertEquals(PttStage.SENT, r.state.stage)
        r.press()
        assertEquals(PttStage.RECORDING, r.state.stage)
        r.controller.stop()
    }

    @Test
    fun `녹음 시작 실패 — 안내만`() = runTest {
        val r = Rig(this)
        r.begin()
        r.recorder.startFailure = "recorder-IllegalStateException"
        r.press()
        assertEquals(PttNotice.RECORD_FAILED, r.state.notice)
        assertTrue(r.voice.spoken.contains(PttPrompts.RECORD_FAILED))
        r.controller.stop()
    }

    private companion object {
        const val SEND_MS = 200L
    }
}
