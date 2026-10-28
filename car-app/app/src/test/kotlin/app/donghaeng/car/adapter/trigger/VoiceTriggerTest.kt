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
    fun `다른 앱 미디어 판정 — 우리 무음 창 안의 MEDIA 는 모름(창이 끝나면 다시 읽는다), MEDIA 없으면 창 안이어도 false`() {
        val until = 10_000L
        assertEquals(null, TriggerPolicy.otherMediaFrom(mediaPlaying = true, now = 9_000, ownSilenceUntil = until))
        assertEquals(false, TriggerPolicy.otherMediaFrom(mediaPlaying = false, now = 9_000, ownSilenceUntil = until))
        assertEquals("창이 끝난 뒤 다시 읽었을 때 남은 MEDIA = 다른 앱", true, TriggerPolicy.otherMediaFrom(mediaPlaying = true, now = 10_100, ownSilenceUntil = until))
        assertEquals(false, TriggerPolicy.otherMediaFrom(mediaPlaying = false, now = 10_100, ownSilenceUntil = until))
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

    @Test
    fun `화면 키 가로채기 정책 — 그룹·설정 둘 다일 때만, SEARCH 는 늘, 재생·정지 키는 음악에 양보`() {
        val hook = KeyEvent.KEYCODE_HEADSETHOOK
        val search = KeyEvent.KEYCODE_SEARCH
        assertFalse("그룹 없음 → 원래 동작", TriggerPolicy.interceptActivityKey(hook, groupActive = false, handleButton = true, yieldingMedia = false))
        assertFalse("설정 끔 → 원래 동작", TriggerPolicy.interceptActivityKey(search, groupActive = true, handleButton = false, yieldingMedia = false))
        assertTrue(TriggerPolicy.interceptActivityKey(hook, groupActive = true, handleButton = true, yieldingMedia = false))
        assertTrue(TriggerPolicy.interceptActivityKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, groupActive = true, handleButton = true, yieldingMedia = false))
        assertFalse("음악 재생 중 → 재생/정지 키는 음악 앱 몫", TriggerPolicy.interceptActivityKey(hook, groupActive = true, handleButton = true, yieldingMedia = true))
        assertFalse(TriggerPolicy.interceptActivityKey(KeyEvent.KEYCODE_MEDIA_PLAY, groupActive = true, handleButton = true, yieldingMedia = true))
        assertTrue("SEARCH 는 미디어 키가 아니다", TriggerPolicy.interceptActivityKey(search, groupActive = true, handleButton = true, yieldingMedia = true))
        assertFalse("무전 키가 아니면 통과", TriggerPolicy.interceptActivityKey(KeyEvent.KEYCODE_VOLUME_UP, groupActive = true, handleButton = true, yieldingMedia = false))
    }

    @Test
    fun `Fake 트리거 — 음악 재생 중·멈춘 직후 15초는 화면 재생 키도 흘려보내고 SEARCH 는 무전`() = runTest {
        var now = 50_000L
        val t = FakeVoiceTrigger(PttSettings(InMemoryPttSettingsStore(true)), clock = { now })
        val presses = mutableListOf<PttPress>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { t.presses.toList(presses) }
        t.start()
        t.setGroupActive(true)
        t.setOtherMediaActive(true)
        assertFalse(t.onActivityKey(KeyEvent.KEYCODE_HEADSETHOOK, down, 0, now))
        assertFalse("up 도 흘려보낸다(음악 앱이 down/up 짝을 받게)", t.onActivityKey(KeyEvent.KEYCODE_HEADSETHOOK, up, 0, now + 50))
        assertTrue(t.onActivityKey(KeyEvent.KEYCODE_SEARCH, down, 0, now + 100))
        t.setOtherMediaActive(false)
        now += 5_000
        assertFalse("멈춘 직후(되찾기 대기)", t.onActivityKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, down, 0, now))
        now += 10_000
        assertTrue(t.onActivityKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, down, 0, now))
        assertEquals(listOf("SEARCH", "MEDIA_PLAY_PAUSE"), presses.map { it.key })
        job.cancel()
    }
}
