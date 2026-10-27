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
)

/** 설정 — 지금은 연결 해제(반복 시연용)와 진단 화면 진입만. 음성 안내 on/off 는 T27. */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: PairingRepository,
    tokenStore: TokenStore,
) : ViewModel() {

    private val justDisconnected = MutableStateFlow(false)

    init {
        viewModelScope.launch { tokenStore.load() }
    }

    val uiState: StateFlow<SettingsUiState> = combine(tokenStore.state, justDisconnected) { tokens, disconnected ->
        SettingsUiState(tokens = tokens, justDisconnected = disconnected)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState(tokens = tokenStore.state.value))

    /** TokenStore 를 비운다 → 홈이 '연결 안 됨' 으로 돌아간다. */
    fun disconnect() {
        viewModelScope.launch {
            repository.disconnect()
            justDisconnected.value = true
        }
    }
}
