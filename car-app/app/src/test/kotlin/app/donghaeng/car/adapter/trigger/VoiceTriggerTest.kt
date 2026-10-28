package app.donghaeng.car.adapter.trigger

import android.view.KeyEvent
import app.donghaeng.car.domain.ptt.InMemoryPttSettingsStore
import app.donghaeng.car.domain.ptt.PttSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 무전 트리거 — 키 down 1회·반복/up 소비, 그룹·설정·다른 앱 미디어(양보·15초 뒤 되찾기) */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceTriggerTest {

    private val down = KeyEvent.ACTION_DOWN
    private val up = KeyEvent.ACTION_UP

    @Test
    fun `키 필터 — down 한 번만 누름, 반복·up 은 소비, 300 ms 안 중복 down 도 소비, 무전 키 아니면 통과`() {
        val f = PttKeyFilter()
        val keys = PttKeys.BACKGROUND
        assertEquals(PttKeyFilter.Verdict.PRESS, f.onKey(KeyEvent.KEYCODE_HEADSETHOOK, down, 0, 1_000, keys))
        assertEquals(PttKeyFilter.Verdict.CONSUME, f.onKey(KeyEvent.KEYCODE_HEADSETHOOK, down, 1, 1_050, keys))
        assertEquals(PttKeyFilter.Verdict.CONSUME, f.onKey(KeyEvent.KEYCODE_HEADSETHOOK, up, 0, 1_100, keys))
        assertEquals("두 경로로 온 같은 누름", PttKeyFilter.Verdict.CONSUME, f.onKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, down, 0, 1_200, keys))
        assertEquals(PttKeyFilter.Verdict.PRESS, f.onKey(KeyEvent.KEYCODE_MEDIA_PLAY, down, 0, 1_400, keys))
        assertEquals(PttKeyFilter.Verdict.PASS, f.onKey(KeyEvent.KEYCODE_VOLUME_UP, down, 0, 5_000, keys))
        assertEquals("SEARCH 는 백그라운드 키가 아니다", PttKeyFilter.Verdict.PASS, f.onKey(KeyEvent.KEYCODE_SEARCH, down, 0, 5_000, keys))
        assertEquals(PttKeyFilter.Verdict.PRESS, f.onKey(KeyEvent.KEYCODE_SEARCH, down, 0, 5_000, PttKeys.FOREGROUND))
    }

    @Test
    fun `세션 정책 — 그룹·설정·다른 앱 미디어·되찾기 지연`() {
        assertFalse(TriggerPolicy.sessionActive(groupActive = false, handleButton = true, otherMediaActive = false, otherMediaStoppedAgoMs = null))
        assertFalse(TriggerPolicy.sessionActive(groupActive = true, handleButton = false, otherMediaActive = false, otherMediaStoppedAgoMs = null))
        assertFalse(TriggerPolicy.sessionActive(groupActive = true, handleButton = true, otherMediaActive = true, otherMediaStoppedAgoMs = null))
        assertFalse(TriggerPolicy.sessionActive(groupActive = true, handleButton = true, otherMediaActive = false, otherMediaStoppedAgoMs = 5_000))
        assertTrue(TriggerPolicy.sessionActive(groupActive = true, handleButton = true, otherMediaActive = false, otherMediaStoppedAgoMs = 15_000))
        assertTrue(TriggerPolicy.sessionActive(groupActive = true, handleButton = true, otherMediaActive = false, otherMediaStoppedAgoMs = null))
    }

    @Test
    fun `Fake 트리거 — 그룹이 있을 때만 키, 음악이 재생 중이면 양보, 설정 끄면 키 안 씀, 탭은 설정과 무관`() = runTest {
        var now = 10_000L
        val settings = PttSettings(InMemoryPttSettingsStore(true))
        val t = FakeVoiceTrigger(settings, clock = { now })
        val presses = mutableListOf<PttPress>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { t.presses.toList(presses) }
        t.start()

        assertFalse("그룹 없음 → 세션 없음", t.sessionActive)
        assertFalse(t.simulateMediaKey(KeyEvent.KEYCODE_HEADSETHOOK, down))
        assertFalse("그룹 없음 → 액티비티 키도 흘려보낸다", t.onActivityKey(KeyEvent.KEYCODE_SEARCH, down, 0, now))

        t.setGroupActive(true)
        assertTrue(t.sessionActive)
        assertTrue(t.simulateMediaKey(KeyEvent.KEYCODE_HEADSETHOOK, down, eventTimeMs = now))
        assertTrue("up 은 소비만", t.simulateMediaKey(KeyEvent.KEYCODE_HEADSETHOOK, up, eventTimeMs = now + 80))
        assertEquals(1, presses.size)
        assertEquals(PttTriggerSource.MEDIA_SESSION, presses[0].source)
        assertEquals("HEADSETHOOK", presses[0].key)

        now += 1_000
        assertTrue(t.onActivityKey(KeyEvent.KEYCODE_SEARCH, down, 0, now))
        assertEquals(PttTriggerSource.ACTIVITY_KEY, presses.last().source)
        assertFalse("볼륨 키는 무전이 아니다", t.onActivityKey(KeyEvent.KEYCODE_VOLUME_DOWN, down, 0, now + 500))

        // 음악 앱 재생 → 세션 양보(키는 음악 앱 몫)
        t.setOtherMediaActive(true)
        assertFalse(t.sessionActive)
        assertFalse(t.simulateMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, down, eventTimeMs = now + 2_000))
        t.setOtherMediaActive(false)
        now += 5_000
        assertFalse("멈춘 직후 15초는 그대로 둔다", t.sessionActive)
        now += 10_000
        assertTrue(t.sessionActive)

        // 설정 끔 → 키 안 씀, 탭은 된다
        settings.setHandleButton(false)
        t.setOtherMediaActive(false)
        assertFalse(t.sessionActive)
        assertFalse(t.onActivityKey(KeyEvent.KEYCODE_HEADSETHOOK, down, 0, now))
        t.press(PttTriggerSource.PANEL)
        assertEquals(PttTriggerSource.PANEL, presses.last().source)
        assertEquals(3, presses.size)
        job.cancel()
    }
}
