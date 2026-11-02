package app.donghaeng.car.domain.ptt

import app.donghaeng.car.adapter.audio.FakeVoiceRecorder
import app.donghaeng.car.adapter.audio.RecordedClip
import app.donghaeng.car.adapter.speech.ChainOutcome
import app.donghaeng.car.adapter.speech.RecognizerKind
import app.donghaeng.car.adapter.speech.YesNoListener
import app.donghaeng.car.adapter.voice.SpeakResult
import app.donghaeng.car.adapter.voice.VoiceAdapter
import app.donghaeng.car.adapter.voice.VoiceAdapterStatus
import app.donghaeng.car.core.network.VoiceMessageClient
import app.donghaeng.car.domain.model.Priority
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * F16 보내기 — 음성 안내와 마이크가 섞이지 않게(B013: 녹음 직전 안내 비키기·지난 사이클 안내 버리기), 백그라운드에서 마이크가 막히면 무음 대신 안내(B015),
 * 같은 녹음은 첫 전송·다시 보내기가 같은 멱등 키(B073), 계약 상한을 조금 넘은 길이는 자른다(B062).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PttVoiceGuardTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 안내 문장과 꼬리표·취소를 기록하는 음성 대역 */
    private class TaggingVoice : VoiceAdapter {
        val spoken = mutableListOf<Pair<String, String?>>()
        val cancelled = mutableListOf<String>()
        override val status: StateFlow<VoiceAdapterStatus> = MutableStateFlow(VoiceAdapterStatus(ready = true))
        override suspend fun speak(text: String, priority: Priority) = speak(text, priority, null)
        override suspend fun speak(text: String, priority: Priority, tag: String?) { spoken += text to tag }
        override suspend fun speakAndWait(text: String, priority: Priority, timeoutMs: Long): SpeakResult {
            spoken += text to null
            return SpeakResult.SPOKEN
        }
        override fun cancel(tag: String) { cancelled += tag }
        override fun stop() = Unit
    }

    private class Listener(var answer: YesNo? = YesNo.NONE) : YesNoListener {
        override fun hasCandidate(): Boolean = true
        override suspend fun prepare() = Unit
        override suspend fun listen(timeoutMs: Long): ChainOutcome {
            delay(500)
            return ChainOutcome(answer, if (answer == null) null else RecognizerKind.VOSK)
        }
        override fun describe(): String = "test"
    }

    /** 키까지 받는 전송 대역 */
    private class KeyedSender : VoiceSender {
        val keys = mutableListOf<String?>()
        var outcome: SendOutcome = SendOutcome.Sent("vm_1")
        override suspend fun send(groupId: String, clip: RecordedClip): SendOutcome = send(groupId, clip, null)
        override suspend fun send(groupId: String, clip: RecordedClip, idempotencyKey: String?): SendOutcome {
            keys += idempotencyKey
            delay(100)
            return outcome
        }
    }

    private inner class Rig(scope: TestScope, micUsable: () -> Boolean = { true }) {
        val scheduler = scope.testScheduler
        val clock = { scheduler.currentTime }
        val recorder = FakeVoiceRecorder(tmp.newFolder(), clock)
        val listener = Listener()
        val voice = TaggingVoice()
        val sender = KeyedSender()
        var micOpenings = 0
        val controller = PttController(
            recorder = recorder, listener = listener, voice = voice, sender = sender,
            groupId = { "grp_1" }, recipients = { 2 }, micGranted = { true }, isDriving = { false },
            dispatcher = StandardTestDispatcher(scheduler), clock = clock,
            // 기기와 같은 크기의 벽시계(epoch ms) — 키 길이가 실제와 같게
            wallClock = { WALL + scheduler.currentTime },
            micUsable = micUsable, micOpening = { micOpenings++ },
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
    }

    @Test
    fun `before the mic opens the controller makes voice step aside and drops its own unfinished prompts (B013)`() = runTest {
        val r = Rig(this)
        r.begin()
        r.listener.answer = YesNo.NO
        r.press()
        assertEquals("녹음을 열기 직전 음성 안내를 비킨다(VoiceAnnouncer.yieldToMic)", 1, r.micOpenings)
        advanceTimeBy(2_000); runCurrent()
        r.press()                     // 녹음 끝 → 확인
        advanceTimeBy(600); runCurrent()
        assertEquals(PttNotice.CANCELLED, r.state.notice)
        val cancelledPrompt = r.voice.spoken.last { it.first == PttPrompts.CANCELLED }
        assertTrue("안내에는 꼬리표 ptt-N", cancelledPrompt.second!!.startsWith("ptt-"))
        // '취소했어요.' 3초 안에 다시 누른다 — 그 안내가 Pleos 지연으로 아직 안 나왔으면 새 녹음에 섞이지 않게 버린다
        advanceTimeBy(1_000); runCurrent()
        r.press()
        assertEquals(PttStage.RECORDING, r.state.stage)
        assertEquals(2, r.micOpenings)
        assertEquals(listOf(cancelledPrompt.second), r.voice.cancelled)
        r.controller.stop()
    }

    @Test
    fun `in the background without a microphone service the controller does not record silence (B015)`() = runTest {
        var usable = false
        val r = Rig(this, micUsable = { usable })
        r.begin()
        r.press()
        assertEquals("무음을 녹음하지 않는다", 0, r.recorder.starts)
        assertEquals(PttNotice.RECORD_FAILED, r.state.notice)
        assertTrue(r.voice.spoken.any { it.first == PttPrompts.MIC_BACKGROUND })
        assertEquals(1, r.state.stats.micBlocked)
        assertEquals("음성 안내를 비킬 필요도 없다", 0, r.micOpenings)
        advanceTimeBy(3_000); runCurrent()
        // 디버그 주입 녹음은 마이크를 쓰지 않으니 그대로 한다
        r.recorder.injectNext(File(tmp.newFolder(), "msg.m4a").apply { writeBytes(ByteArray(100)) })
        r.press()
        assertEquals(1, r.recorder.starts)
        assertEquals(PttStage.RECORDING, r.state.stage)
        r.controller.stop()
        // 화면이 한 번 열려 타입이 붙으면 다시 녹음한다
        usable = true
    }

    @Test
    fun `the first send and the resend from the unsent list carry the same idempotency key (B073)`() = runTest {
        val r = Rig(this)
        r.begin()
        r.listener.answer = YesNo.YES
        r.sender.outcome = SendOutcome.Failed("network SocketTimeoutException")
        r.press()
        advanceTimeBy(2_000); runCurrent()
        r.press()
        advanceTimeBy(600 + 100); runCurrent()
        assertEquals(PttNotice.SEND_FAILED, r.state.notice)
        val kept = r.controller.unsent.value.single()
        val first = r.sender.keys.single()
        assertNotNull(first)
        assertEquals("보관 id 로 만든 키", PttController.idempotencyKeyOf(kept.id), first)
        assertTrue("docs/03 계약 3 형식", VoiceMessageClient.IDEMPOTENCY_KEY.matches(first!!))
        advanceTimeBy(3_000); runCurrent()
        // 응답을 잃었을 뿐 서버는 커밋했을 수 있다 — 다시 보내도 같은 키(서버가 첫 응답을 그대로 준다)
        r.sender.outcome = SendOutcome.Sent("vm_1")
        assertTrue(r.controller.resend(kept.id))
        runCurrent(); advanceTimeBy(200); runCurrent()
        assertEquals(listOf(first, first), r.sender.keys)
        assertTrue(r.controller.unsent.value.isEmpty())
        r.controller.stop()
    }

    @Test
    fun `different recordings get different keys and short test ids still make a valid key`() {
        assertTrue(VoiceMessageClient.IDEMPOTENCY_KEY.matches(PttController.idempotencyKeyOf("u0-1")))
        assertTrue(PttController.idempotencyKeyOf("u1798000000000-1") != PttController.idempotencyKeyOf("u1798000000000-2"))
        assertEquals(64, PttController.idempotencyKeyOf("u" + "9".repeat(80)).length)
    }

    @Test
    fun `a stored clip a little over the 16 s contract is clamped so the resend does not fail forever (B062)`() {
        val f = tmp.newFile("u1-1.m4a").apply { writeBytes(ByteArray(10)) }
        fun unsent(ms: Long) = UnsentVoice("u1-1", "grp_1", f, "audio/mp4", ms, 0L, UnsentReason.SEND_FAILED)
        assertEquals(16_000L, unsent(16_900).toClip().durationMs)
        assertEquals(15_000L, unsent(15_000).toClip().durationMs)
        assertEquals("정말 긴 파일(디버그 주입)은 그대로 — 거절된다", 25_000L, unsent(25_000).toClip().durationMs)
        assertEquals(null, VoiceMessageClient.clientCheck(f, "audio/mp4", unsent(16_900).toClip().durationMs))
    }

    @Test
    fun `mic gate - API 30+ background needs a microphone foreground service`() {
        val mic = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        val location = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        assertTrue("API 29 는 제한 없음", PttMicGate.usable(29, appVisible = false, attachedFgsType = null))
        assertTrue("화면이 앞이면 된다", PttMicGate.usable(34, appVisible = true, attachedFgsType = null))
        assertTrue(PttMicGate.usable(34, appVisible = false, attachedFgsType = location or mic))
        assertTrue(!PttMicGate.usable(34, appVisible = false, attachedFgsType = location))
        assertTrue("서비스 없음", !PttMicGate.usable(34, appVisible = false, attachedFgsType = null))
    }

    private companion object {
        const val WALL = 1_798_000_000_000L
    }
}
