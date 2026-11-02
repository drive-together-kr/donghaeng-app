package app.donghaeng.car.domain.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** A120: 음성 안내·자동 따라가기·지연 표시가 앱을 다시 켜도 남는다. release 기본값은 지연 표시 끔 */
class UserSettingsTest {

    @Test
    fun `saved switches survive a restart (new instance over the same store)`() {
        val store = InMemoryUserSettingsStore()
        val first = UserSettings(store)
        first.setVoiceEnabled(false)
        first.setAutoFollow(true)
        first.setLatencyOverlay(false)

        val afterRestart = UserSettings(store)
        val applied = mutableListOf<Pair<String, Boolean>>()
        afterRestart.applyTo(voice = { applied += "voice" to it }, autoFollow = { applied += "auto" to it }, latencyOverlay = { applied += "latency" to it })
        assertEquals(listOf("voice" to false, "auto" to true, "latency" to false), applied)
    }

    @Test
    fun `defaults - voice on, auto follow off, latency overlay follows the build (release off)`() {
        val release = UserSettings(InMemoryUserSettingsStore(), UserSettings.Defaults(latencyOverlay = false))
        assertEquals(true, release.voiceEnabled)
        assertEquals(false, release.autoFollow)
        assertEquals(false, release.latencyOverlay)
        assertEquals(true, UserSettings(InMemoryUserSettingsStore(), UserSettings.Defaults(latencyOverlay = true)).latencyOverlay)
    }
}
