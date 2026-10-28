package app.donghaeng.car.feature.settings

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.donghaeng.car.BuildConfig
import app.donghaeng.car.adapter.navi.NaviAdapter
import app.donghaeng.car.adapter.sdk.ProbeLine
import app.donghaeng.car.adapter.sdk.SdkProbe
import app.donghaeng.car.core.common.DhLog
import app.donghaeng.car.core.common.DrivingStateMonitor
import app.donghaeng.car.core.common.DrivingStatus
import app.donghaeng.car.core.common.TextMasking
import app.donghaeng.car.core.common.TokenStore
import app.donghaeng.car.core.common.TokenStoreState
import app.donghaeng.car.core.network.MqttAddressDisplay
import app.donghaeng.car.core.network.ServerAddresses
import app.donghaeng.car.core.network.ServerConfig
import app.donghaeng.car.core.network.mqtt.MqttGateway
import app.donghaeng.car.core.network.mqtt.MqttStatus
import app.donghaeng.car.domain.session.GroupSession
import app.donghaeng.protocol.GroupState
import app.donghaeng.car.domain.session.LastCommand
import app.donghaeng.car.domain.session.MqttSessionManager
import app.donghaeng.car.domain.capability.CapabilityReport
import app.donghaeng.car.domain.capability.CapabilityReporter
import app.donghaeng.car.adapter.voice.VoiceAdapter
import app.donghaeng.car.adapter.voice.VoiceAdapterStatus
import app.donghaeng.car.domain.event.EventEngineState
import app.donghaeng.car.domain.voice.VoiceAnnouncer
import app.donghaeng.car.domain.routesync.RouteSyncEngine
import app.donghaeng.car.domain.routesync.RouteSyncState
import app.donghaeng.car.domain.voice.VoiceAnnouncerState
import app.donghaeng.car.domain.event.VehicleEventEngine
import app.donghaeng.car.domain.location.LocationFeed
import app.donghaeng.car.domain.location.LocationFeedState
import app.donghaeng.car.domain.telemetry.TelemetryCollector
import app.donghaeng.car.domain.telemetry.TelemetryState
import app.donghaeng.car.domain.vehicle.VehicleSignalState
import app.donghaeng.car.service.DriveSessionController
import app.donghaeng.car.domain.vehicle.VehicleSignals
import app.donghaeng.car.domain.model.Place
import app.donghaeng.car.domain.navi.NaviEventMonitor
import app.donghaeng.car.domain.navi.NaviMonitorState
import app.donghaeng.car.core.common.ClearRecord
import app.donghaeng.car.domain.pairing.TokenRefreshState
import app.donghaeng.car.domain.pairing.TokenRefresher
import app.donghaeng.car.domain.session.GroupSessionStore
import app.donghaeng.car.domain.session.StoreRetryState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
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
    /** T32 지금 서버 주소(숨은 메뉴 사용자 지정 ?: 빌드 기본값) */
    val publicBaseUrl: String = ServerConfig.BUILD_DEFAULT_BASE_URL,
    val baseUrlOverridden: Boolean = false,
    /** T32 지금 자격 증명의 mqtt.uri(페어링 전 null) — [mqttAddress] 계산용 */
    val pairedMqttUri: String? = null,
    /** T32 실제 MQTT 주소(사용자 지정 ?: 페어링 응답 mqtt.uri)와 출처 */
    val mqttAddress: MqttAddressDisplay = MqttAddressDisplay.UNPAIRED,
    val debug: Boolean = BuildConfig.DEBUG,
    val androidApi: Int = Build.VERSION.SDK_INT,
    val deviceModel: String = Build.MODEL ?: "?",
    /** T16 MQTT 게이트웨이 상태(토큰·좌표 없음) */
    val mqtt: MqttStatus = MqttStatus(),
    /** 활성 그룹(cmd GROUP_JOINED 로 받은 것). null = 없음 */
    val groupSession: GroupSession? = null,
    val lastCommand: LastCommand? = null,
    /** T24 마지막 그룹 STATE 스냅샷과 수신 시각 */
    val groupState: GroupState? = null,
    val groupStateAtMs: Long? = null,
    /** T20 내비 콜백 요약(좌표 마스킹) */
    val navi: NaviMonitorState = NaviMonitorState(),
    /** 진단 화면에서 보낸 마지막 내비 요청과 결과 */
    val naviActionResult: String? = null,
    /** T21 위치 체인·속도·권한 */
    val location: LocationFeedState = LocationFeedState(),
    /** T22 차량 신호 최신값 */
    val vehicle: VehicleSignalState = VehicleSignalState(),
    /** T23 텔레메트리 발행 상태 */
    val telemetry: TelemetryState = TelemetryState(),
    val driveServiceRunning: Boolean = false,
    /** T26 차량 이벤트 판정·발행 */
    val events: EventEngineState = EventEngineState(),
    /** T27 음성 안내 */
    val voice: VoiceAnnouncerState = VoiceAnnouncerState(),
    val tts: VoiceAdapterStatus = VoiceAdapterStatus(),
    /** T28 경로 동기화 */
    val routeSync: RouteSyncState = RouteSyncState(),
    /** T29 주행 제한 */
    val driving: DrivingStatus = DrivingStatus(),
    val isDriving: Boolean = false,
    /** T31 지연 측정·시계 동기화 */
    val latency: app.donghaeng.car.domain.latency.LatencyState = app.donghaeng.car.domain.latency.LatencyState(),
    val clockSync: app.donghaeng.car.domain.time.ClockSyncState = app.donghaeng.car.domain.time.ClockSyncState(),
)

/** T33 진단 — 토큰·저장소·갱신·삭제 기록·최근 로그(토큰 원문 없음) */
data class T33DiagState(
    val tokens: TokenStoreState = TokenStoreState.Loading,
    val refresh: TokenRefreshState = TokenRefreshState(),
    val lastTokenClear: ClearRecord? = null,
    val lastSessionClear: ClearRecord? = null,
    val storeRetry: StoreRetryState = StoreRetryState(),
    /** DhLog 링 버퍼 마지막 30줄(이미 마스킹됨) */
    val recentLogs: List<String> = emptyList(),
    val nowMs: Long = 0L,
)

/** 진단 화면의 경로 동기화 확인용 동작(T28) */
enum class RouteSyncDebugAction { PUBLISH_TEST, ACCEPT, DECLINE, TOGGLE_AUTO }

/** 진단 화면의 음성 확인용 동작(T27) */
enum class VoiceDebugAction { SPEAK_TEST, TOGGLE }

/** 진단 화면의 내비 확인용 요청(T20) — 실제 기능 UI 가 아니라 콜백이 오는지 보는 버튼 */
enum class NaviDebugAction { REQUEST_ROUTE, SHOW_NAVIGATION, QUERY_DESTINATION }

/** 진단 화면의 위치 확인용 동작(T21) — 권한 요청은 Route 의 런처가, 재탐색은 ViewModel 이 처리 */
enum class LocationDebugAction { REQUEST_PERMISSION, RESTART }

private data class SessionSide(val session: GroupSession?, val lastCommand: LastCommand?, val groupState: GroupState?, val groupStateAt: Long?, val latency: app.donghaeng.car.domain.latency.LatencyState = app.donghaeng.car.domain.latency.LatencyState(), val clock: app.donghaeng.car.domain.time.ClockSyncState = app.donghaeng.car.domain.time.ClockSyncState())

private data class SideState(val mqtt: MqttStatus, val navi: NaviMonitorState, val naviAction: String?, val location: LocationFeedState, val vehicle: VehicleSignalState, val telemetry: TelemetryState, val serviceRunning: Boolean, val events: EventEngineState = EventEngineState(), val voice: VoiceAnnouncerState = VoiceAnnouncerState(), val tts: VoiceAdapterStatus = VoiceAdapterStatus(), val routeSync: RouteSyncState = RouteSyncState(), val driving: DrivingStatus = DrivingStatus(), val isDriving: Boolean = false)

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
    telemetryCollector: TelemetryCollector,
    driveSessionController: DriveSessionController,
    eventEngine: VehicleEventEngine,
    private val voiceAnnouncer: VoiceAnnouncer,
    voiceAdapter: VoiceAdapter,
    private val routeSync: RouteSyncEngine,
    drivingMonitor: DrivingStateMonitor,
    private val clockSync: app.donghaeng.car.domain.time.ClockSync,
    latency: app.donghaeng.car.domain.latency.LatencyMeter,
    tokenRefresher: TokenRefresher,
    sessionStore: GroupSessionStore,
    serverConfig: ServerConfig,
) : ViewModel() {

    private val naviActionResult = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch { tokenStore.load() }
    }

    /** 최근 로그·만료까지 남은 시간을 2초마다 다시 읽는다 */
    private val logTicker = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(2_000L)
        }
    }

    /** T33 토큰·저장소·로그 카드 */
    val t33State: StateFlow<T33DiagState> = combine(
        combine(tokenStore.state, tokenRefresher.state, tokenStore.lastClear) { t, r, c -> Triple(t, r, c) },
        sessionStore.lastClear,
        sessionManager.storeRetry,
        logTicker,
    ) { head, sessionClear, retry, now ->
        T33DiagState(head.first, head.second, head.third, sessionClear, retry, DhLog.recent(30), now)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), T33DiagState(tokens = tokenStore.state.value))

    val uiState: StateFlow<DiagnosticsUiState> = combine(reporter.report, sdkProbe.report, tokenStore.state, combine(combine(mqtt.status, naviMonitor.state, naviActionResult, voiceAnnouncer.state, voiceAdapter.status) { m, n, a, vo, tt -> SideState(m, n, a, LocationFeedState(), VehicleSignalState(), TelemetryState(), false, EventEngineState(), vo, tt) }, combine(locationFeed.state, vehicleSignals.state, telemetryCollector.state, driveSessionController.serviceRunning, eventEngine.state) { l, v, t, r, ev -> SideState(MqttStatus(), NaviMonitorState(), null, l, v, t, r, ev) }, routeSync.state, drivingMonitor.status, drivingMonitor.isDriving().onStart { emit(false) }) { head, side, rs, ds, isDriving -> side.copy(mqtt = head.mqtt, navi = head.navi, naviAction = head.naviAction, voice = head.voice, tts = head.tts, routeSync = rs, driving = ds, isDriving = isDriving) }, combine(sessionManager.session, sessionManager.lastCommand, sessionManager.groupState, sessionManager.groupStateAt, combine(latency.state, clockSync.state) { l, cs -> l to cs }) { s, c, g, t, lc -> SessionSide(s, c, g, t, lc.first, lc.second) }) { report, probe, tokens, side, sess ->
        DiagnosticsUiState(
            report = report,
            probe = probe,
            pairedVehicleIdMasked = (tokens as? TokenStoreState.Present)?.let { TextMasking.maskVehicleId(it.credentials.vehicleId) },
            pairedMqttUri = (tokens as? TokenStoreState.Present)?.credentials?.mqttUri,
            mqtt = side.mqtt,
            groupSession = sess.session,
            lastCommand = sess.lastCommand,
            groupState = sess.groupState,
            groupStateAtMs = sess.groupStateAt,
            navi = side.navi,
            naviActionResult = side.naviAction,
            location = side.location,
            vehicle = side.vehicle,
            telemetry = side.telemetry,
            driveServiceRunning = side.serviceRunning,
            events = side.events,
            voice = side.voice,
            tts = side.tts,
            routeSync = side.routeSync,
            driving = side.driving,
            isDriving = side.isDriving,
            latency = sess.latency,
            clockSync = sess.clock,
        )
    }.combine(serverConfig.current) { ui, server -> ui.withServer(server) }
        .stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5_000),
            DiagnosticsUiState(
                report = reporter.report.value, probe = sdkProbe.report.value,
                pairedMqttUri = (tokenStore.state.value as? TokenStoreState.Present)?.credentials?.mqttUri,
            ).withServer(serverConfig.current.value),
        )

    private fun DiagnosticsUiState.withServer(server: ServerAddresses) = copy(
        publicBaseUrl = server.publicBaseUrl, baseUrlOverridden = server.baseUrlOverridden,
        mqttAddress = MqttAddressDisplay.of(server, pairedMqttUri),
    )

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

    /** T31 진단 '지금 동기화' */
    fun resyncClock() = clockSync.resync()

    fun onRouteSyncAction(action: RouteSyncDebugAction) {
        when (action) {
            RouteSyncDebugAction.PUBLISH_TEST -> routeSync.publishTestRouteChange(RouteSyncEngine.DEMO_WAYPOINT)
            RouteSyncDebugAction.ACCEPT -> viewModelScope.launch { routeSync.accept() }
            RouteSyncDebugAction.DECLINE -> routeSync.decline()
            RouteSyncDebugAction.TOGGLE_AUTO -> routeSync.setAutoFollow(!routeSync.state.value.autoFollow)
        }
    }

    fun onVoiceAction(action: VoiceDebugAction) {
        when (action) {
            VoiceDebugAction.SPEAK_TEST -> voiceAnnouncer.speakTest()
            VoiceDebugAction.TOGGLE -> voiceAnnouncer.setEnabled(!voiceAnnouncer.state.value.enabled)
        }
    }

    private companion object {
        /** 확인용 목적지 — 공공장소, 시연 전용 */
        val DEBUG_DESTINATION = Place(36.3504, 127.3845, "대전역", address = "대전광역시 동구 중앙로 215")
    }
}
