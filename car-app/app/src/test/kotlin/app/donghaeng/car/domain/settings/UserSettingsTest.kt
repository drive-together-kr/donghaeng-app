package app.donghaeng.car.domain.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A120: 자동 따라가기·지연 표시·음성 안내가 앱을 다시 켜도 남는다. release 기본값은 지연 표시 끔. 음성 안내는 여기 한 곳(RB-5, 병합 정리) */
class UserSettingsTest {

    /** 읽고 쓴 키를 모두 남기는 저장소 */
    private class RecordingStore : UserSettingsStore {
        val inner = InMemoryUserSettingsStore()
        val touched = linkedSetOf<String>()
        override fun getBoolean(key: String): Boolean? = inner.getBoolean(key).also { touched += key }
        override fun putBoolean(key: String, value: Boolean) {
            touched += key
            inner.putBoolean(key, value)
        }
    }

    @Test
    fun `saved switches survive a restart (new instance over the same store)`() {
        val store = InMemoryUserSettingsStore()
        val first = UserSettings(store)
        first.setAutoFollow(true)
        first.setLatencyOverlay(false)

        val afterRestart = UserSettings(store)
        val applied = mutableListOf<Pair<String, Boolean>>()
        afterRestart.applyTo(autoFollow = { applied += "auto" to it }, latencyOverlay = { applied += "latency" to it })
        assertEquals(listOf("auto" to true, "latency" to false), applied)
    }

    @Test
    fun `defaults - auto follow off, latency overlay follows the build (release off)`() {
        val release = UserSettings(InMemoryUserSettingsStore(), UserSettings.Defaults(latencyOverlay = false))
        assertEquals(false, release.autoFollow)
        assertEquals(false, release.latencyOverlay)
        assertEquals(true, UserSettings(InMemoryUserSettingsStore(), UserSettings.Defaults(latencyOverlay = true)).latencyOverlay)
    }

    /**
     * 음성 안내 저장 주인(2026-11-02 병합 정리): RA-3 은 음성 쪽(B066) 저장소를, RB-5 는 B066 을 걷어내고 이곳을 주인으로 정해, 둘이 함께 들어가자
     * '음성 안내'가 어디에도 저장되지 않았다(앱을 다시 켜면 늘 켬). 이제 저장은 여기 한 곳 — 새 키 [UserSettings.KEY_VOICE] 만 읽고 쓰며,
     * 배치 A 중간 빌드가 `voiceEnabled` 키에 남겼을 수 있는 옛 값은 읽지 않는다. 음성 엔진 쪽 저장 금지는 VoiceAnnouncerQueueTest 가 지킨다.
     */
    @Test
    fun `the voice switch is stored here only, under a new key, and survives a restart`() {
        val store = RecordingStore()
        store.inner.putBoolean("voiceEnabled", false)   // 옛 키의 남은 값
        assertEquals("저장값이 없으면 켬(옛 키는 읽지 않는다)", true, UserSettings(store).voiceEnabled)
        UserSettings(store).setVoiceEnabled(false)
        assertEquals("다시 켜도 꺼짐", false, UserSettings(store).voiceEnabled)
        UserSettings(store).setAutoFollow(true)
        UserSettings(store).setLatencyOverlay(true)
        assertEquals(setOf(UserSettings.KEY_VOICE, UserSettings.KEY_AUTO_FOLLOW, UserSettings.KEY_LATENCY), store.touched)
        assertTrue("옛 키 voiceEnabled 를 읽거나 쓰지 않는다", "voiceEnabled" !in store.touched)
    }
}
