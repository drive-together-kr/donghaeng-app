package app.donghaeng.car.domain.ptt

import app.donghaeng.car.adapter.audio.FakeVoiceClipPlayer
import app.donghaeng.car.adapter.voice.SpeakResult
import app.donghaeng.car.adapter.voice.VoiceAdapter
import app.donghaeng.car.adapter.voice.VoiceAdapterStatus
import app.donghaeng.car.domain.model.Priority
import app.donghaeng.car.domain.session.GroupSession
import app.donghaeng.car.domain.session.ReceivedGroupEvent
import app.donghaeng.protocol.DhJson
import app.donghaeng.protocol.GroupEvent
import app.donghaeng.protocol.GroupState
import app.donghaeng.protocol.GroupTopics
import app.donghaeng.protocol.MemberState
import app.donghaeng.protocol.MemberStatus
import app.donghaeng.protocol.Place
import app.donghaeng.protocol.Severity
import app.donghaeng.protocol.VoiceMessagePayload
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** F16 수신 — 내 차 제외·audience·60 s 신선도(서버 시계)·큐·보내기 중 미룸·알림 뒤 재생·3 s 머묾·끊기·지난 무전 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoicePlaybackTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class RecordingVoice : VoiceAdapter {
        val waited = mutableListOf<String>()
        var announceMs = 0L
        /** stop() 호출 수 — 알림을 읽다 끊기면 TTS 큐를 비운다 */
        var stops = 0
        override val status: StateFlow<VoiceAdapterStatus> = MutableStateFlow(VoiceAdapterStatus(ready = true))
        override suspend fun speak(text: String, priority: Priority) = Unit
        override suspend fun speakAndWait(text: String, priority: Priority, timeoutMs: Long): SpeakResult {
            waited += text
            delay(announceMs)
            return SpeakResult.SPOKEN
        }
        override fun stop() {
            stops++
        }
    }

    private fun member(vid: String, nick: String, color: String) =
        MemberState(vid, nick, color, true, null, null, null, null, null, null, null, null, MemberStatus.DRIVING)

    private inner class Rig(scope: TestScope) {
        val scheduler = scope.testScheduler
        val events = MutableSharedFlow<ReceivedGroupEvent>(extraBufferCapacity = 16)
        val session = MutableStateFlow<GroupSession?>(GroupSession("grp_1", "귀성길", "veh_A", Place(37.0, 127.0, "집"), GroupTopics("s", "e"), 0L))
        val groupState = GroupState("grp_1", "veh_A", listOf(member("veh_A", "아빠 차", "#2E7DFF"), member("veh_ME", "나", "#FF6B2E"), member("veh_C", "삼촌 차", "#2EC27E")))
        var serverNow = 100_000L
        val fetched = mutableListOf<String>()
        var fetchFails = false
        var fetchDelayMs = 0L
        val dir: File = tmp.newFolder()
        /** 내려받은 파일 이름에 long 이 들어가면 7초, 아니면 3초 재생 */
        val player = FakeVoiceClipPlayer(defaultDurationMs = 3_000, durationOf = { f -> if (f.name.contains("long")) 7_000 else null })
        val voice = RecordingVoice()
        var announce = true
        val playback = VoicePlayback(
            events = events, myVehicleId = { "veh_ME" }, groupState = { groupState }, session = session,
            serverNow = { serverNow + scheduler.currentTime },
            fetcher = { _, voiceId, _ ->
                fetched += voiceId
                delay(fetchDelayMs)
                if (fetchFails) null else File(dir, "$voiceId.m4a").apply { writeBytes(ByteArray(10)) }
            },
            player = player, voice = voice, announceEnabled = { announce },
            dispatcher = StandardTestDispatcher(scheduler),
        )
        var seq = 0
        val state get() = playback.state.value

        fun voiceEvent(voiceId: String, from: String = "veh_A", ageMs: Long = 0, speech: String? = "아빠 차 무전", audience: List<String>? = null, durationMs: Int = 3_000) =
            ReceivedGroupEvent(
                GroupEvent(
                    "evt_${seq++}", "VOICE_MESSAGE", from, Severity.INFO, speech,
                    DhJson.payloadOf(VoiceMessagePayload(voiceId, durationMs, "audio/mp4", 12_000, 0L)), audience,
                ),
                envelopeTsMs = serverNow + scheduler.currentTime - ageMs,
                receivedAtMs = scheduler.currentTime,
            )

        fun emit(e: ReceivedGroupEvent) {
            events.tryEmit(e)
            scheduler.runCurrent()
        }
    }

    @Test
    fun `알림을 읽은 뒤 재생하고, 끝나면 3초 머문 뒤 사라진다`() = runTest {
        val r = Rig(this)
        r.playback.start(); runCurrent()
        r.voice.announceMs = 800
        r.emit(r.voiceEvent("vm_1"))
        assertEquals(listOf("vm_1"), r.fetched)
        assertEquals("서버 speech 를 그대로 읽는다", listOf("아빠 차 무전"), r.voice.waited)
        val p = r.state.playing
        assertNotNull(p)
        assertEquals("아빠 차", p!!.voice.nickname)
        assertEquals("#2E7DFF", p.voice.colorHex)
        assertFalse(p.ended)
        assertTrue(r.player.played.isEmpty())   // 알림을 읽는 중
        advanceTimeBy(800); runCurrent()
        assertEquals(listOf("vm_1.m4a"), r.player.played)
        assertTrue(r.playback.isPlaying)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(1_000L, r.state.playing!!.positionMs)
        advanceTimeBy(2_000); runCurrent()
        assertTrue("끝 — 머무는 중", r.state.playing!!.ended)
        assertFalse(r.playback.isPlaying)
        assertEquals(PastStatus.PLAYED, r.state.past.single().status)
        advanceTimeBy(3_000); runCurrent()
        assertNull(r.state.playing)
        assertEquals(1, r.state.played)
        r.playback.stop()
    }

    @Test
    fun `내 차가 보낸 무전·대상이 아닌 무전은 재생하지 않는다`() = runTest {
        val r = Rig(this)
        r.playback.start(); runCurrent()
        r.emit(r.voiceEvent("vm_own", from = "veh_ME"))
        r.emit(r.voiceEvent("vm_other", audience = listOf("veh_C")))
        advanceTimeBy(10_000); runCurrent()
        assertTrue(r.fetched.isEmpty())
        assertTrue(r.player.played.isEmpty())
        assertEquals(1, r.state.skippedOwn)
        assertEquals(1, r.state.skippedNotAudience)
        r.emit(r.voiceEvent("vm_me", audience = listOf("veh_ME", "veh_C")))
        advanceTimeBy(4_000); runCurrent()
        assertEquals(listOf("vm_me.m4a"), r.player.played)
        r.playback.stop()
    }

    @Test
    fun `60초 넘은 무전은 재생하지 않고 지난 무전에 — 서버 시계 기준`() = runTest {
        val r = Rig(this)
        r.playback.start(); runCurrent()
        r.emit(r.voiceEvent("vm_old", ageMs = 61_000))
        advanceTimeBy(5_000); runCurrent()
        assertTrue(r.player.played.isEmpty())
        assertTrue("내려받지도 않는다(탭하면 받는다)", r.fetched.isEmpty())
        val past = r.state.past.single()
        assertEquals(PastStatus.STALE, past.status)
        assertEquals(1, r.state.stale)

        // 다시 듣기 — 신선도는 보지 않는다
        val replay = async { r.playback.replay("vm_old") }
        runCurrent()
        assertTrue(replay.await())
        advanceTimeBy(100); runCurrent()
        assertEquals(listOf("vm_old"), r.fetched)
        assertEquals(listOf("vm_old.m4a"), r.player.played)
        r.playback.stop()
    }

    @Test
    fun `큐 — 도착 순서대로, 같은 무전은 한 번만`() = runTest {
        val r = Rig(this)
        r.playback.start(); runCurrent()
        val first = r.voiceEvent("vm_1")
        r.emit(first)
        r.emit(r.voiceEvent("vm_2", from = "veh_C", speech = null))
        r.emit(first)   // QoS1 중복
        assertEquals(1, r.state.queued)
        advanceTimeBy(3_000); runCurrent()
        advanceTimeBy(600); runCurrent()
        assertEquals(listOf("vm_1.m4a", "vm_2.m4a"), r.player.played)
        assertEquals("speech 가 없으면 닉네임으로", listOf("아빠 차 무전", "삼촌 차 무전"), r.voice.waited)
        r.playback.stop()
    }

    @Test
    fun `무전 보내기 중(held)에는 기다렸다가 끝난 뒤 재생`() = runTest {
        val r = Rig(this)
        r.playback.start(); runCurrent()
        r.playback.setHeld(true)
        r.emit(r.voiceEvent("vm_1"))
        advanceTimeBy(10_000); runCurrent()
        assertTrue(r.player.played.isEmpty())
        assertEquals(1, r.state.queued)
        assertTrue(r.state.held)
        r.playback.setHeld(false)
        runCurrent()
        assertEquals(listOf("vm_1.m4a"), r.player.played)
        r.playback.stop()
    }

    @Test
    fun `미루는 사이 60초를 넘기면 재생 직전에 지난 무전으로`() = runTest {
        val r = Rig(this)
        r.playback.start(); runCurrent()
        r.playback.setHeld(true)
        r.emit(r.voiceEvent("vm_1", ageMs = 50_000))
        advanceTimeBy(15_000); runCurrent()
        r.playback.setHeld(false)
        runCurrent()
        assertTrue(r.player.played.isEmpty())
        assertEquals(PastStatus.STALE, r.state.past.single().status)
        r.playback.stop()
    }

    @Test
    fun `재생 중 끊으면 INTERRUPTED 로 지난 무전에`() = runTest {
        val r = Rig(this)
        r.playback.start(); runCurrent()
        r.emit(r.voiceEvent("vm_1"))
        advanceTimeBy(1_000); runCurrent()
        assertTrue(r.playback.isPlaying)
        r.playback.interrupt("ptt-press")
        runCurrent()
        assertEquals(PastStatus.INTERRUPTED, r.state.past.single().status)
        assertFalse(r.playback.isPlaying)
        r.playback.stop()
    }

    @Test
    fun `내려받기 실패는 DOWNLOAD_FAILED, 음성 안내가 꺼져 있으면 알림만 생략`() = runTest {
        val r = Rig(this)
        r.playback.start(); runCurrent()
        r.fetchFails = true
        r.emit(r.voiceEvent("vm_1"))
        advanceTimeBy(1_000); runCurrent()
        assertEquals(PastStatus.DOWNLOAD_FAILED, r.state.past.single().status)
        assertEquals(1, r.state.downloadFailed)

        r.fetchFails = false
        r.announce = false
        r.emit(r.voiceEvent("vm_2"))
        advanceTimeBy(100); runCurrent()
        assertEquals(listOf("vm_2.m4a"), r.player.played)
        assertTrue(r.voice.waited.isEmpty())
        r.playback.stop()
    }

    @Test
    fun `그룹이 끝나면 큐·지난 무전·파일을 지운다`() = runTest {
        val r = Rig(this)
        r.playback.start(); runCurrent()
        r.emit(r.voiceEvent("vm_1"))
        advanceTimeBy(7_000); runCurrent()
        val file = r.state.past.single().file!!
        assertTrue(file.exists())
        r.playback.setHeld(true)
        r.emit(r.voiceEvent("vm_2"))
        r.session.value = null
        runCurrent()
        assertTrue(r.state.past.isEmpty())
        assertEquals(0, r.state.queued)
        assertFalse(file.exists())
        r.playback.stop()
    }

    @Test
    fun `내려받는 중 그룹이 바뀌어도 워커는 살아 있다 — 새 그룹 무전은 재생`() = runTest {
        val r = Rig(this)
        r.playback.start(); runCurrent()
        r.fetchDelayMs = 2_000
        r.emit(r.voiceEvent("vm_old"))
        advanceTimeBy(500); runCurrent()
        assertEquals(listOf("vm_old"), r.fetched)
        // 내려받는 사이 그룹이 바뀐다 → 그 내려받기는 취소(워커가 await 중)
        r.session.value = GroupSession("grp_2", "새 그룹", "veh_A", Place(37.0, 127.0, "집"), GroupTopics("s2", "e2"), 0L)
        runCurrent()
        advanceTimeBy(5_000); runCurrent()
        assertTrue("끝난 그룹 무전은 재생하지 않는다", r.player.played.isEmpty())
        assertTrue(r.state.past.isEmpty())

        r.fetchDelayMs = 0
        r.emit(r.voiceEvent("vm_new"))
        advanceTimeBy(100); runCurrent()
        assertEquals("새 그룹 무전은 재생된다(워커가 끝나지 않았다)", listOf("vm_new.m4a"), r.player.played)
        assertEquals("grp_2", r.state.playing!!.voice.groupId)
        r.playback.stop()
    }

    @Test
    fun `알림을 읽는 중 끊으면 TTS 를 멈추고 재생하지 않는다 — INTERRUPTED`() = runTest {
        val r = Rig(this)
        r.playback.start(); runCurrent()
        r.voice.announceMs = 2_000
        r.emit(r.voiceEvent("vm_1"))
        advanceTimeBy(500); runCurrent()
        assertEquals(listOf("아빠 차 무전"), r.voice.waited)
        assertFalse("알림 중(아직 소리 재생 전)", r.player.playing)
        r.playback.interrupt("ptt-press")
        runCurrent()
        assertEquals("알림 TTS 를 멈췄다", 1, r.voice.stops)
        advanceTimeBy(5_000); runCurrent()
        assertTrue("받은 무전을 재생하지 않는다", r.player.played.isEmpty())
        assertEquals(PastStatus.INTERRUPTED, r.state.past.single().status)
        r.playback.stop()
    }

    @Test
    fun `알림을 읽는 중 그룹이 끝나면 멈추고 재생·목록 없이 파일을 지운다`() = runTest {
        val r = Rig(this)
        r.playback.start(); runCurrent()
        r.voice.announceMs = 2_000
        r.emit(r.voiceEvent("vm_1"))
        advanceTimeBy(500); runCurrent()
        val file = File(r.dir, "vm_1.m4a")
        assertTrue(file.exists())
        r.session.value = null
        runCurrent()
        assertEquals(1, r.voice.stops)
        advanceTimeBy(5_000); runCurrent()
        assertTrue(r.player.played.isEmpty())
        assertTrue(r.state.past.isEmpty())
        assertNull(r.state.playing)
        assertFalse(file.exists())
        r.playback.stop()
    }

    @Test
    fun `재생 상태 → PttUi PLAYING(보낸 차 이름·색·경과·길이)`() = runTest {
        val r = Rig(this)
        r.playback.start(); runCurrent()
        r.emit(r.voiceEvent("vm_long", durationMs = 7_000))
        advanceTimeBy(2_000); runCurrent()
        val ui = app.donghaeng.car.surface.PttUiMapper.map(PttState(), r.state)
        assertEquals(app.donghaeng.car.feature.drive.PttPhase.PLAYING, ui.phase)
        assertEquals("아빠 차", ui.fromName)
        assertEquals(0xFF2E7DFFL, ui.fromColorArgb)
        assertEquals("0:07", ui.durationText)
        assertEquals(2, ui.seconds)
        r.playback.stop()
    }
}
