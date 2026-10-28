package app.donghaeng.car.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.donghaeng.car.core.common.ClearRecord
import app.donghaeng.car.core.common.DhLog
import app.donghaeng.car.core.common.DrivingStateMonitor
import app.donghaeng.car.core.common.TokenClearReason
import app.donghaeng.car.core.common.TokenStore
import app.donghaeng.car.core.common.TokenStoreState
import app.donghaeng.car.domain.pairing.PairingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
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
    /** T31 그룹 주행 화면 지연(ms) 오버레이 */
    val latencyOverlay: Boolean = true,
    /** T33 주행 중 — 연결 해제·저장소 초기화 비활성 */
    val isDriving: Boolean = false,
    /** T33 마지막 토큰 삭제 기록 */
    val lastClear: ClearRecord? = null,
)

private data class ToggleSide(val voice: Boolean, val autoFollow: Boolean, val latency: Boolean)

/** 설정 — 연결 해제(반복 시연용, 확인 창 뒤), 저장소 초기화(읽기 실패 시), 진단 화면 진입, 음성 안내 on/off(T27). */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: PairingRepository,
    tokenStore: TokenStore,
    private val voiceAnnouncer: app.donghaeng.car.domain.voice.VoiceAnnouncer,
    private val routeSync: app.donghaeng.car.domain.routesync.RouteSyncEngine,
    private val latency: app.donghaeng.car.domain.latency.LatencyMeter,
    driving: DrivingStateMonitor,
) : ViewModel() {

    private val justDisconnected = MutableStateFlow(false)
    private var drivingNow = false

    init {
        viewModelScope.launch { tokenStore.load() }
    }

    private val toggles = combine(voiceAnnouncer.state, routeSync.state, latency.state) { voice, rs, lat -> ToggleSide(voice.enabled, rs.autoFollow, lat.overlayEnabled) }

    val uiState: StateFlow<SettingsUiState> = combine(tokenStore.state, justDisconnected, toggles, driving.isDriving().onStart { emit(false) }, tokenStore.lastClear) { tokens, disconnected, t, isDriving, lastClear ->
        drivingNow = isDriving
        SettingsUiState(tokens = tokens, justDisconnected = disconnected, voiceEnabled = t.voice, autoFollow = t.autoFollow, latencyOverlay = t.latency, isDriving = isDriving, lastClear = lastClear)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState(tokens = tokenStore.state.value, lastClear = tokenStore.lastClear.value))

    fun setVoiceEnabled(enabled: Boolean) = voiceAnnouncer.setEnabled(enabled)

    fun setAutoFollow(enabled: Boolean) = routeSync.setAutoFollow(enabled)

    fun setLatencyOverlay(enabled: Boolean) = latency.setOverlayEnabled(enabled)

    /** 확인 창에서 '연결 해제' — TokenStore 를 비운다 → 홈이 '연결 안 됨' 으로 돌아간다. 주행 중이면 무시 */
    fun disconnect() = clear(TokenClearReason.USER_SETTINGS_DISCONNECT)

    /** 확인 창에서 '저장소 초기화' — 읽을 수 없는 `dh_tokens` 를 파일째 지운다(T33) */
    fun resetStore() = clear(TokenClearReason.STORE_RESET)

    private fun clear(reason: TokenClearReason) {
        if (drivingNow) {
            DhLog.w("settings $reason ignored while driving")
            return
        }
        viewModelScope.launch {
            repository.disconnect(reason)
            justDisconnected.value = true
        }
    }
}
