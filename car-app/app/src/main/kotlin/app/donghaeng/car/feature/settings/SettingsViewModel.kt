package app.donghaeng.car.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.donghaeng.car.BuildConfig
import app.donghaeng.car.core.common.ClearRecord
import app.donghaeng.car.core.common.DhLog
import app.donghaeng.car.core.common.DrivingStateMonitor
import app.donghaeng.car.core.common.TokenClearReason
import app.donghaeng.car.core.common.TokenStore
import app.donghaeng.car.core.common.TokenStoreState
import app.donghaeng.car.core.network.ServerAddressRules
import app.donghaeng.car.core.network.ServerAddresses
import app.donghaeng.car.core.network.ServerConfig
import app.donghaeng.car.core.network.ServerOverrides
import app.donghaeng.car.domain.pairing.PairingRepository
import app.donghaeng.car.domain.session.ServerSwitcher
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
    /** T32 지금 서버·브로커 주소(사용자 지정 ?: 빌드 기본값) — 숨은 메뉴 '서버 주소' 다이얼로그 */
    val server: ServerAddresses = ServerConfig.BUILD_DEFAULTS,
    /** T32 숨은 메뉴로 방금 주소를 바꿨다(안내 문구). null = 없음 */
    val serverNotice: ServerNotice? = null,
    /** F16 '핸들 버튼으로 무전'(기본 켬) — 정차 중에만 바꾼다 */
    val pttHandleButton: Boolean = true,
)

/** T32 숨은 메뉴 저장 뒤 안내 */
enum class ServerNotice {
    /** 서버 주소가 바뀌어 연결 정보를 지웠다 — 폰에서 다시 연결 */
    BASE_CHANGED,

    /** MQTT 주소만 바뀌어 다시 연결했다 */
    MQTT_RECONNECTED,
}

private data class ToggleSide(val voice: Boolean, val autoFollow: Boolean, val latency: Boolean, val server: ServerAddresses, val pttHandle: Boolean)

private data class LocalFlags(val justDisconnected: Boolean = false, val serverNotice: ServerNotice? = null)

/**
 * 설정 — 연결 해제(반복 시연용, 확인 창 뒤), 저장소 초기화(읽기 실패 시), 진단 화면 진입, 음성 안내 on/off(T27).
 * T32: 숨은 메뉴 '서버 주소'(제목 3초 안에 5번 탭, 주행 중 비활성) — [checkServerForm] → 확인 창 → [applyServer]/[resetServer].
 * F16: '핸들 버튼으로 무전' 스위치([setPttHandleButton], 정차 중에만) — 끄면 핸들·미디어 키가 원래 동작(음악 재생/정지)으로 돌아간다.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: PairingRepository,
    tokenStore: TokenStore,
    private val voiceAnnouncer: app.donghaeng.car.domain.voice.VoiceAnnouncer,
    private val routeSync: app.donghaeng.car.domain.routesync.RouteSyncEngine,
    private val latency: app.donghaeng.car.domain.latency.LatencyMeter,
    driving: DrivingStateMonitor,
    private val serverConfig: ServerConfig,
    private val serverSwitcher: ServerSwitcher,
    private val pttSettings: app.donghaeng.car.domain.ptt.PttSettings,
    /** A120: 음성 안내·자동 따라가기·지연 표시를 `dh_settings` 에 저장(앱을 다시 켜도 유지) */
    private val userSettings: app.donghaeng.car.domain.settings.UserSettings,
) : ViewModel() {

    private val flags = MutableStateFlow(LocalFlags())
    private var drivingNow = false

    init {
        viewModelScope.launch { tokenStore.load() }
    }

    private val toggles = combine(voiceAnnouncer.state, routeSync.state, latency.state, serverConfig.current, pttSettings.handleButton) { voice, rs, lat, server, ptt ->
        ToggleSide(voice.enabled, rs.autoFollow, lat.overlayEnabled, server, ptt)
    }

    val uiState: StateFlow<SettingsUiState> = combine(tokenStore.state, flags, toggles, driving.isDriving().onStart { emit(false) }, tokenStore.lastClear) { tokens, f, t, isDriving, lastClear ->
        drivingNow = isDriving
        SettingsUiState(
            tokens = tokens, justDisconnected = f.justDisconnected, voiceEnabled = t.voice, autoFollow = t.autoFollow, latencyOverlay = t.latency,
            isDriving = isDriving, lastClear = lastClear, server = t.server, serverNotice = f.serverNotice, pttHandleButton = t.pttHandle,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState(tokens = tokenStore.state.value, lastClear = tokenStore.lastClear.value, server = serverConfig.current.value, pttHandleButton = pttSettings.handleButton.value))

    fun setVoiceEnabled(enabled: Boolean) {
        userSettings.setVoiceEnabled(enabled)
        voiceAnnouncer.setEnabled(enabled)
    }

    fun setAutoFollow(enabled: Boolean) {
        userSettings.setAutoFollow(enabled)
        routeSync.setAutoFollow(enabled)
    }

    fun setLatencyOverlay(enabled: Boolean) {
        userSettings.setLatencyOverlay(enabled)
        latency.setOverlayEnabled(enabled)
    }

    /** F16 '핸들 버튼으로 무전' — 주행 중이면 무시(화면도 스위치를 막는다) */
    fun setPttHandleButton(enabled: Boolean) {
        if (drivingNow) {
            DhLog.w("settings ptt handle button change ignored while driving")
            return
        }
        pttSettings.setHandleButton(enabled)
    }

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
            flags.value = flags.value.copy(justDisconnected = true)
        }
    }

    /** T32 숨은 메뉴 [저장] 전 검증 — 화면이 결과로 오류 표시 또는 확인 창을 띄운다 */
    fun checkServerForm(baseUrl: String, mqttUri: String): ServerAddressRules.FormResult =
        ServerAddressRules.checkForm(baseUrl, mqttUri, BuildConfig.DH_CLEARTEXT_HOSTS, serverConfig.current.value)

    /** T32 확인 창에서 '바꾸기'. 주행 중이면 무시 */
    fun applyServer(overrides: ServerOverrides) = switchServer { serverSwitcher.apply(overrides) }

    /** T32 '기본값 복원'(확인 창 뒤). 주행 중이면 무시 */
    fun resetServer() = switchServer { serverSwitcher.reset() }

    private fun switchServer(block: suspend () -> app.donghaeng.car.core.network.ServerChange) {
        if (drivingNow) {
            DhLog.w("settings server change ignored while driving")
            return
        }
        viewModelScope.launch {
            val change = block()
            val notice = when {
                change.baseUrlChanged -> ServerNotice.BASE_CHANGED
                change.mqttUriChanged -> ServerNotice.MQTT_RECONNECTED
                else -> null
            }
            flags.value = flags.value.copy(serverNotice = notice, justDisconnected = false)
        }
    }
}
