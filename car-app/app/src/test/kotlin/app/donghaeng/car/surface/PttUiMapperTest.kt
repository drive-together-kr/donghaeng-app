package app.donghaeng.car.surface

import app.donghaeng.car.domain.ptt.PlayingVoice
import app.donghaeng.car.domain.ptt.PttNotice
import app.donghaeng.car.domain.ptt.PttStage
import app.donghaeng.car.domain.ptt.PttState
import app.donghaeng.car.domain.ptt.ReceivedVoice
import app.donghaeng.car.domain.ptt.VoicePlaybackState
import app.donghaeng.car.feature.drive.PttPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PttUiMapperTest {

    private val voice = ReceivedVoice("evt_1", "vm_1", "grp_1", "veh_A", "아빠 차", "#2E7DFF", 7_400, "audio/mp4", 0, "아빠 차 무전")
    private val playing = VoicePlaybackState(playing = PlayingVoice(voice, positionMs = 3_200), queued = 1)

    @Test
    fun `보내기 단계별 화면 모델`() {
        val rec = PttUiMapper.map(PttState(stage = PttStage.RECORDING, remainingS = 9, totalS = 15, level = 0.7f), VoicePlaybackState())
        assertEquals(PttPhase.RECORDING, rec.phase)
        assertEquals(9, rec.seconds)
        assertEquals(15, rec.totalSeconds)
        assertEquals(0.7f, rec.level, 0.001f)

        val confirm = PttUiMapper.map(PttState(stage = PttStage.CONFIRMING, remainingS = 4, totalS = 5, listening = true), VoicePlaybackState())
        assertEquals(PttPhase.CONFIRMING, confirm.phase)
        assertTrue(confirm.listening)
        assertFalse(confirm.fallbackCountdown)
        assertTrue(PttUiMapper.map(PttState(stage = PttStage.CONFIRMING, remainingS = 5, totalS = 5, countdownMode = true), VoicePlaybackState()).fallbackCountdown)

        val sending = PttUiMapper.map(PttState(stage = PttStage.SENDING), VoicePlaybackState())
        assertEquals(PttPhase.SENT, sending.phase)
        assertTrue(sending.sending)
        val sent = PttUiMapper.map(PttState(stage = PttStage.SENT, sentTo = 3), VoicePlaybackState())
        assertFalse(sent.sending)
        assertEquals(3, sent.sentTo)

        val notice = PttUiMapper.map(PttState(stage = PttStage.NOTICE, notice = PttNotice.KEPT_UNSENT, micGranted = false), VoicePlaybackState(), unsentCount = 2)
        assertEquals(PttPhase.IDLE, notice.phase)
        assertEquals(PttNotice.KEPT_UNSENT, notice.notice)
        assertTrue(notice.micDenied)
        assertEquals(2, notice.unsentCount)
        assertNull(PttUiMapper.map(PttState(), VoicePlaybackState()).notice)
    }

    @Test
    fun `받은 무전 재생 — 이름·색·경과·길이, 보내기가 바쁘면 보내기 화면이 먼저`() {
        val ui = PttUiMapper.map(PttState(), playing)
        assertEquals(PttPhase.PLAYING, ui.phase)
        assertEquals("아빠 차", ui.fromName)
        assertEquals(0xFF2E7DFFL, ui.fromColorArgb)
        assertEquals(3, ui.seconds)
        assertEquals("0:07", ui.durationText)
        assertEquals(1, ui.queuedIncoming)
        assertFalse(ui.playingEnded)
        assertTrue(PttUiMapper.map(PttState(), playing.copy(playing = playing.playing!!.copy(ended = true))).playingEnded)

        assertEquals(PttPhase.RECORDING, PttUiMapper.map(PttState(stage = PttStage.RECORDING, remainingS = 15, totalS = 15), playing).phase)
        assertEquals("보냄 3초보다 재생이 먼저", PttPhase.PLAYING, PttUiMapper.map(PttState(stage = PttStage.SENT, sentTo = 2), playing).phase)
    }
}
