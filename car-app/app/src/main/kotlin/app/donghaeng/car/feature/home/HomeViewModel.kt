package app.donghaeng.car.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.donghaeng.car.BuildConfig
import app.donghaeng.car.adapter.sdk.ProbeLine
import app.donghaeng.car.adapter.sdk.SdkProbe
import app.donghaeng.car.core.common.TextMasking
import app.donghaeng.car.core.common.TokenStore
import app.donghaeng.car.core.common.TokenStoreState
import app.donghaeng.car.core.network.ServerConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 차량 연결 상태 — [TokenStore] 에 자격 증명이 있으면 CONNECTED. CHECKING = 저장소를 아직 읽는 중. */
enum class VehicleConnection { CHECKING, DISCONNECTED, CONNECTED }

/** 사용자에게 보이는 문구(앱 이름·연결 상태 등)는 여기 두지 않고 res/values/strings.xml 에서 가져온다. */
data class HomeUiState(
    val flavor: String = BuildConfig.FLAVOR,
    val versionName: String = BuildConfig.VERSION_NAME,
    val publicBaseUrl: String = ServerConfig.publicBaseUrl,
    val mqttPublicUri: String = ServerConfig.mqttPublicUri,
    val connection: VehicleConnection = VehicleConnection.CHECKING,
    /** 연결됨일 때 서버가 준 차량 별명(화면 표시용 — 로그에는 쓰지 않는다) */
    val nickname: String? = null,
    /** 연결됨일 때 서버 vehicleId 마스킹(앞 4 + 뒤 2) */
    val vehicleIdMasked: String? = null,
    /** T12 페어링 화면 — 항상 열 수 있다(연결 상태에서는 '다시 연결' 안내) */
    val canPairPhone: Boolean = true,
    /** T17 그룹 생성 구현 후 true (페어링 완료 조건 포함) */
    val canStartGroup: Boolean = false,
    /** SDK 점검 결과. null = 아직 실행 전. pleos flavor 에서만 카드에 표시 */
    val sdkProbe: List<ProbeLine>? = null,
) {
    val showSdkProbe: Boolean get() = flavor == "pleos"
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    sdkProbe: SdkProbe,
    private val tokenStore: TokenStore,
) : ViewModel() {

    init {
        viewModelScope.launch { tokenStore.load() }
    }

    val uiState: StateFlow<HomeUiState> = combine(sdkProbe.report, tokenStore.state) { lines, tokens -> build(lines, tokens) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), build(sdkProbe.report.value, tokenStore.state.value))

    private fun build(lines: List<ProbeLine>?, tokens: TokenStoreState): HomeUiState = when (tokens) {
        TokenStoreState.Loading -> HomeUiState(connection = VehicleConnection.CHECKING, sdkProbe = lines)
        TokenStoreState.Empty -> HomeUiState(connection = VehicleConnection.DISCONNECTED, sdkProbe = lines)
        is TokenStoreState.Present -> HomeUiState(
            connection = VehicleConnection.CONNECTED,
            nickname = tokens.credentials.nickname,
            vehicleIdMasked = TextMasking.maskVehicleId(tokens.credentials.vehicleId),
            sdkProbe = lines,
        )
    }
}
