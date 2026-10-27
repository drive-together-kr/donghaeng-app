package app.donghaeng.car.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.donghaeng.car.BuildConfig
import app.donghaeng.car.adapter.sdk.ProbeLine
import app.donghaeng.car.adapter.sdk.SdkProbe
import app.donghaeng.car.core.network.ServerConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** 차량 연결 상태 — 페어링(T12) 전까지는 항상 DISCONNECTED */
enum class VehicleConnection { DISCONNECTED, PAIRING, CONNECTED }

data class HomeUiState(
    val appName: String = "동행",
    val flavor: String = BuildConfig.FLAVOR,
    val versionName: String = BuildConfig.VERSION_NAME,
    val publicBaseUrl: String = ServerConfig.publicBaseUrl,
    val mqttPublicUri: String = ServerConfig.mqttPublicUri,
    val connection: VehicleConnection = VehicleConnection.DISCONNECTED,
    /** T12 페어링 구현 후 true */
    val canPairPhone: Boolean = false,
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
) : ViewModel() {

    val uiState: StateFlow<HomeUiState> = sdkProbe.report
        .map { lines -> HomeUiState(sdkProbe = lines) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState(sdkProbe = sdkProbe.report.value))
}
