package app.donghaeng.car.domain.ptt

import app.donghaeng.car.adapter.audio.FakeVoiceRecorder
import app.donghaeng.car.adapter.speech.ChainOutcome
import app.donghaeng.car.adapter.speech.RecognizerKind
import app.donghaeng.car.adapter.speech.YesNoListener
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

/**
 * F16 '보내지 않은 무전' 영속화 (사용자 결정 2026-10-28) — 앱 재시작·프로세스 종료 뒤에도 남고(같은 그룹일 때만 보임), 다시 보냄 성공·지우기·그룹 종료 때 지운다.
 * '앱 재시작' = PttController 를 멈추고 **새 PttController + 새 FileUnsentVoiceStore(같은 폴더)** 로 다시 띄운다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PttUnsentPersistTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class QuietVoice : VoiceAdapter {
        override val status: StateFlow<VoiceAdapterStatus> = MutableStateFlow(VoiceAdapterStatus(ready = true))
        override suspend fun speak(text: String, priority: Priority) = Unit
        override fun stop() = Unit
    }

    /** 늘 무응답(NONE) → 보관 */
    private class SilentListener : YesNoListener {
        override fun hasCandidate(): Boolean = true
        override suspend fun prepare() = Unit
        override suspend fun listen(timeoutMs: Long): ChainOutcome {
            delay(200)
            return ChainOutcome(YesNo.NONE, RecognizerKind.VOSK)
        }
        override fun describe(): String = "silent"
    }

    /** 앱 한 번 띄운 것 — [storeDir] 는 '앱 내부 저장소 files/ptt_unsent'(재시작해도 같은 폴더) */
    private inner class App(scope: TestScope, storeDir: File, var group: String? = "grp_1", grace: Long = 30_000) {
        val scheduler = scope.testScheduler
        val clock = { scheduler.currentTime }
        val cacheDir: File = tmp.newFolder()
        val store = FileUnsentVoiceStore(storeDir)
        var outcome: SendOutcome = SendOutcome.Sent("vm_1")
        var driving = false
        val controller = PttController(
            recorder = FakeVoiceRecorder(cacheDir, clock), listener = SilentListener(), voice = QuietVoice(),
            sender = { _, _ -> outcome },
            groupId = { group }, recipients = { 1 }, isDriving = { driving },
            timings = PttTimings(restoreGraceMs = grace),
            dispatcher = StandardTestDispatcher(scheduler), clock = clock, wallClock = { 1_700_000_000_000L + scheduler.currentTime },
            store = store,
        )

        fun start(groupNow: String? = group) {
            controller.start()
            scheduler.runCurrent()
            controller.onGroupChanged(groupNow)
            scheduler.runCurrent()
        }

        /** 녹음 [ms] → 확인 → 무응답 → 보관 → 안내 3초 */
        fun keep(ms: Long) {
            controller.press("TEST"); scheduler.runCurrent()
            scheduler.advanceTimeBy(ms); scheduler.runCurrent()
            controller.press("TEST"); scheduler.runCurrent()
            scheduler.advanceTimeBy(200); scheduler.runCurrent()
            scheduler.advanceTimeBy(3_000); scheduler.runCurrent()
        }

        val unsent get() = controller.unsent.value
    }

    @Test
    fun `재시작 증명 — 보관한 무전이 앱을 다시 띄워도(같은 그룹) 목록에 그대로, 오디오는 앱 내부 저장소에`() = runTest {
        val storeDir = File(tmp.root, "files/ptt_unsent")
        val first = App(this, storeDir)
        first.start()
        first.keep(2_000)
        first.keep(3_000)
        val before = first.unsent
        assertEquals(2, before.size)
        assertEquals("최신 먼저", 3_000L, before[0].durationMs)
        assertTrue("오디오는 캐시가 아니라 저장소 폴더", before.all { it.file.parentFile!!.canonicalPath == storeDir.canonicalPath && it.file.isFile })
        assertTrue("캐시(cache/ptt/out)에는 남지 않는다", first.cacheDir.listFiles().isNullOrEmpty())
        assertEquals(2, first.controller.persistedCount())
        assertTrue(first.controller.describe().contains("unsent=2 persisted=2"))
        // 프로세스 종료 — 메모리는 사라진다
        first.controller.stop()

        val second = App(this, storeDir)
        second.controller.start(); runCurrent()
        assertTrue("그룹을 알기 전에는 보이지 않는다", second.unsent.isEmpty())
        assertEquals(2, second.controller.persistedCount())
        second.controller.onGroupChanged("grp_1"); runCurrent()
        val after = second.unsent
        assertEquals(before.map { it.id }, after.map { it.id })
        assertEquals(before.map { it.durationMs }, after.map { it.durationMs })
        assertEquals(before.map { it.createdAtMs }, after.map { it.createdAtMs })
        assertEquals(before.map { it.reason }, after.map { it.reason })
        assertTrue(after.all { it.file.isFile })

        // 재시작 뒤에도 다시 보내기(정차 중) → 성공하면 파일 삭제
        val resend = async { second.controller.resend(after[0].id) }
        runCurrent()
        assertTrue(resend.await())
        runCurrent()
        assertEquals(PttStage.SENT, second.controller.state.value.stage)
        assertFalse(after[0].file.exists())
        assertEquals(1, second.controller.persistedCount())
        advanceTimeBy(3_000); runCurrent()

        // 지우기 → 파일 삭제, 폴더가 빈다
        val delete = async { second.controller.deleteUnsent(after[1].id) }
        runCurrent()
        assertTrue(delete.await())
        assertEquals(0, second.controller.persistedCount())
        assertTrue(storeDir.listFiles().isNullOrEmpty())
        second.controller.stop()
    }

    @Test
    fun `그룹 종료 — 목록과 저장소 폴더를 비운다(재시작해도 안 돌아온다)`() = runTest {
        val storeDir = File(tmp.root, "files/ptt_unsent")
        val app = App(this, storeDir)
        app.start()
        app.keep(2_000)
        assertEquals(1, app.controller.persistedCount())
        app.controller.onGroupChanged(null); runCurrent()
        assertTrue(app.unsent.isEmpty())
        assertTrue("그룹 종료 → 폴더가 빈다", storeDir.listFiles().isNullOrEmpty())
        app.controller.stop()

        val again = App(this, storeDir)
        again.start("grp_1")
        assertTrue(again.unsent.isEmpty())
        again.controller.stop()
    }

    @Test
    fun `재시작했는데 다른 그룹이면 지운다`() = runTest {
        val storeDir = File(tmp.root, "files/ptt_unsent")
        val first = App(this, storeDir)
        first.start()
        first.keep(2_000)
        first.controller.stop()

        val second = App(this, storeDir, group = "grp_2")
        second.start("grp_2")
        assertTrue(second.unsent.isEmpty())
        assertEquals(0, second.controller.persistedCount())
        assertTrue(storeDir.listFiles().isNullOrEmpty())
        second.controller.stop()
    }

    @Test
    fun `재시작했는데 그룹이 없으면(유예가 지나도록) 지운다 — 유예 안에 같은 그룹이 오면 남긴다`() = runTest {
        val storeDir = File(tmp.root, "files/ptt_unsent")
        val first = App(this, storeDir)
        first.start()
        first.keep(2_000)
        first.keep(2_500)
        first.controller.stop()

        // 세션 복원이 늦게 와도(유예 안) 같은 그룹이면 남는다
        val late = App(this, storeDir, grace = 5_000)
        late.controller.start(); runCurrent()
        late.controller.onGroupChanged(null); runCurrent()   // 시작 직후 '아직 모름'(null)
        advanceTimeBy(4_000); runCurrent()
        assertEquals(2, late.controller.persistedCount())
        late.controller.onGroupChanged("grp_1"); runCurrent()
        assertEquals(2, late.unsent.size)
        late.controller.stop()

        // 끝까지 그룹이 없다 → 지운다
        val none = App(this, storeDir, group = null, grace = 5_000)
        none.controller.start(); runCurrent()
        none.controller.onGroupChanged(null); runCurrent()
        advanceTimeBy(4_900); runCurrent()
        assertEquals("유예 안에는 그대로", 2, none.controller.persistedCount())
        advanceTimeBy(200); runCurrent()
        assertEquals(0, none.controller.persistedCount())
        assertTrue(none.unsent.isEmpty())
        assertTrue(storeDir.listFiles().isNullOrEmpty())
        none.controller.stop()
    }

    @Test
    fun `다시 보내기 실패 사유도 저장된다 — 재시작 뒤 보내기 실패로 보인다`() = runTest {
        val storeDir = File(tmp.root, "files/ptt_unsent")
        val first = App(this, storeDir)
        first.start()
        first.keep(2_000)
        first.outcome = SendOutcome.Failed("HTTP 503")
        val id = first.unsent.single().id
        val r = async { first.controller.resend(id) }
        runCurrent()
        assertTrue(r.await())
        runCurrent()
        assertEquals(PttNotice.SEND_FAILED, first.controller.state.value.notice)
        first.controller.stop()

        val second = App(this, storeDir)
        second.start()
        val back = second.unsent.single()
        assertEquals(id, back.id)
        assertEquals(UnsentReason.SEND_FAILED, back.reason)
        assertEquals("HTTP 503", back.failure)
        second.controller.stop()
    }

    @Test
    fun `저장소 상한을 넘는 것은 목록 상한(5)대로 — 넘친 것은 파일도 지운다`() = runTest {
        val storeDir = File(tmp.root, "files/ptt_unsent")
        val app = App(this, storeDir)
        app.start()
        repeat(6) { app.keep(1_000 + it * 100L) }
        assertEquals(PttController.MAX_UNSENT, app.unsent.size)
        assertEquals(PttController.MAX_UNSENT, app.controller.persistedCount())
        app.controller.stop()
    }

    @Test
    fun `사이클 번호 — 흐름이 IDLE 로 끝나면 idleCycleSeq 가 그 사이클 번호`() = runTest {
        val app = App(this, File(tmp.root, "s"))
        app.start()
        assertEquals(0, app.controller.state.value.cycleSeq)
        app.controller.press("TEST"); runCurrent()
        assertEquals(1, app.controller.state.value.cycleSeq)
        assertEquals(0, app.controller.state.value.idleCycleSeq)
        advanceTimeBy(300); runCurrent()
        app.controller.press("TEST"); runCurrent()   // 너무 짧아요(안내 3초)
        assertEquals(PttStage.NOTICE, app.controller.state.value.stage)
        assertEquals(0, app.controller.state.value.idleCycleSeq)
        // 안내 중 누름 → 새 사이클 2(옛 사이클 1 은 IDLE 을 남기지 않고 끝난다)
        app.controller.press("TEST"); runCurrent()
        assertEquals(2, app.controller.state.value.cycleSeq)
        assertEquals(0, app.controller.state.value.idleCycleSeq)
        advanceTimeBy(300); runCurrent()
        app.controller.press("TEST"); runCurrent()
        advanceTimeBy(3_000); runCurrent()
        assertEquals(PttStage.IDLE, app.controller.state.value.stage)
        assertEquals(2, app.controller.state.value.idleCycleSeq)
        app.controller.stop()
    }
}
