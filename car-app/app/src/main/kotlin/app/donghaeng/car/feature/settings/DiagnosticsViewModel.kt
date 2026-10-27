package app.donghaeng.car.feature.settings

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.donghaeng.car.BuildConfig
import app.donghaeng.car.adapter.navi.NaviAdapter
import app.donghaeng.car.adapter.sdk.ProbeLine
import app.donghaeng.car.adapter.sdk.SdkProbe
import app.donghaeng.car.core.common.DhLog
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
import app.donghaeng.car.domain.location.LocationFeed
import app.donghaeng.car.domain.location.LocationFeedState
import app.donghaeng.car.domain.vehicle.VehicleSignalState
import app.donghaeng.car.domain.vehicle.VehicleSignals
import app.donghaeng.car.domain.model.Place
import app.donghaeng.car.domain.navi.NaviEventMonitor
import app.donghaeng.car.domain.navi.NaviMonitorState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
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
    /** T20 내비 콜백 요약(좌표 마스킹) */
    val navi: NaviMonitorState = NaviMonitorState(),
    /** 진단 화면에서 보낸 마지막 내비 요청과 결과 */
    val naviActionResult: String? = null,
    /** T21 위치 체인·속도·권한 */
    val location: LocationFeedState = LocationFeedState(),
    /** T22 차량 신호 최신값 */
    val vehicle: VehicleSignalState = VehicleSignalState(),
)

/** 진단 화면의 내비 확인용 요청(T20) — 실제 기능 UI 가 아니라 콜백이 오는지 보는 버튼 */
enum class NaviDebugAction { REQUEST_ROUTE, SHOW_NAVIGATION, QUERY_DESTINATION }

/** 진단 화면의 위치 확인용 동작(T21) — 권한 요청은 Route 의 런처가, 재탐색은 ViewModel 이 처리 */
enum class LocationDebugAction { REQUEST_PERMISSION, RESTART }

private data class SideState(val mqtt: MqttStatus, val navi: NaviMonitorState, val naviAction: String?, val location: LocationFeedState, val vehicle: VehicleSignalState)

/** 진단 화면 — capability 표(T11) + SDK 점검 + 빌드 정보. 확인용 화면이라 사용자 입력이 없다. */
@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    reporter: CapabilityReporter,
    sdkProbe: SdkProbe,
    tokenStore: TokenStore,
    mqtt: MqttGateway,
    sessionManager: MqttSessionManager,
    naviMonitor: NaviEventMonitor,
    private val navi: NaviAdapter,
    private val locationFeed: LocationFeed,
    private val vehicleSignals: VehicleSignals,
) : ViewModel() {

    private val naviActionResult = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch { tokenStore.load() }
    }

    val uiState: StateFlow<DiagnosticsUiState> = combine(reporter.report, sdkProbe.report, tokenStore.state, combine(mqtt.status, naviMonitor.state, naviActionResult, locationFeed.state, vehicleSignals.state) { m, n, a, l, v -> SideState(m, n, a, l, v) }, combine(sessionManager.session, sessionManager.lastCommand) { s, c -> s to c }) { report, probe, tokens, (mqttStatus, naviState, actionResult, locationState, vehicleState), (session, lastCmd) ->
        DiagnosticsUiState(
            report = report,
            probe = probe,
            pairedVehicleIdMasked = (tokens as? TokenStoreState.Present)?.let { TextMasking.maskVehicleId(it.credentials.vehicleId) },
            mqtt = mqttStatus,
            groupSession = session,
            lastCommand = lastCmd,
            navi = naviState,
            naviActionResult = actionResult,
            location = locationState,
            vehicle = vehicleState,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiagnosticsUiState(report = reporter.report.value, probe = sdkProbe.report.value))

    /** 내비 요청 → 결과는 [DiagnosticsUiState.naviActionResult] 와 내비 카드의 콜백 수로 확인한다 */
    fun onNaviAction(action: NaviDebugAction) {
        viewModelScope.launch {
            val name = when (action) {
                NaviDebugAction.REQUEST_ROUTE -> "requestRoute(${DEBUG_DESTINATION.name})"
                NaviDebugAction.SHOW_NAVIGATION -> "showNavigation"
                NaviDebugAction.QUERY_DESTINATION -> "currentDestination"
            }
            naviActionResult.value = "$name 요청 중…"
            val result = runCatching {
                when (action) {
                    NaviDebugAction.REQUEST_ROUTE -> { navi.requestRoute(DEBUG_DESTINATION); "요청 보냄 (콜백 수 변화 확인)" }
                    NaviDebugAction.SHOW_NAVIGATION -> { navi.showNavigation(); "요청 보냄" }
                    NaviDebugAction.QUERY_DESTINATION -> navi.currentDestination()?.let { "→ ${it.name}" } ?: "→ null (안내 중 아님 또는 2초 내 응답 없음)"
                }
            }.getOrElse { "실패 ${it.javaClass.simpleName}: ${it.message}" }
            naviActionResult.value = "$name $result"
        }
    }

    fun onLocationAction(action: LocationDebugAction) {
        when (action) {
            LocationDebugAction.RESTART -> locationFeed.restart()
            LocationDebugAction.REQUEST_PERMISSION -> locationFeed.refreshPermissions()   // 실제 요청은 Route 의 런처
        }
    }

    /** 시스템 권한 다이얼로그 결과 → 체인 재탐색 + 차량 신호 재구독(CAR_SPEED) */
    fun onPermissionResult(result: Map<String, Boolean>) {
        DhLog.i("diag permissions: ${result.entries.joinToString { "${it.key.substringAfterLast('.')}=${it.value}" }}")
        locationFeed.restart()
        vehicleSignals.restart()
    }

    fun restartVehicleSignals() = vehicleSignals.restart()

    private companion object {
        /** 확인용 목적지 — 공공장소, 시연 전용 */
        val DEBUG_DESTINATION = Place(36.3504, 127.3845, "대전역", address = "대전광역시 동구 중앙로 215")
    }
}
