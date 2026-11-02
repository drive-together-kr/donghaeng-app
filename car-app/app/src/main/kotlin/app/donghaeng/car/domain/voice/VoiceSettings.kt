package app.donghaeng.car.domain.voice

import android.content.Context

/**
 * 설정 '음성 안내' 스위치 저장소(B066) — 기기: SharedPreferences `dh_voice`, 테스트: 메모리.
 * 무전 설정([app.donghaeng.car.domain.ptt.PttSettingsStore])과 같은 모양 — 프로세스가 다시 시작돼도(차 재시동·AAOS 정리) 끈 것이 유지된다.
 */
interface VoiceSettingsStore {
    fun loadEnabled(): Boolean
    fun saveEnabled(enabled: Boolean)
}

class SharedPrefsVoiceSettingsStore(context: Context) : VoiceSettingsStore {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    override fun loadEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, true)
    override fun saveEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    private companion object {
        const val FILE = "dh_voice"
        const val KEY_ENABLED = "enabled"
    }
}

class InMemoryVoiceSettingsStore(var value: Boolean = true) : VoiceSettingsStore {
    var saves = 0
        private set

    override fun loadEnabled(): Boolean = value
    override fun saveEnabled(enabled: Boolean) {
        value = enabled
        saves++
    }
}
