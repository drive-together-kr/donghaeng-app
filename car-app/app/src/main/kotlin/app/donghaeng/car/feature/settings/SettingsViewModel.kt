package app.donghaeng.car.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.donghaeng.car.core.common.TokenStore
import app.donghaeng.car.core.common.TokenStoreState
import app.donghaeng.car.domain.pairing.PairingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val tokens: TokenStoreState = TokenStoreState.Loading,
    /** 이번 화면에서 연결 해제를 눌렀는지(안내 문구 표시) */
    val justDisconnected: Boolean = false,
    /** T27 음성 안내 on/off */
    val voiceEnabled: Boolean = true,
    /** T28 선두 경로 자동 따라가기 */
    val autoFollow: Boolean = false,
)

/** 설정 — 연결 해제(반복 시연용), 진단 화면 진입, 음성 안내 on/off(T27). */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: PairingRepository,
    tokenStore: TokenStore,
    private val voiceAnnouncer: app.donghaeng.car.domain.voice.VoiceAnnouncer,
    private val routeSync: app.donghaeng.car.domain.routesync.RouteSyncEngine,
) : ViewModel() {

    private val justDisconnected = MutableStateFlow(false)

    init {
        viewModelScope.launch { tokenStore.load() }
    }

    val uiState: StateFlow<SettingsUiState> = combine(tokenStore.state, justDisconnected, voiceAnnouncer.state, routeSync.state) { tokens, disconnected, voice, rs ->
        SettingsUiState(tokens = tokens, justDisconnected = disconnected, voiceEnabled = voice.enabled, autoFollow = rs.autoFollow)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState(tokens = tokenStore.state.value))

    fun setVoiceEnabled(enabled: Boolean) = voiceAnnouncer.setEnabled(enabled)

    fun setAutoFollow(enabled: Boolean) = routeSync.setAutoFollow(enabled)

    /** TokenStore 를 비운다 → 홈이 '연결 안 됨' 으로 돌아간다. */
    fun disconnect() {
        viewModelScope.launch {
            repository.disconnect()
            justDisconnected.value = true
        }
    }
}
