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
)

data class EventUi(val eventId: String, val type: String, val severity: Severity, val speech: String?, val receivedAtMs: Long)

enum class ConnState { CONNECTED, RECONNECTING, OFFLINE }

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
) {
    val hasSnapshot: Boolean get() = members.isNotEmpty()
    val proposalRemainingS: Long? get() = proposal?.let { ((it.expiresAtMs - nowMs) / 1_000).coerceAtLeast(0) }
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
        viewModelScope.launch {
            sessionManager.groupEvents.collect { e -> events.update { (listOf(e.toUi(clock())) + it).distinctBy { it.eventId }.take(MAX_EVENTS) } }
        }
    }

    private data class Core(val session: GroupSession?, val state: GroupState?, val stateAt: Long?, val myVehicleId: String?)
    private data class Side(val mqtt: MqttState, val fix: Fix?, val driving: Boolean, val events: List<EventUi>, val now: Long)
    private data class Extra(val busy: Boolean, val error: String?, val proposal: RouteProposal?, val autoFollow: Boolean, val latencyText: String? = null)

    val uiState: StateFlow<GroupDriveUiState> = combine(
        combine(sessionManager.session, sessionManager.groupState, sessionManager.groupStateAt, tokenStore.state) { s, g, t, tok ->
            Core(s, g, t, (tok as? TokenStoreState.Present)?.credentials?.vehicleId)
        },
        combine(mqtt.status, fixes.fixes.map<Fix, Fix?> { it }.onStart { emit(null) }, driving.isDriving(), events, ticker) { m, f, d, e, n -> Side(m.state, f, d, e, n) },
        combine(busy, error, routeSync.state, latency.state) { b, err, rs, lat -> Extra(b, err, rs.pending, rs.autoFollow, lat.overlayText) },
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
                is ApiResult.UnexpectedFailure -> error.value = "예상치 못한 오류가 났어요 (${result.cause.javaClass.simpleName})"
            }
            busy.value = false
        }
    }

    fun clearError() {
        error.value = null
    }

    fun acceptProposal() { viewModelScope.launch { routeSync.accept() } }

    fun declineProposal() = routeSync.decline()

    /** 테스트용: 틱 주기·시각 교체 */
    fun configureForTest(tickMs: Long, clock: () -> Long) {
        this.tickMs = tickMs
        this.clock = clock
    }

    private fun GroupEvent.toUi(now: Long) = EventUi(eventId = eventId, type = type, severity = severity, speech = speech, receivedAtMs = now)

    companion object {
        const val MAX_EVENTS = 20

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
