package app.donghaeng.car.feature.groupdrive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.donghaeng.car.core.common.DhLog
import app.donghaeng.car.core.common.DrivingStateMonitor
import app.donghaeng.car.core.common.TokenStore
import app.donghaeng.car.core.common.TokenStoreState
import app.donghaeng.car.core.network.ApiResult
import app.donghaeng.car.core.network.mqtt.MqttGateway
import app.donghaeng.car.core.network.mqtt.MqttState
import app.donghaeng.car.domain.group.GroupRepository
import app.donghaeng.car.domain.location.MyFixSource
import app.donghaeng.car.domain.model.Fix
import app.donghaeng.car.domain.routesync.RouteProposal
import app.donghaeng.car.domain.routesync.RouteSyncEngine
import app.donghaeng.car.domain.session.GroupSession
import app.donghaeng.car.domain.session.MqttSessionManager
import app.donghaeng.car.domain.session.ReceivedGroupEvent
import app.donghaeng.car.adapter.navi.NaviAdapter
import app.donghaeng.car.domain.model.Place
import app.donghaeng.car.domain.navi.NaviEventMonitor
import app.donghaeng.car.surface.SurfaceFeed
import app.donghaeng.car.surface.SurfaceMode
import kotlinx.coroutines.flow.distinctUntilChanged
import app.donghaeng.protocol.EventAudience
import app.donghaeng.protocol.GroupEvent
import app.donghaeng.protocol.GroupState
import app.donghaeng.protocol.MemberState
import app.donghaeng.protocol.MemberStatus
import app.donghaeng.protocol.Severity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 화면용 멤버 한 대 (T25). 좌표는 지도에만 쓰고 로그에는 찍지 않는다 */
data class MemberUi(
    val vehicleId: String,
    val nickname: String,
    val colorArgb: Long,
    val isLeader: Boolean,
    val isMe: Boolean,
    val online: Boolean,
    val status: MemberStatus,
    val lat: Double?,
    val lon: Double?,
    val heading: Float?,
    val speedKph: Float?,
    val distanceToLeaderM: Int?,
    val etaText: String?,
    val energyText: String?,
    val ageS: Long?,
    /** 정차 대시보드(docs/05 §5.1)용 원값 — 도착 예상 시각(epoch ms)·남은 거리·시간·잔량 */
    val etaDestTs: Long? = null,
    val remainM: Int? = null,
    val remainS: Int? = null,
    val fuelPct: Int? = null,
    val socPct: Int? = null,
)

data class EventUi(val eventId: String, val type: String, val severity: Severity, val speech: String?, val receivedAtMs: Long)

enum class ConnState { CONNECTED, RECONNECTING, OFFLINE }

/**
 * 그룹 세션이 끝난 이유(A115) — 정차 화면(EndedPane)이 사유별 문구·버튼을 고른다(화면 반영은 E 단계).
 * - [GROUP_ENDED]: 방장이 끝냄 / [ENDED_WHILE_OFFLINE]: 꺼져 있던 사이 끝났거나 빠짐(재접속 때 서버와 맞춤) / [REMOVED]: 폰·REST 가 이 차를 그룹에서 뺌
 * - [LEFT]: 이 차가 나가기·종료 / [UNPAIRED]: 차량 연결이 해제됨(새 그룹 대신 먼저 다시 연결) / [OTHER]: 기록 없음·저장소 문제
 */
enum class SessionEndReason { GROUP_ENDED, ENDED_WHILE_OFFLINE, REMOVED, LEFT, UNPAIRED, OTHER }

/** docs/05 §5 `GroupDriveUiState` */
data class GroupDriveUiState(
    val session: GroupSession? = null,
    val members: List<MemberUi> = emptyList(),
    val me: MemberUi? = null,
    val leader: MemberUi? = null,
    /** 최근 그룹 이벤트(최신 먼저, 최대 20) */
    val events: List<EventUi> = emptyList(),
    val connection: ConnState = ConnState.OFFLINE,
    /** 마지막 STATE 스냅샷 경과(초). null = 아직 없음 */
    val snapshotAgeS: Long? = null,
    val isDriving: Boolean = false,
    /** 내 최신 위치(스냅샷에 아직 없을 때 지도에 쓴다) */
    val myLat: Double? = null,
    val myLon: Double? = null,
    val myHeading: Float? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val nowMs: Long = 0L,
    /** T28 대기 중인 선두 경로 제안 */
    val proposal: RouteProposal? = null,
    val autoFollow: Boolean = false,
    /** T31 지연 오버레이 문구("이벤트 123 ms · 상태 85 ms"), 꺼져 있거나 표본 없으면 null */
    val latencyText: String? = null,
    /** 정차 대시보드 하단 '지연 N ms'(이벤트 마지막 값, 표시 꺼짐이면 null) */
    val latencyMs: Int? = null,
    /** 그룹 다음 정차지(RouteSyncEngine 이 마지막으로 수락·발행한 경유지) */
    val nextStop: Place? = null,
    /** 내비 제한속도(km/h). 0·모름 = null */
    val speedLimitKph: Int? = null,
    /** 주행 중 표면 모드 — CARDS_ONLY 면 대시보드에 권한 안내 1줄 */
    val surfaceMode: SurfaceMode = SurfaceMode.OFF,
    /** 실제 수신 시각이 붙은 최근 그룹 이벤트(최신 먼저) — 대시보드 이벤트 띠(10초)·지난 소식 */
    val recentEvents: List<ReceivedGroupEvent> = emptyList(),
    /** 서버 시계 기준 지금(T31 ClockSync) — 이벤트 신선도·"N초 전". null = [nowMs] 로 대신 */
    val serverNowMs: Long? = null,
    /** 내 차 id(페어링 자격 증명) — 대시보드도 패널·카드처럼 내 차 출처 이벤트를 뺀다. null = [me] 의 id */
    val myVehicleId: String? = null,
    /** 연속 주행(ms) — 주행 구간 합, 15분 이상 쉬면 0(ContinuousDriveTimer). null = 모름 */
    val continuousDriveMs: Long? = null,
    /** 음성 경로·지연·시작 신호 — 대시보드 띠의 위험 이벤트 강조(패널 소식 줄과 같은 값, docs/05 §5.2) */
    val voiceCue: app.donghaeng.car.domain.voice.VoiceCue = app.donghaeng.car.domain.voice.VoiceCue(),
    /** 세션이 없을 때 끝난 이유(A115). 세션이 있으면 null */
    val endReason: SessionEndReason? = null,
) {
    val hasSnapshot: Boolean get() = members.isNotEmpty()
    val proposalRemainingS: Long? get() = proposal?.let { ((it.expiresAtMs - nowMs) / 1_000).coerceAtLeast(0) }

    /**
     * 제안 카드 아래 줄(E006·E034): 주행 중이라 정차까지 보류 중이면([RouteProposal.heldUntilParked]) 남은 초 대신 "정차하면 답할 수 있어요" —
     * 보류 중 [RouteProposal.expiresAtMs] 는 보류 상한(최대 10분)이라 초를 세면 오해를 준다. 정차가 5초 이어지면 엔진이 보류를 풀고 30초를 센다.
     */
    val proposalStatus: ProposalStatus?
        get() {
            val p = proposal ?: return null
            return when {
                p.heldUntilParked && isDriving -> ProposalStatus.Held
                p.heldUntilParked -> ProposalStatus.Settling
                else -> ProposalStatus.Countdown(proposalRemainingS ?: 0)
            }
        }
}

/** 제안 카드 상태 줄 — 화면 문구는 strings.xml(drive_proposal_*) */
sealed interface ProposalStatus {
    /** 주행 중 보류 — "정차하면 답할 수 있어요" */
    data object Held : ProposalStatus

    /** 보류 중인데 정차함 — 정차가 이어지는지 확인하는 몇 초(엔진 PARK_SETTLE) */
    data object Settling : ProposalStatus

    /** "N초 뒤 자동으로 거절돼요" */
    data class Countdown(val seconds: Long) : ProposalStatus
}

/**
 * 그룹 주행 화면 (T25): 서버 2초 스냅샷(`MqttSessionManager.groupState`) + 그룹 이벤트 + 내 위치 + 연결 상태를 합친다.
 * 입력은 "나가기/종료" 뿐이며 주행 중([DrivingStateMonitor])에는 막는다(docs/05 §4).
 */
@HiltViewModel
class GroupDriveViewModel @Inject constructor(
    private val sessionManager: MqttSessionManager,
    private val repo: GroupRepository,
    tokenStore: TokenStore,
    mqtt: MqttGateway,
    fixes: MyFixSource,
    driving: DrivingStateMonitor,
    private val routeSync: RouteSyncEngine,
    latency: app.donghaeng.car.domain.latency.LatencyMeter,
    naviMonitor: NaviEventMonitor,
    private val navi: NaviAdapter,
    surface: SurfaceFeed,
) : ViewModel() {

    private var tickMs: Long = 1_000L
    private var clock: () -> Long = System::currentTimeMillis

    private val events = MutableStateFlow<List<EventUi>>(emptyList())
    private val busy = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)

    private val ticker: Flow<Long> = flow {
        while (true) {
            emit(clock())
            delay(tickMs)
        }
    }

    init {
        viewModelScope.launch { tokenStore.load() }
        // A010: 그룹이 바뀌거나 끝나면 이전 그룹 소식을 비운다(같은 화면 인스턴스가 새 그룹을 보여 줄 때)
        viewModelScope.launch {
            sessionManager.session.map { it?.groupId }.distinctUntilChanged().collect { events.value = emptyList() }
        }
        viewModelScope.launch {
            sessionManager.groupEvents.collect { e ->
                // 내 차가 대상이 아닌 이벤트(뒤차의 급제동 등 — EventAudience)는 이벤트 스트립에도 넣지 않는다.
                // 판정은 이 ViewModel 이 받는 시점의 스냅샷 — groupEvents 는 replay 20 이라 화면을 다시 열면 그때 스냅샷으로 다시 판정한다(docs/05)
                if (!EventAudience.isForMe(e, sessionManager.myVehicleId, sessionManager.groupState.value?.members)) return@collect
                events.update { (listOf(e.toUi(clock())) + it).distinctBy { it.eventId }.take(MAX_EVENTS) }
            }
        }
    }

    private data class Core(val session: GroupSession?, val state: GroupState?, val stateAt: Long?, val myVehicleId: String?, val endReason: SessionEndReason?)
    private data class Side(val mqtt: MqttState, val fix: Fix?, val driving: Boolean, val events: List<EventUi>, val now: Long)
    private data class Extra(
        val busy: Boolean,
        val error: String?,
        val proposal: RouteProposal?,
        val autoFollow: Boolean,
        val latencyText: String? = null,
        val latencyMs: Int? = null,
        val nextStop: Place? = null,
        val speedLimitKph: Int? = null,
        val surfaceMode: SurfaceMode = SurfaceMode.OFF,
        val recentEvents: List<ReceivedGroupEvent> = emptyList(),
    )

    val uiState: StateFlow<GroupDriveUiState> = combine(
        combine(sessionManager.session, sessionManager.groupState, sessionManager.groupStateAt, tokenStore.state, sessionManager.lastSessionClear) { s, g, t, tok, clear ->
            Core(s, g, t, (tok as? TokenStoreState.Present)?.credentials?.vehicleId, if (s != null) null else endReasonOf(tok, clear?.reason))
        },
        combine(mqtt.status, fixes.fixes.map<Fix, Fix?> { it }.onStart { emit(null) }, driving.isDriving(), events, ticker) { m, f, d, e, n -> Side(m.state, f, d, e, n) },
        combine(
            combine(busy, error) { b, err -> b to err },
            routeSync.state,
            latency.state,
            naviMonitor.state.map { it.speedLimitKph?.takeIf { v -> v > 0 } }.distinctUntilChanged(),
            combine(surface.mode, surface.recentEvents) { m, r -> m to r },
        ) { (b, err), rs, lat, limit, (mode, recent) ->
            Extra(
                busy = b, error = err, proposal = rs.pending, autoFollow = rs.autoFollow, latencyText = lat.overlayText,
                latencyMs = if (lat.overlayEnabled) lat.event?.lastMs?.toInt() else null,
                nextStop = rs.activeWaypoint, speedLimitKph = limit, surfaceMode = mode, recentEvents = recent,
            )
        },
    ) { core, side, extra ->
        val members = core.state?.members?.map { it.toUi(core.state.leaderVehicleId, core.myVehicleId) }.orEmpty()
        GroupDriveUiState(
            session = core.session,
            members = members,
            me = members.firstOrNull { it.isMe },
            leader = members.firstOrNull { it.isLeader },
            events = side.events,
            connection = when (side.mqtt) {
                MqttState.CONNECTED -> ConnState.CONNECTED
                MqttState.CONNECTING, MqttState.RECONNECTING -> ConnState.RECONNECTING
                MqttState.DISCONNECTED -> ConnState.OFFLINE
            },
            snapshotAgeS = core.stateAt?.let { (side.now - it).coerceAtLeast(0) / 1_000 },
            isDriving = side.driving,
            myLat = side.fix?.lat,
            myLon = side.fix?.lon,
            myHeading = side.fix?.heading,
            busy = extra.busy,
            error = extra.error,
            nowMs = side.now,
            proposal = extra.proposal,
            autoFollow = extra.autoFollow,
            latencyText = extra.latencyText,
            latencyMs = extra.latencyMs,
            nextStop = extra.nextStop,
            speedLimitKph = extra.speedLimitKph,
            surfaceMode = extra.surfaceMode,
            recentEvents = extra.recentEvents,
            serverNowMs = surface.serverNow(),
            myVehicleId = core.myVehicleId,
            continuousDriveMs = surface.continuousDriveMs(),
            voiceCue = surface.voiceCue(),
            endReason = core.endReason,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GroupDriveUiState())

    /** 방장이면 그룹 종료, 멤버면 나가기. 성공하면 세션이 비워져 화면이 "끝남" 상태로 바뀐다 */
    fun endOrLeave(dispatcher: CoroutineDispatcher = Dispatchers.Default) {
        if (busy.value) return
        val session = sessionManager.session.value ?: return
        busy.value = true
        error.value = null
        viewModelScope.launch(dispatcher) {
            val result = if (session.isLeader) repo.end() else repo.leave()
            when (result) {
                is ApiResult.Success -> DhLog.i("group drive: ${if (session.isLeader) "ended" else "left"} group")
                is ApiResult.HttpFailure -> error.value = result.error?.message ?: "요청에 실패했어요 (HTTP ${result.status})"
                is ApiResult.NetworkFailure -> error.value = "서버에 연결할 수 없어요"
                // 예외 클래스 이름은 화면에 보이지 않는다(E119) — 로그에만
                is ApiResult.UnexpectedFailure -> {
                    DhLog.w("group drive: end/leave 실패 ${result.summary}")
                    error.value = "처리하지 못했어요. 잠시 후 다시 시도해 주세요"
                }
            }
            busy.value = false
        }
    }

    /**
     * 정차 대시보드 [목적지 안내 시작](4-3·5-1) — 순정 내비에 그룹 목적지 경로 안내를 요청한다(NaviHelper requestRoute).
     * 안내가 시작되면 TripInfo 로 ETA 가 생겨 모든 차의 도착 예상이 채워진다. 주행 중이면 무시(화면 버튼도 정차 중에만).
     */
    fun startGuidance() {
        val session = sessionManager.session.value ?: return
        if (uiState.value.isDriving) {
            DhLog.w("group drive: 안내 시작 무시(주행 중)")
            return
        }
        viewModelScope.launch {
            runCatching { navi.requestRoute(session.destination) }
                .onSuccess { DhLog.i("group drive: 목적지 안내 시작 요청") }
                .onFailure { DhLog.w("group drive: 목적지 안내 요청 실패 ${it.javaClass.simpleName}") }
        }
    }

    fun clearError() {
        error.value = null
    }

    fun acceptProposal() { viewModelScope.launch { routeSync.accept() } }

    fun declineProposal() = routeSync.decline()

    /** [내비 보기] 폴백 — 지도 앱 selector 가 실패하면 NaviHelper 로 내비 화면을 요청한다 */
    fun showNavigationFallback() {
        viewModelScope.launch {
            runCatching { navi.showNavigation() }
                .onSuccess { DhLog.i("group drive: 내비 보기 → NaviHelper 화면 요청") }
                .onFailure { DhLog.w("group drive: 내비 화면 요청 실패 ${it.javaClass.simpleName}") }
        }
    }

    /** 테스트용: 틱 주기·시각 교체 */
    fun configureForTest(tickMs: Long, clock: () -> Long) {
        this.tickMs = tickMs
        this.clock = clock
    }

    private fun GroupEvent.toUi(now: Long) = EventUi(eventId = eventId, type = type, severity = severity, speech = speech, receivedAtMs = now)

    companion object {
        const val MAX_EVENTS = 20

        /** A115: 세션 삭제 사유(+ 토큰 상태) → 화면용 끝난 이유. 순수 함수(단위 테스트) */
        fun endReasonOf(tokens: TokenStoreState, clearReason: String?): SessionEndReason = when {
            tokens is TokenStoreState.Empty -> SessionEndReason.UNPAIRED
            clearReason == app.donghaeng.car.domain.session.SessionClearReason.GROUP_ENDED.name -> SessionEndReason.GROUP_ENDED
            clearReason == app.donghaeng.car.domain.session.SessionClearReason.RECONCILED.name -> SessionEndReason.ENDED_WHILE_OFFLINE
            clearReason == app.donghaeng.car.domain.session.SessionClearReason.MEMBER_LEFT_ME.name -> SessionEndReason.REMOVED
            clearReason == app.donghaeng.car.domain.session.SessionClearReason.LEFT_LOCALLY.name -> SessionEndReason.LEFT
            clearReason == app.donghaeng.car.domain.session.SessionClearReason.UNPAIRED.name -> SessionEndReason.UNPAIRED
            else -> SessionEndReason.OTHER
        }

        fun MemberState.toUi(leaderVehicleId: String, myVehicleId: String?): MemberUi = MemberUi(
            vehicleId = vehicleId,
            nickname = nickname,
            colorArgb = parseColor(color),
            isLeader = vehicleId == leaderVehicleId,
            isMe = vehicleId == myVehicleId,
            online = online,
            status = status,
            lat = lat,
            lon = lon,
            heading = heading,
            speedKph = speedKph,
            distanceToLeaderM = distanceToLeaderM,
            etaText = eta?.let { e ->
                val d = e.remainM?.let { m -> if (m >= 1_000) "%.1f km".format(m / 1_000.0) else "$m m" }
                val t = e.remainS?.let { s -> if (s >= 3_600) "${s / 3_600}시간 ${(s % 3_600) / 60}분" else "${s / 60}분" }
                listOfNotNull(d, t).joinToString(" · ").ifEmpty { null }
            },
            energyText = energy?.let { en ->
                listOfNotNull(en.fuelPct?.let { "연료 $it%" }, en.socPct?.let { "배터리 $it%" }).joinToString(" · ").ifEmpty { null }
            },
            ageS = ageMs?.let { it / 1_000 },
            etaDestTs = eta?.destTs,
            remainM = eta?.remainM,
            remainS = eta?.remainS,
            fuelPct = energy?.fuelPct,
            socPct = energy?.socPct,
        )

        /** `#RRGGBB` → ARGB. 못 읽으면 회색 */
        fun parseColor(hex: String): Long = try {
            val h = hex.removePrefix("#")
            val rgb = h.toLong(16)
            if (h.length == 6) 0xFF000000L or rgb else rgb
        } catch (e: Exception) {
            0xFF9E9E9EL
        }
    }
}
