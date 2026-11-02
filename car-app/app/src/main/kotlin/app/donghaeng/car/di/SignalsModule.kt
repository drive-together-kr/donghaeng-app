package app.donghaeng.car.di

import android.content.Context
import app.donghaeng.car.adapter.vehicle.VehicleAdapter
import app.donghaeng.car.core.common.TokenStore
import app.donghaeng.car.core.common.TokenStoreState
import app.donghaeng.car.core.network.mqtt.MqttGateway
import app.donghaeng.car.domain.event.VehicleEventEngine
import app.donghaeng.car.domain.location.LocationFeed
import app.donghaeng.car.domain.location.MyFixSource
import app.donghaeng.car.domain.navi.NaviEventMonitor
import app.donghaeng.car.domain.session.MqttSessionManager
import app.donghaeng.car.domain.telemetry.TelemetryCollector
import app.donghaeng.car.domain.vehicle.VehicleSignals
import app.donghaeng.car.domain.voice.VoiceAnnouncer
import app.donghaeng.car.domain.routesync.RouteSyncEngine
import app.donghaeng.car.adapter.navi.NaviAdapter
import app.donghaeng.protocol.Ack
import app.donghaeng.protocol.RouteChanged
import app.donghaeng.protocol.Topics
import app.donghaeng.protocol.VehicleEventType
import app.donghaeng.car.adapter.voice.VoiceAdapter
import app.donghaeng.car.service.DriveSessionController
import app.donghaeng.protocol.MessageTypes
import kotlinx.serialization.json.JsonObject
import app.donghaeng.protocol.Telemetry
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import app.donghaeng.car.core.network.TimeApi
import app.donghaeng.car.domain.time.ClockSync
import app.donghaeng.car.domain.latency.LatencyMeter
import app.donghaeng.car.BuildConfig
import app.donghaeng.car.adapter.speech.RecognizerChain
import app.donghaeng.car.core.common.DrivingStateMonitor
import app.donghaeng.car.domain.model.Priority
import app.donghaeng.car.domain.ptt.PttController
import app.donghaeng.car.domain.ptt.PttStage
import app.donghaeng.car.domain.routesync.VoiceRouteAsk
import app.donghaeng.car.domain.routesync.VoiceAnswer
import app.donghaeng.car.domain.settings.SharedPrefsUserSettingsStore
import app.donghaeng.car.domain.settings.UserSettings
import app.donghaeng.car.service.DriveSessionService
import app.donghaeng.car.surface.AppForegroundTracker
import android.content.pm.ServiceInfo
import android.os.Build
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.map
import javax.inject.Singleton

/** flavor 공통 도메인 수집기 바인딩 (T22). 어댑터는 flavor 별 AdapterModule 이 준다. */
@Module
@InstallIn(SingletonComponent::class)
object SignalsModule {

    @Provides
    @Singleton
    fun provideVehicleSignals(adapter: VehicleAdapter): VehicleSignals = VehicleSignals(adapter)

    /** T23: 활성 그룹 동안 1Hz 텔레메트리. 위치 = LocationFeed 최신 Fix, 속도 = 차량 신호 → 위치 계산, ETA = 내비 TripInfo */
    @Provides
    @Singleton
    fun provideTelemetryCollector(
        sessionManager: MqttSessionManager,
        tokenStore: TokenStore,
        mqtt: MqttGateway,
        locationFeed: LocationFeed,
        vehicleSignals: VehicleSignals,
        naviMonitor: NaviEventMonitor,
    ): TelemetryCollector = TelemetryCollector(
        session = sessionManager.session,
        vehicleId = { (tokenStore.state.value as? TokenStoreState.Present)?.credentials?.vehicleId },
        latestFix = { locationFeed.fixes.replayCache.firstOrNull() },
        fallbackSpeedKph = { locationFeed.speedKph.value },
        vehicle = vehicleSignals.state,
        navi = naviMonitor.state,
        publish = { topic, telemetry -> mqtt.publish(topic, MessageTypes.TELEMETRY, telemetry, Telemetry.serializer(), qos = 0) },
    )

    @Provides
    @Singleton
    fun provideMyFixSource(feed: LocationFeed): MyFixSource = feed

    /**
     * A120: 설정 화면 값(자동 따라가기·지연 표시) — `dh_settings`. 지연 표시 기본값은 debug 켬 / release 끔.
     * '음성 안내' 스위치는 음성 쪽(B066 VoiceAnnouncer 자체 저장소)이 저장한다 — 같은 값을 두 곳에 두지 않는다(RA-3).
     */
    @Provides
    @Singleton
    fun provideUserSettings(@ApplicationContext context: Context): UserSettings =
        UserSettings(SharedPrefsUserSettingsStore(context), UserSettings.Defaults(latencyOverlay = BuildConfig.DEBUG))

    /**
     * T28: 선두 ROUTE_CHANGED 발행 / 멤버 ROUTE_FOLLOW_PROPOSAL 수락·거절 → ACK.
     * A006/A034: 주행 중 제안은 F16 '네/아니' 인식 체인으로 음성으로 묻고([askWhileDriving]), 답이 없으면 정차까지 보류한다.
     */
    @Provides
    @Singleton
    fun provideRouteSyncEngine(
        sessionManager: MqttSessionManager,
        tokenStore: TokenStore,
        mqtt: MqttGateway,
        navi: NaviAdapter,
        naviMonitor: NaviEventMonitor,
        voice: VoiceAnnouncer,
        driving: DrivingStateMonitor,
        voiceAdapter: VoiceAdapter,
        recognizers: dagger.Lazy<RecognizerChain>,
        ptt: dagger.Lazy<PttController>,
        settings: UserSettings,
        tracker: AppForegroundTracker,
    ): RouteSyncEngine {
        fun vid() = (tokenStore.state.value as? TokenStoreState.Present)?.credentials?.vehicleId
        return RouteSyncEngine(
            session = sessionManager.session,
            proposals = sessionManager.proposals,
            naviEvents = naviMonitor.events,
            navi = navi,
            publishRouteChanged = { change -> vid()?.let { mqtt.publish(Topics.vehicleEvent(it), VehicleEventType.ROUTE_CHANGED.name, change, RouteChanged.serializer(), qos = 1) } ?: false },
            publishAck = { ack -> vid()?.let { mqtt.publish(Topics.vehicleAck(it), MessageTypes.ACK, ack, Ack.serializer(), qos = 1) } ?: false },
            speak = { text, priority -> voice.announce(text, priority, "ROUTE_FOLLOW_PROPOSAL") },
            drivingFlow = driving.isDriving(),
            askYesNo = { question -> askWhileDriving(question, voice, voiceAdapter, recognizers.get(), ptt.get(), tracker) },
        ).also { it.setAutoFollow(settings.autoFollow) }
    }

    /**
     * 주행 중 경로 제안 음성 질문(A034) — 질문을 다 읽은 뒤에만 마이크를 연다(PTT 확인과 같은 순서). null = 묻지 않음(엔진이 읽고 정차까지 보류):
     * 음성 안내 꺼짐 / 무전이 쓰는 중(마이크를 다투지 않는다) / 들을 수 있는 인식 단계가 없음(권한·모델·서비스) / 마이크가 무음일 상태(RA-4).
     */
    private suspend fun askWhileDriving(
        question: String,
        announcer: VoiceAnnouncer,
        voice: VoiceAdapter,
        chain: RecognizerChain,
        ptt: PttController,
        tracker: AppForegroundTracker,
    ): VoiceAnswer? = VoiceRouteAsk.ask(
        question = question,
        voiceEnabled = { announcer.state.value.enabled },
        pttBusy = ptt.state.map { it.stage != PttStage.IDLE },
        pttBusyNow = { ptt.state.value.stage != PttStage.IDLE },
        hasCandidate = { chain.hasCandidate() },
        micUsable = { routeAskMicUsable(Build.VERSION.SDK_INT, tracker.appVisible.value, DriveSessionService.attachedType.value) },
        speakAndWait = { voice.speakAndWait(it, Priority.NORMAL, ROUTE_ASK_SPEAK_TIMEOUT_MS) },
        listen = { chain.listen(ROUTE_ASK_LISTEN_MS).answer },
    )

    /**
     * RA-4: 지금 마이크를 열면 소리가 담기는가 — API 30+ 에서 동행 화면이 뒤에 있으면 그룹 서비스에 microphone 포그라운드 타입이 붙어 있어야 한다
     * (없으면 앱이 여는 마이크는 오류 없이 무음). B 배치 `PttMicGate.usable` 과 같은 판정 — 병합 뒤 그것을 부르도록 바꾼다(handoff).
     */
    internal fun routeAskMicUsable(sdk: Int, appVisible: Boolean, attachedFgsType: Int?): Boolean = when {
        sdk < Build.VERSION_CODES.R -> true
        appVisible -> true
        attachedFgsType == null -> false
        else -> attachedFgsType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE != 0
    }

    /** "…따라갈까요? 네 또는 아니요로 답해 주세요." ≈ 6 s + 엔진 지연 */
    private const val ROUTE_ASK_SPEAK_TIMEOUT_MS = 12_000L

    /** '네/아니' 듣는 시간 — PTT 확인(5 s)보다 조금 넉넉히(운전 중 답이 늦을 수 있다) */
    private const val ROUTE_ASK_LISTEN_MS = 6_000L

    /** T27: 그룹 이벤트 → 음성 큐(우선순위·신선도 5s·내 이벤트 생략·대상 아님 생략) */
    @Provides
    @Singleton
    fun provideVoiceAnnouncer(sessionManager: MqttSessionManager, voice: VoiceAdapter, clockSync: ClockSync): VoiceAnnouncer =
        // 신선도(5s) 판정은 서버 시계 기준(T31 ClockSync) — 차량 시계가 틀려도 옛 이벤트만 생략한다.
        // 급제동 대상(audience)이 없으면 최신 스냅샷으로 앞뒤를 본다(EventAudience)
        VoiceAnnouncer(
            events = sessionManager.groupEventsReceived, myVehicleId = { sessionManager.myVehicleId }, voice = voice,
            groupState = { sessionManager.groupState.value }, clock = { clockSync.serverNow() },
        )

    /** T31: 서버 시각 동기화 — 시작 직후·5분마다 `GET /v1/time` 3회, 왕복 최소 표본 */
    @Provides
    @Singleton
    fun provideClockSync(timeApi: TimeApi): ClockSync = ClockSync(fetchServerTimeMs = { timeApi.time().serverTimeMs })

    /** T31: 그룹 EVENT·STATE 지연(ms) — 봉투 ts → 수신(시계 오프셋 보정) */
    @Provides
    @Singleton
    fun provideLatencyMeter(sessionManager: MqttSessionManager, clockSync: ClockSync, settings: UserSettings): LatencyMeter =
        LatencyMeter(events = sessionManager.groupEventsReceived, states = sessionManager.groupStatesReceived, clockState = clockSync.state)
            .also { it.setOverlayEnabled(settings.latencyOverlay) }   // A120: 저장값(release 기본 끔)

    /** T26: 활성 그룹 동안 1Hz 판정 → `dh/v1/vehicle/{vid}/event` QoS 1 */
    @Provides
    @Singleton
    fun provideVehicleEventEngine(
        sessionManager: MqttSessionManager,
        tokenStore: TokenStore,
        mqtt: MqttGateway,
        locationFeed: LocationFeed,
        vehicleSignals: VehicleSignals,
        naviMonitor: NaviEventMonitor,
        routeSync: RouteSyncEngine,
    ): VehicleEventEngine = VehicleEventEngine(
        session = sessionManager.session,
        vehicleId = { (tokenStore.state.value as? TokenStoreState.Present)?.credentials?.vehicleId },
        latestFix = { locationFeed.fixes.replayCache.firstOrNull() },
        fallbackSpeedKph = { locationFeed.speedKph.value },
        vehicle = vehicleSignals.state,
        naviEvents = naviMonitor.events,
        publish = { topic, type, payload -> mqtt.publish(topic, type, payload, JsonObject.serializer(), qos = 1) },
        // A005: 그룹이 따라가는 목적지(선두가 바꾼 목적지 수락·발행) — 내비 도착이 그룹 목적지인지 가릴 때 함께 본다
        followedDestination = { routeSync.state.value.followedDestination },
    )

    @Provides
    @Singleton
    fun provideDriveSessionController(
        @ApplicationContext context: Context,
        sessionManager: MqttSessionManager,
        tracker: app.donghaeng.car.surface.AppForegroundTracker,
    ): DriveSessionController =
        // F16: 포그라운드 타입 갱신(location·microphone)은 동행 화면이 보일 때만 — 백그라운드에서 다시 시작하면 dataSync 로 떨어진다
        DriveSessionController(context, sessionManager.session, appVisible = { tracker.appVisible.value })
}
