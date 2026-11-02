package app.donghaeng.car.domain.settings

import android.content.Context
import app.donghaeng.car.core.common.DhLog

/** 설정 값 저장소 — 구현: [SharedPrefsUserSettingsStore](기기, `dh_settings`) / [InMemoryUserSettingsStore](테스트) */
interface UserSettingsStore {
    /** 저장된 값, 없거나 읽지 못하면 null */
    fun getBoolean(key: String): Boolean?

    fun putBoolean(key: String, value: Boolean)
}

class InMemoryUserSettingsStore : UserSettingsStore {
    private val map = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    override fun getBoolean(key: String): Boolean? = map[key]

    override fun putBoolean(key: String, value: Boolean) {
        map[key] = value
    }
}

/** SharedPreferences `dh_settings` — 읽기·쓰기 실패는 기본값으로(크래시 없음) */
class SharedPrefsUserSettingsStore(context: Context) : UserSettingsStore {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun getBoolean(key: String): Boolean? = runCatching { if (prefs.contains(key)) prefs.getBoolean(key, false) else null }.getOrNull()

    override fun putBoolean(key: String, value: Boolean) {
        runCatching { prefs.edit().putBoolean(key, value).apply() }.onFailure { DhLog.w("settings save failed key=$key ${it.javaClass.simpleName}") }
    }

    private companion object {
        const val FILE = "dh_settings"
    }
}

/**
 * 설정 화면 스위치 중 앱을 다시 켜도 남아야 하는 값 (A120) — 음성 안내·선두 경로 자동 따라가기·지연 표시.
 * 예전엔 엔진 메모리에만 있어 차를 다시 켜면 초기값으로 돌아갔다('핸들 버튼으로 무전'만 PttSettings 에 저장).
 *
 * - 저장은 사용자가 설정 화면에서 바꿀 때만([SettingsViewModel]) — 진단·디버그 토글은 저장하지 않는다.
 * - 앱 시작 때 엔진에 [applyTo] 로 넣는다(di/SignalsModule).
 * - 지연 표시 기본값: debug 켬(시연·측정) / **release 끔** — 사용자 화면에 개발용 수치를 보이지 않는다.
 */
class UserSettings(
    private val store: UserSettingsStore,
    private val defaults: Defaults = Defaults(),
) {
    data class Defaults(val voiceEnabled: Boolean = true, val autoFollow: Boolean = false, val latencyOverlay: Boolean = true)

    val voiceEnabled: Boolean get() = store.getBoolean(KEY_VOICE) ?: defaults.voiceEnabled
    val autoFollow: Boolean get() = store.getBoolean(KEY_AUTO_FOLLOW) ?: defaults.autoFollow
    val latencyOverlay: Boolean get() = store.getBoolean(KEY_LATENCY) ?: defaults.latencyOverlay

    fun setVoiceEnabled(v: Boolean) = store.putBoolean(KEY_VOICE, v)
    fun setAutoFollow(v: Boolean) = store.putBoolean(KEY_AUTO_FOLLOW, v)
    fun setLatencyOverlay(v: Boolean) = store.putBoolean(KEY_LATENCY, v)

    /** 저장값(없으면 기본값)을 엔진에 넣는다 — 앱 시작 때 한 번 */
    fun applyTo(voice: (Boolean) -> Unit, autoFollow: (Boolean) -> Unit, latencyOverlay: (Boolean) -> Unit) {
        voice(voiceEnabled)
        autoFollow(this.autoFollow)
        latencyOverlay(this.latencyOverlay)
    }

    companion object {
        const val KEY_VOICE = "voiceEnabled"
        const val KEY_AUTO_FOLLOW = "autoFollow"
        const val KEY_LATENCY = "latencyOverlay"
    }
}
