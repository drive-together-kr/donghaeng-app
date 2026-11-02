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
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /**
     * Pleos 부하를 흉내 낸 음성 대역(RB-3) — 문장이 요청 뒤 [startMs] 에 시작해 [speakMs] 동안 읽힌다. 체인처럼 상한 안에 끝나지 못하면
     * 그 문장을 버린다(B012 — [dropped]). [heard] = 끝까지 들린 문장.
     */
    private class SlowStartVoice(private val startMs: Long, private val speakMs: Long) : VoiceAdapter {
        val heard = mutableListOf<String>()
        val dropped = mutableListOf<String>()
        override val status: StateFlow<VoiceAdapterStatus> = MutableStateFlow(VoiceAdapterStatus(ready = true))
        override suspend fun speak(text: String, priority: Priority) = Unit
        override suspend fun speakAndWait(text: String, priority: Priority, timeoutMs: Long): SpeakResult {
            if (startMs + speakMs <= timeoutMs) {
                delay(startMs + speakMs)
                heard += text
                return SpeakResult.SPOKEN
            }
            delay(timeoutMs)
            dropped += text
            return SpeakResult.TIMEOUT
        }
        /** 체인과 같은 두 단계 상한 — 시작까지 [startTimeoutMs], 시작한 뒤 [doneTimeoutMs]. 기다리던 쪽이 취소되면 그 문장을 버린다 */
        override suspend fun speakAndWaitStaged(text: String, priority: Priority, startTimeoutMs: Long, doneTimeoutMs: Long, onStarted: () -> Unit): SpeakResult {
            if (startMs > startTimeoutMs) {
                delay(startTimeoutMs)
                dropped += text
                return SpeakResult.TIMEOUT
            }
            delay(startMs)
            onStarted()
            try {
                if (speakMs > doneTimeoutMs) {
                    delay(doneTimeoutMs)
                    dropped += text
                    return SpeakResult.TIMEOUT
                }
                delay(speakMs)
            } catch (e: kotlinx.coroutines.CancellationException) {
                cut += text
                throw e
            }
            heard += text
            return SpeakResult.SPOKEN
        }
        /** 읽는 중에 기다리던 쪽이 끊은 문장 */
        val cut = mutableListOf<String>()
        override fun stop() = Unit
    }

    private class Listener(var answer: YesNo? = YesNo.NONE) : YesNoListener {
        var candidate = true
        var listenStartedAt: Long? = null
        var clock: () -> Long = { 0L }
        override fun hasCandidate(): Boolean = candidate
        override suspend fun prepare() = Unit
        override suspend fun listen(timeoutMs: Long): ChainOutcome {
            if (listenStartedAt == null) listenStartedAt = clock()
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

    private inner class Rig(scope: TestScope, micUsable: () -> Boolean = { true }, voiceAdapter: VoiceAdapter? = null) {
        val scheduler = scope.testScheduler
        val clock = { scheduler.currentTime }
        val recorder = FakeVoiceRecorder(tmp.newFolder(), clock)
        val listener = Listener().also { it.clock = clock }
        val voice = TaggingVoice()
        val sender = KeyedSender()
        var micOpenings = 0
        val controller = PttController(
            recorder = recorder, listener = listener, voice = voiceAdapter ?: voice, sender = sender,
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
    fun `a server rejection (429) keeps the same key for the resend - contract 3 says the server remembers only 2xx (RB-1)`() = runTest {
        // docs/03 계약 3(배치 C 확정): 서버는 첫 **성공(2xx)** 응답만 24 h 기억하고 4xx·5xx 는 기억하지 않는다(같은 키로 다시 보내면 다시 처리) →
        // 차는 실패 사유와 무관하게 같은 키로 다시 보낸다. 키를 바꾸면 오히려 '커밋 뒤 응답 직렬화 실패 500'·프록시 502/504 처럼 서버가 저장했을 수 있는
        // 실패에서 같은 무전이 두 번 나간다
        val r = Rig(this)
        r.begin()
        r.listener.answer = YesNo.YES
        r.sender.outcome = SendOutcome.Failed("HTTP 429 VOICE_RATE_LIMITED")
        r.press()
        advanceTimeBy(2_000); runCurrent()
        r.press()
        advanceTimeBy(600 + 100); runCurrent()
        val kept = r.controller.unsent.value.single()
        advanceTimeBy(3_000); runCurrent()
        r.sender.outcome = SendOutcome.Sent("vm_1")
        assertTrue(r.controller.resend(kept.id))
        runCurrent(); advanceTimeBy(200); runCurrent()
        assertEquals(2, r.sender.keys.size)
        assertEquals("429 뒤에도 같은 키", r.sender.keys[0], r.sender.keys[1])
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

    @Test
    fun `mic gate - API 30 to 33 a microphone type attached from the background still records silence (RB-6)`() {
        val mic = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        val location = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        // API 31: 폰 합류로 백그라운드에서 시작 — 타입은 예외 없이 붙었지만 while-in-use 마이크는 막혀 있다
        assertFalse(PttMicGate.usable(31, appVisible = false, attachedFgsType = location or mic, startedWhileVisible = false))
        assertTrue("화면이 보일 때 시작했으면 된다", PttMicGate.usable(31, appVisible = false, attachedFgsType = location or mic, startedWhileVisible = true))
        assertTrue("동행 화면이 앞이면 늘 된다", PttMicGate.usable(33, appVisible = true, attachedFgsType = location or mic, startedWhileVisible = false))
        assertTrue("모르면(서비스가 아직 알려 주지 않음) 예전처럼 타입만", PttMicGate.usable(31, appVisible = false, attachedFgsType = location or mic, startedWhileVisible = null))
        assertTrue("API 34+ 는 붙은 타입이 곧 허용(막히면 SecurityException)", PttMicGate.usable(34, appVisible = false, attachedFgsType = location or mic, startedWhileVisible = false))
        assertTrue("API 29 는 제한 없음", PttMicGate.usable(29, appVisible = false, attachedFgsType = null, startedWhileVisible = false))
    }

    @Test
    fun `a slow voice start (Pleos 7 s) still reads the question before the mic opens - it is not dropped (RB-3)`() = runTest {
        val slow = SlowStartVoice(startMs = 7_000, speakMs = 1_000)
        val r = Rig(this, voiceAdapter = slow)
        r.begin()
        r.listener.answer = YesNo.YES
        r.press()
        advanceTimeBy(2_000); runCurrent()
        r.press()                         // 녹음 끝 → "보낼까요?"
        val confirmAt = currentTime
        assertEquals(PttStage.CONFIRMING, r.state.stage)
        advanceTimeBy(7_500); runCurrent()
        assertFalse("질문을 읽는 동안에는 마이크를 열지 않는다", r.state.listening)
        assertEquals(null, r.listener.listenStartedAt)
        advanceTimeBy(600); runCurrent()
        assertEquals("시작이 7 s 늦어도 질문이 들린다(버리지 않는다)", listOf(PttPrompts.ASK), slow.heard)
        assertTrue(slow.dropped.isEmpty())
        assertEquals("질문이 끝난 뒤에 듣는다", confirmAt + 8_000, r.listener.listenStartedAt)
        advanceTimeBy(500 + 100); runCurrent()
        assertEquals(PttStage.SENT, r.state.stage)
        r.controller.stop()
    }

    @Test
    fun `countdown counts five seconds from the moment the warning starts to sound (RB-3)`() = runTest {
        val slow = SlowStartVoice(startMs = 7_000, speakMs = 4_000)
        val r = Rig(this, voiceAdapter = slow)
        r.begin()
        r.listener.candidate = false          // 인식기 없음 → 카운트다운 모드
        r.press()
        advanceTimeBy(2_000); runCurrent()
        r.press()
        val confirmAt = currentTime
        assertTrue(r.state.countdownMode)
        advanceTimeBy(7_000 + 4_900); runCurrent()
        assertEquals("안내가 들리기 시작한 7 s 뒤부터 5초 — 아직 보내지 않는다", PttStage.CONFIRMING, r.state.stage)
        assertEquals(listOf(PttPrompts.COUNTDOWN), slow.heard)
        assertTrue("안내가 버려지지 않았다(시작 7 s > 예전 상한 6 s)", slow.dropped.isEmpty())
        advanceTimeBy(150); runCurrent()
        assertEquals("7 s + 5 s 에 보낸다", PttStage.SENDING, r.state.stage)
        assertEquals(confirmAt + 12_050, currentTime)
        advanceTimeBy(100); runCurrent()
        assertEquals(PttStage.SENT, r.state.stage)
        r.controller.stop()
    }

    @Test
    fun `a press while waiting for a slow countdown warning cancels and drops the warning (RB-3)`() = runTest {
        val slow = SlowStartVoice(startMs = 7_000, speakMs = 4_000)
        val r = Rig(this, voiceAdapter = slow)
        r.begin()
        r.listener.candidate = false
        r.press()
        advanceTimeBy(2_000); runCurrent()
        r.press()
        advanceTimeBy(8_000); runCurrent()        // 안내 읽는 중(7 s 에 시작)
        r.press()
        assertEquals(PttNotice.CANCELLED, r.state.notice)
        assertEquals("읽던 안내는 끊는다", listOf(PttPrompts.COUNTDOWN), slow.cut)
        advanceTimeBy(10_000); runCurrent()
        assertTrue(r.sender.keys.isEmpty())
        r.controller.stop()
    }

    @Test
    fun `a voice that never starts within the start limit is dropped and the countdown runs from then (RB-3)`() = runTest {
        val stuck = SlowStartVoice(startMs = 60_000, speakMs = 1_000)
        val r = Rig(this, voiceAdapter = stuck)
        r.begin()
        r.listener.candidate = false
        r.press()
        advanceTimeBy(2_000); runCurrent()
        r.press()
        val confirmAt = currentTime
        val limit = PttTimings().promptStartTimeoutMs
        advanceTimeBy(limit + 4_900); runCurrent()
        assertEquals(PttStage.CONFIRMING, r.state.stage)
        assertEquals("시작 상한에 버림 — 늦게 나와 다음 안내와 겹치지 않게", listOf(PttPrompts.COUNTDOWN), stuck.dropped)
        advanceTimeBy(150); runCurrent()
        assertEquals(PttStage.SENDING, r.state.stage)
        assertEquals(confirmAt + limit + 5_050, currentTime)
        r.controller.stop()
    }

    private companion object {
        const val WALL = 1_798_000_000_000L
    }
}
