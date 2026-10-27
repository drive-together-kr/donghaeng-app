package app.donghaeng.car.feature.settings

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.donghaeng.car.BuildConfig
import app.donghaeng.car.adapter.sdk.ProbeLine
import app.donghaeng.car.adapter.sdk.SdkProbe
import app.donghaeng.car.core.common.TextMasking
import app.donghaeng.car.core.common.TokenStore
import app.donghaeng.car.core.common.TokenStoreState
import app.donghaeng.car.core.network.ServerConfig
import app.donghaeng.car.core.network.mqtt.MqttGateway
import app.donghaeng.car.core.network.mqtt.MqttStatus
import app.donghaeng.car.domain.session.GroupSession
import app.donghaeng.car.domain.session.LastCommand
import app.donghaeng.car.domain.session.MqttSessionManager
import app.donghaeng.car.domain.capability.CapabilityReport
import app.donghaeng.car.domain.capability.CapabilityReporter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DiagnosticsUiState(
    /** null = 아직 점검 중 */
    val report: CapabilityReport? = null,
    val probe: List<ProbeLine>? = null,
    /** 페어링된 서버 vehicleId 마스킹. null = 없음 */
    val pairedVehicleIdMasked: String? = null,
    val flavor: String = BuildConfig.FLAVOR,
    val adapter: String = BuildConfig.ADAPTER,
    val versionName: String = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
    val publicBaseUrl: String = ServerConfig.publicBaseUrl,
    val mqttPublicUri: String = ServerConfig.mqttPublicUri,
    val debug: Boolean = BuildConfig.DEBUG,
    val androidApi: Int = Build.VERSION.SDK_INT,
    val deviceModel: String = Build.MODEL ?: "?",
    /** T16 MQTT 게이트웨이 상태(토큰·좌표 없음) */
    val mqtt: MqttStatus = MqttStatus(),
    /** 활성 그룹(cmd GROUP_JOINED 로 받은 것). null = 없음 */
    val groupSession: GroupSession? = null,
    val lastCommand: LastCommand? = null,
)

/** 진단 화면 — capability 표(T11) + SDK 점검 + 빌드 정보. 확인용 화면이라 사용자 입력이 없다. */
@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    reporter: CapabilityReporter,
    sdkProbe: SdkProbe,
    tokenStore: TokenStore,
    mqtt: MqttGateway,
    sessionManager: MqttSessionManager,
) : ViewModel() {

    init {
        viewModelScope.launch { tokenStore.load() }
    }

    val uiState: StateFlow<DiagnosticsUiState> = combine(reporter.report, sdkProbe.report, tokenStore.state, mqtt.status, combine(sessionManager.session, sessionManager.lastCommand) { s, c -> s to c }) { report, probe, tokens, mqttStatus, (session, lastCmd) ->
        DiagnosticsUiState(
            report = report,
            probe = probe,
            pairedVehicleIdMasked = (tokens as? TokenStoreState.Present)?.let { TextMasking.maskVehicleId(it.credentials.vehicleId) },
            mqtt = mqttStatus,
            groupSession = session,
            lastCommand = lastCmd,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiagnosticsUiState(report = reporter.report.value, probe = sdkProbe.report.value))
}
