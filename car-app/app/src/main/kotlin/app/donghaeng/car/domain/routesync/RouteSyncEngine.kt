package app.donghaeng.car.domain.routesync

import app.donghaeng.car.adapter.navi.NaviAdapter
import app.donghaeng.car.core.common.DhLog
import app.donghaeng.car.core.common.TextMasking
import app.donghaeng.car.domain.model.NaviEvent
import app.donghaeng.car.domain.model.Place
import app.donghaeng.car.domain.model.Priority
import app.donghaeng.car.domain.model.toLogString
import app.donghaeng.car.domain.session.GroupSession
import app.donghaeng.car.domain.session.ReceivedProposal
import app.donghaeng.protocol.Ack
import app.donghaeng.protocol.RouteChanged
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 화면에 보여 줄 대기 중 제안 */
data class RouteProposal(
    val proposalId: String,
    val destination: Place,
    val waypoints: List<Place>,
    val speech: String,
    val receivedAtMs: Long,
    val expiresAtMs: Long,
)

data class RouteSyncState(
    val running: Boolean = false,
    /** 선두 경로를 묻지 않고 바로 따라간다(설정) */
    val autoFollow: Boolean = false,
    val pending: RouteProposal? = null,
    val accepted: Int = 0,
    val declined: Int = 0,
    val autoDeclined: Int = 0,
    val lastResult: String? = null,
    /** 선두차 역할: 보낸 ROUTE_CHANGED 수·마지막 시각 */
    val routeChangedSent: Int = 0,
    val lastRouteChangedAtMs: Long? = null,
    val lastRouteChangedSummary: String? = null,
)

/**
 * 선두 경로 동기화 (T28, PRD F6, docs/03 §5.2/§5.5/§5.6, docs/10 R5).
 *
 * 선두차 역할: 내비의 목적지·경유지 변경([NaviEvent.DestinationChanged]/[NaviEvent.WaypointChanged])을 [debounceMs] 로 묶어
 * 그룹 목적지와 다르거나 경유지가 생기면 `ROUTE_CHANGED { destination, waypoints }` 를 발행한다(같은 내용은 한 번만).
 *
 * 멤버 역할: cmd `ROUTE_FOLLOW_PROPOSAL` 을 받으면 문구를 읽어 주고(제안 음성), 화면에 "따라가기/아니요" 를 띄운다.
 * - 수락: 경유지가 있으면 `requestRouteWithWaypoints`, 없으면 `requestRoute` (docs/10 R5: `addWaypoint` 대신 전체 재요청이 확실하다) → ACK accepted=true.
 * - 거절 또는 [autoDeclineMs](30초) 무응답 → ACK accepted=false (주행 중 입력 없이도 안전한 기본값, docs/05 §4).
 * - 설정 "자동 따라가기" 가 켜져 있으면 묻지 않고 바로 수락한다.
 */
class RouteSyncEngine(
    private val session: StateFlow<GroupSession?>,
    private val proposals: Flow<ReceivedProposal>,
    private val naviEvents: Flow<NaviEvent>,
    private val navi: NaviAdapter,
    private val publishRouteChanged: (RouteChanged) -> Boolean,
    private val publishAck: (Ack) -> Boolean,
    private val speak: (text: String, priority: Priority) -> Unit,
    private val autoDeclineMs: Long = AUTO_DECLINE_MS,
    private val debounceMs: Long = DEBOUNCE_MS,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var job: Job? = null
    private var expiryJob: Job? = null
    private var debounceJob: Job? = null

    private val _state = MutableStateFlow(RouteSyncState())
    val state: StateFlow<RouteSyncState> = _state

    // 선두차: 마지막으로 안 내비 목적지·경유지와 마지막으로 보낸 내용
    private var naviDestination: Place? = null
    private var naviWaypoints: List<Place> = emptyList()
    private var lastSent: RouteChanged? = null

    fun setAutoFollow(enabled: Boolean) {
        _state.update { it.copy(autoFollow = enabled) }
        DhLog.i("routesync: autoFollow=$enabled")
        if (enabled) _state.value.pending?.let { scope.launch { accept() } }
    }

    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        _state.update { it.copy(running = true) }
        job = scope.launch {
            launch { proposals.collect { onProposal(it) } }
            launch { naviEvents.collect { onNavi(it) } }
            launch {
                session.collect { s ->
                    if (s == null) {
                        // 그룹이 끝나면 대기 중 제안은 의미 없다
                        expiryJob?.cancel()
                        _state.update { it.copy(pending = null) }
                        lastSent = null
                    }
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel(); job = null
        expiryJob?.cancel(); debounceJob?.cancel()
        _state.update { it.copy(running = false, pending = null) }
    }

    // ---------------------------------------------------------------- 멤버 역할

    /** 제안 수신 (테스트에서 직접 부를 수 있다) */
    suspend fun onProposal(p: ReceivedProposal) {
        if (session.value == null) {
            DhLog.i("routesync: proposal ignored (no group)")
            return
        }
        val now = clock()
        val proposal = RouteProposal(
            proposalId = p.cmd.proposalId,
            destination = p.cmd.destination,
            waypoints = p.cmd.waypoints,
            speech = p.cmd.speech,
            receivedAtMs = now,
            expiresAtMs = now + autoDeclineMs,
        )
        expiryJob?.cancel()
        _state.update { it.copy(pending = proposal) }
        DhLog.i("routesync: proposal ${TextMasking.maskVehicleId(proposal.proposalId)} dest=${proposal.destination.toLogString()} waypoints=${proposal.waypoints.size} autoFollow=${_state.value.autoFollow}")
        if (_state.value.autoFollow) {
            accept()
            return
        }
        speak(proposal.speech, Priority.NORMAL)
        expiryJob = scope.launch {
            delay(autoDeclineMs)
            if (_state.value.pending?.proposalId == proposal.proposalId) decline(auto = true)
        }
    }

    suspend fun accept() {
        val p = _state.value.pending ?: return
        expiryJob?.cancel()
        _state.update { it.copy(pending = null) }
        val ok = runCatching {
            if (p.waypoints.isEmpty()) navi.requestRoute(p.destination) else navi.requestRouteWithWaypoints(p.destination, p.waypoints)
        }.onFailure { DhLog.w("routesync: 내비 경로 요청 실패 ${it.javaClass.simpleName}") }.isSuccess
        val sent = publishAck(Ack(proposalId = p.proposalId, accepted = true))
        _state.update { it.copy(accepted = it.accepted + 1, lastResult = "수락 ${TextMasking.maskVehicleId(p.proposalId)} navi=${if (ok) "요청" else "실패"} ack=$sent") }
        DhLog.i("routesync: accepted ${TextMasking.maskVehicleId(p.proposalId)} navi=$ok ack=$sent")
    }

    fun decline(auto: Boolean = false) {
        val p = _state.value.pending ?: return
        expiryJob?.cancel()
        _state.update { it.copy(pending = null) }
        val sent = publishAck(Ack(proposalId = p.proposalId, accepted = false))
        _state.update {
            if (auto) it.copy(autoDeclined = it.autoDeclined + 1, lastResult = "자동 거절(30초 무응답) ${TextMasking.maskVehicleId(p.proposalId)} ack=$sent")
            else it.copy(declined = it.declined + 1, lastResult = "거절 ${TextMasking.maskVehicleId(p.proposalId)} ack=$sent")
        }
        DhLog.i("routesync: declined auto=$auto ${TextMasking.maskVehicleId(p.proposalId)} ack=$sent")
    }

    // ---------------------------------------------------------------- 선두차 역할

    private fun onNavi(e: NaviEvent) {
        when (e) {
            is NaviEvent.DestinationChanged -> naviDestination = e.destination
            is NaviEvent.WaypointChanged -> naviWaypoints = e.waypoints
            else -> return
        }
        val s = session.value ?: return
        if (!s.isLeader) return
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(debounceMs)
            publishIfChanged(s)
        }
    }

    private fun publishIfChanged(s: GroupSession) {
        val dest = naviDestination ?: return
        val change = RouteChanged(destination = dest, waypoints = naviWaypoints)
        // 그룹 목적지 그대로 + 경유지 없음 = 변화 아님(구독 직후 현재 목적지 콜백)
        if (naviWaypoints.isEmpty() && samePlace(dest, s.destination) && lastSent == null) return
        if (change == lastSent) return
        publish(change)
    }

    /** 진단·시연용: 그룹 목적지에 경유지 하나를 더한 ROUTE_CHANGED 를 보낸다(선두차일 때만) */
    fun publishTestRouteChange(waypoint: Place): Boolean {
        val s = session.value ?: return false
        if (!s.isLeader) return false
        return publish(RouteChanged(destination = s.destination, waypoints = listOf(waypoint)))
    }

    private fun publish(change: RouteChanged): Boolean {
        val sent = publishRouteChanged(change)
        if (sent) lastSent = change
        val summary = "dest=${change.destination.name} waypoints=${change.waypoints.joinToString { it.name }}"
        _state.update { it.copy(routeChangedSent = if (sent) it.routeChangedSent + 1 else it.routeChangedSent, lastRouteChangedAtMs = clock(), lastRouteChangedSummary = summary) }
        DhLog.i("routesync: ROUTE_CHANGED published=$sent ${change.destination.toLogString()} waypoints=${change.waypoints.size}")
        return sent
    }

    private fun samePlace(a: Place, b: Place): Boolean =
        kotlin.math.abs(a.lat - b.lat) < 1e-5 && kotlin.math.abs(a.lon - b.lon) < 1e-5

    companion object {
        const val AUTO_DECLINE_MS = 30_000L
        const val DEBOUNCE_MS = 2_000L

        /** 시연용 경유지(공공장소 근방 좌표) */
        val DEMO_WAYPOINT = Place(lat = 37.2963, lon = 127.4120, name = "덕평휴게소", poiId = null, address = null)
    }
}
