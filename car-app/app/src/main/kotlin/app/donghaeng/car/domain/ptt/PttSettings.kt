package app.donghaeng.car.domain.ptt

import android.content.Context
import app.donghaeng.car.core.common.DhLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 무전 설정 저장소 — 기기: SharedPreferences `dh_ptt`, 테스트: 메모리 */
interface PttSettingsStore {
    fun loadHandleButton(): Boolean
    fun saveHandleButton(enabled: Boolean)
}

class SharedPrefsPttSettingsStore(context: Context) : PttSettingsStore {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    override fun loadHandleButton(): Boolean = prefs.getBoolean(KEY_HANDLE_BUTTON, true)
    override fun saveHandleButton(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_HANDLE_BUTTON, enabled).apply()
    }

    private companion object {
        const val FILE = "dh_ptt"
        const val KEY_HANDLE_BUTTON = "handleButton"
    }
}

class InMemoryPttSettingsStore(var value: Boolean = true) : PttSettingsStore {
    override fun loadHandleButton(): Boolean = value
    override fun saveHandleButton(enabled: Boolean) {
        value = enabled
    }
}

/**
 * 무전 설정 (F16). **'핸들 버튼으로 무전'**(기본 켬) — 끄면 핸들·미디어 키(MediaSession·MainActivity 키)를 무전에 쓰지 않는다
 * (음악 앱 재생/정지 키를 그대로 둔다). 패널·대시보드 [무전] 탭은 설정과 무관하다. 설정 화면 스위치는 UI 담당이 [setHandleButton] 에 잇는다.
 */
class PttSettings(private val store: PttSettingsStore) {
    private val _handleButton = MutableStateFlow(runCatching { store.loadHandleButton() }.getOrDefault(true))
    val handleButton: StateFlow<Boolean> = _handleButton.asStateFlow()

    fun setHandleButton(enabled: Boolean) {
        runCatching { store.saveHandleButton(enabled) }
        _handleButton.value = enabled
        DhLog.i("ptt: handle button=$enabled (saved)")
    }
}
