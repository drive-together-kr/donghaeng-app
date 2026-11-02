package app.donghaeng.car.domain.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A120: 자동 따라가기·지연 표시가 앱을 다시 켜도 남는다. release 기본값은 지연 표시 끔. RA-3: 음성 안내는 여기 저장하지 않는다 */
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
     * RA-3: '음성 안내'는 음성 쪽(B066 VoiceAnnouncer 저장소) 하나만 저장한다 — 예전엔 `dh_settings` 에도 따로 저장·적용해(두 곳)
     * 한쪽만 바뀌면 다음 시작 때 값이 어긋났다. UserSettings 의 모든 동작이 건드리는 키는 자동 따라가기·지연 표시뿐이어야 한다.
     */
    @Test
    fun `RA-3 - the voice switch is not stored or applied here (single owner is the voice side)`() {
        val store = RecordingStore()
        val settings = UserSettings(store)
        settings.setAutoFollow(true)
        settings.setLatencyOverlay(true)
        settings.applyTo(autoFollow = {}, latencyOverlay = {})
        assertEquals(setOf(UserSettings.KEY_AUTO_FOLLOW, UserSettings.KEY_LATENCY), store.touched)
        assertTrue("voiceEnabled 키를 읽거나 쓰지 않는다", "voiceEnabled" !in store.touched)
    }
}
