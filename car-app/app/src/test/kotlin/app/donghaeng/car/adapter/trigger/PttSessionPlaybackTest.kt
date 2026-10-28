package app.donghaeng.car.adapter.trigger

import android.media.session.PlaybackState
import app.donghaeng.car.domain.ptt.InMemoryPttSettingsStore
import app.donghaeng.car.domain.ptt.PttSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 무전 MediaSession 재생 상태 고정 (F16, 2026-10-28 실측 — PLAYING 이면 Pleos 미디어 프레임워크가 동행을 '재생 중' 미디어로 잡는다).
 * 근거 표는 [PttSessionPlayback] KDoc. 이 테스트가 깨지면 표를 다시 재고 같이 고친다.
 */
class PttSessionPlaybackTest {

    @Test
    fun `세션 재생 상태는 PLAYING 이 아니라 PAUSED — 속도 0, 메타데이터 없음`() {
        assertNotEquals("PLAYING 이면 MediaDataProvider·GleoMediaSession 이 isPlaying=true 로 잡는다", PlaybackState.STATE_PLAYING, PttSessionPlayback.STATE)
        assertEquals(PlaybackState.STATE_PAUSED, PttSessionPlayback.STATE)
        assertEquals(0f, PttSessionPlayback.SPEED, 0f)
        assertFalse("제목·앨범 아트를 넣지 않는다(미디어 위젯 항목이 생기지 않게)", PttSessionPlayback.SETS_METADATA)
    }

    @Test
    fun `미디어 버튼을 받는 동작은 그대로 — PLAY_PAUSE·PLAY·PAUSE`() {
        val a = PttSessionPlayback.ACTIONS
        assertTrue(a and PlaybackState.ACTION_PLAY_PAUSE != 0L)
        assertTrue(a and PlaybackState.ACTION_PLAY != 0L)
        assertTrue(a and PlaybackState.ACTION_PAUSE != 0L)
        assertEquals("다음 곡·이전 곡은 받지 않는다", 0L, a and (PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS))
    }

    @Test
    fun `로그 한 줄 — state=PAUSED, 상태 이름`() {
        assertEquals("state=PAUSED speed=0.0 metadata=none", PttSessionPlayback.describe())
        assertEquals("PLAYING", PttSessionPlayback.stateName(PlaybackState.STATE_PLAYING))
        assertEquals("STOPPED", PttSessionPlayback.stateName(PlaybackState.STATE_STOPPED))
        assertEquals("NONE", PttSessionPlayback.stateName(PlaybackState.STATE_NONE))
        assertEquals("STATE_6", PttSessionPlayback.stateName(6))
    }

    @Test
    fun `트리거 상태 줄 — 세션이 켜지면 sessionState=PAUSED, 꺼지면 -`() {
        val t = FakeVoiceTrigger(PttSettings(InMemoryPttSettingsStore(true)), clock = { 0L })
        t.start()
        assertNull(t.status.value.sessionState)
        t.setGroupActive(true)
        assertEquals("PAUSED", t.status.value.sessionState)
        assertTrue(t.status.value.toString().contains("session=true sessionState=PAUSED"))
        t.setGroupActive(false)
        assertTrue(t.status.value.toString().contains("session=false sessionState=-"))
    }
}
