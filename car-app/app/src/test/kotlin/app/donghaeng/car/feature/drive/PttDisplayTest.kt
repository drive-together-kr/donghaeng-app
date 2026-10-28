package app.donghaeng.car.feature.drive

import app.donghaeng.car.domain.ptt.PastStatus
import app.donghaeng.car.domain.ptt.PttNotice
import app.donghaeng.car.domain.ptt.ReceivedVoice
import app.donghaeng.car.domain.ptt.UnsentReason
import app.donghaeng.car.domain.ptt.UnsentVoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset

/** F16 무전 줄 판단(패널·대시보드 공용) + 정차 대시보드 목록 문구 */
class PttDisplayTest {

    @Test
    fun `무전 줄 종류 — 단계·안내·마이크·카운트다운·보내는 중·재생 끝`() {
        assertEquals(PttDisplayKind.IDLE, PttDisplay.kind(PttUi()))
        assertEquals(PttDisplayKind.MIC_DENIED, PttDisplay.kind(PttUi(micDenied = true)))
        assertEquals("안내가 마이크 표시보다 먼저", PttDisplayKind.NOTICE, PttDisplay.kind(PttUi(micDenied = true, notice = PttNotice.MIC_PERMISSION)))
        assertEquals(PttDisplayKind.RECORDING, PttDisplay.kind(PttUi(PttPhase.RECORDING, seconds = 12, totalSeconds = 15)))
        assertEquals(PttDisplayKind.CONFIRM_VOICE, PttDisplay.kind(PttUi(PttPhase.CONFIRMING, seconds = 4, listening = true)))
        assertEquals(PttDisplayKind.CONFIRM_COUNTDOWN, PttDisplay.kind(PttUi(PttPhase.CONFIRMING, seconds = 4, fallbackCountdown = true)))
        assertEquals(PttDisplayKind.SENDING, PttDisplay.kind(PttUi(PttPhase.SENT, sending = true)))
        assertEquals(PttDisplayKind.SENT, PttDisplay.kind(PttUi(PttPhase.SENT, sentTo = 2)))
        assertEquals(PttDisplayKind.PLAYING, PttDisplay.kind(PttUi(PttPhase.PLAYING, seconds = 3)))
        assertEquals(PttDisplayKind.PLAYED, PttDisplay.kind(PttUi(PttPhase.PLAYING, seconds = 7, playingEnded = true)))
        // 녹음 중에는 마이크 표시를 덮는다(주입 녹음은 권한 없이도 진행)
        assertEquals(PttDisplayKind.RECORDING, PttDisplay.kind(PttUi(PttPhase.RECORDING, micDenied = true)))
    }

    @Test
    fun `안내 색 — 버린 것은 중립, 남았거나 실패한 것은 주의`() {
        assertEquals(Tone.NEUTRAL, PttDisplay.noticeTone(PttNotice.TOO_SHORT))
        assertEquals(Tone.NEUTRAL, PttDisplay.noticeTone(PttNotice.CANCELLED))
        assertEquals(Tone.NEUTRAL, PttDisplay.noticeTone(PttNotice.NO_GROUP))
        assertEquals(Tone.CAUTION, PttDisplay.noticeTone(PttNotice.KEPT_UNSENT))
        assertEquals(Tone.CAUTION, PttDisplay.noticeTone(PttNotice.SEND_FAILED))
        assertEquals(Tone.CAUTION, PttDisplay.noticeTone(PttNotice.RECORD_FAILED))
        assertEquals(Tone.CAUTION, PttDisplay.noticeTone(PttNotice.MIC_PERMISSION))
    }

    @Test
    fun `보내지 않은 무전 줄 — 길이·벽시계 시각·사유`() {
        val zone = ZoneOffset.ofHours(9)
        val at = LocalDateTime.of(2026, 10, 28, 14, 3).atZone(zone).toInstant().toEpochMilli()
        val rows = PttDisplay.unsentRows(
            listOf(
                UnsentVoice("u2", "grp_1", File("u2.m4a"), "audio/mp4", 7_400, at, UnsentReason.NO_ANSWER),
                UnsentVoice("u1", "grp_1", File("u1.m4a"), "audio/mp4", 75_000, at - 60_000, UnsentReason.SEND_FAILED, failure = "HTTP 429 VOICE_RATE_LIMITED"),
            ),
            zone,
        )
        assertEquals(listOf("u2", "u1"), rows.map { it.id })
        assertEquals("무전 0:07 · 14:03", rows[0].text)
        assertEquals("대답 없음", rows[0].reasonText)
        assertFalse(rows[0].failed)
        assertEquals("무전 1:15 · 14:02", rows[1].text)
        assertEquals("보내기 실패", rows[1].reasonText)
        assertTrue(rows[1].failed)
    }

    @Test
    fun `지난 무전 줄 — 대기 중 제외, 최대 5개, 상태 꼬리표, 서버 시계 나이, 이름 없으면 무전만`() {
        val now = 1_700_000_000_000L
        fun v(id: String, status: PastStatus, ageS: Long, nick: String? = "아빠 차", color: String? = "#2E7DFF") =
            ReceivedVoice("e_$id", id, "grp_1", "veh_A", nick, color, 7_000, "audio/mp4", now - ageS * 1_000, "아빠 차 무전", status)
        val rows = PttDisplay.pastRows(
            listOf(
                v("q", PastStatus.QUEUED, 1),
                v("a", PastStatus.PLAYED, 30),
                v("b", PastStatus.STALE, 180, nick = null, color = null),
                v("c", PastStatus.INTERRUPTED, 240),
                v("d", PastStatus.DOWNLOAD_FAILED, 300),
                v("e", PastStatus.PLAY_FAILED, 400),
                v("f", PastStatus.PLAYED, 500),
            ),
            serverNowMs = now,
        )
        assertEquals(listOf("a", "b", "c", "d", "e"), rows.map { it.voiceId })
        assertEquals("아빠 차 무전 0:07", rows[0].text)
        assertEquals("30초 전", rows[0].whenText)
        assertNull("다 들은 것은 꼬리표 없음", rows[0].statusText)
        assertEquals(0xFF2E7DFFL, rows[0].colorArgb)
        assertEquals("무전 0:07", rows[1].text)
        assertNull(rows[1].colorArgb)
        assertEquals("못 들음", rows[1].statusText)
        assertEquals("3분 전", rows[1].whenText)
        assertEquals("끊김", rows[2].statusText)
        assertEquals("받기 실패", rows[3].statusText)
        assertEquals(Tone.CAUTION, rows[3].tone)
        assertEquals("재생 실패", rows[4].statusText)
    }
}
