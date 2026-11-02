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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 화면에 보여 줄 대기 중 제안 */
data class RouteProposal(
    val proposalId: String,
    val destination: Place,
    val waypoints: List<Place>,
    val speech: String,
    val receivedAtMs: Long,
    /** 자동 거절 시각. [heldUntilParked] 면 정차 없이 기다리는 상한(보류 만료) */
    val expiresAtMs: Long,
    /** 서버 제안이 아닌 이 차 안에서 만든 제안(합류 직후 선두 목적지 안내, T30) — ACK 를 보내지 않는다 */
    val local: Boolean = false,
    /**
     * 주행 중이라 답을 받지 못해 **정차할 때까지 보류** 중(A006/A034). 화면은 '정차하면 답할 수 있어요'로 보이고 버튼은 주행 중 비활성 그대로.
     * 정차하면 false 로 바뀌며 다시 읽고 [expiresAtMs] 가 그때부터 30초가 된다.
     */
    val heldUntilParked: Boolean = false,
)

/** 주행 중 음성 '네/아니' 질문 결과(A034) */
enum class VoiceAnswer { YES, NO, NO_ANSWER }

data class RouteSyncState(
    val running: Boolean = false,
    /** 선두 경로를 묻지 않고 바로 따라간다(설정 — 저장값은 UserSettings, A120) */
    val autoFollow: Boolean = false,
    val pending: RouteProposal? = null,
    val accepted: Int = 0,
    val declined: Int = 0,
    val autoDeclined: Int = 0,
    /** 주행 중이라 정차까지 보류한 횟수(A006) */
    val held: Int = 0,
    /** 주행 중 음성으로 답을 받은 횟수(A034) */
    val voiceAnswered: Int = 0,
    /** 새 제안에 밀려 ACK false 로 끝낸 서버 제안 수(A057) */
    val superseded: Int = 0,
    val lastResult: String? = null,
    /** 선두차 역할: 보낸 ROUTE_CHANGED 수·마지막 시각 */
    val routeChangedSent: Int = 0,
    val lastRouteChangedAtMs: Long? = null,
    val lastRouteChangedSummary: String? = null,
    /**
     * 그룹 다음 정차지(휴게소) — 마지막으로 **수락(멤버)·발행(선두)** 한 경로의 첫 경유지. 경유지 없는 경로를 수락하거나,
     * 내비가 경유지 도착을 알리거나, 내비 경유지 목록에서 빠지거나(A056), 그룹이 바뀌면 null. 정차 대시보드의 '다음 정차'·휴식 추천에 쓴다(docs/05 §5.1).
     */
    val activeWaypoint: Place? = null,
    /**
     * 마지막으로 수락(멤버)·발행(선두)한 경로의 목적지 — 그룹 목적지와 다를 수 있다(선두가 목적지를 바꿈). 이벤트 엔진이 '내비 도착'이
     * 그룹이 따라가는 목적지인지 가리는 데 쓴다(A005). 그룹이 바뀌면 null.
     */
    val followedDestination: Place? = null,
)

/**
 * 선두 경로 동기화 (T28, PRD F6, docs/03 §5.2/§5.5/§5.6, docs/10 R5).
 *
 * 선두차 역할: 내비의 목적지·경유지 변경([NaviEvent.DestinationChanged]/[NaviEvent.WaypointChanged])을 [debounceMs] 로 묶어
 * 그룹 목적지와 다르거나 경유지가 생기면 `ROUTE_CHANGED { destination, waypoints }` 를 발행한다(같은 내용은 한 번만).
 * 앱을 다시 켠 뒤(복원된 세션) 처음 들어오는 내비 현재값 묶음은 기준선으로만 기록한다(A058 — 이미 보낸 경로를 경유지 빠진 채 다시 보내지 않게).
 *
 * 멤버 역할: cmd `ROUTE_FOLLOW_PROPOSAL` 을 받으면
 * - **정차 중**: 문구를 읽어 주고 화면에 "따라가기/아니요" 를 띄운다. [autoDeclineMs](30초) 무응답이면 ACK accepted=false(docs/05 §4 — 기존 그대로).
 * - **주행 중**(A006/A034): 화면 버튼은 쓸 수 없으므로 [askYesNo](F16 '네/아니' 인식 체인)로 음성으로 묻는다. 네 → 수락, 아니 → 거절,
 *   답이 없거나 물을 수 없으면(음성 꺼짐·무전 중·인식기 없음) 자동 거절하지 않고 **정차할 때까지 보류**([RouteProposal.heldUntilParked])한다 —
 *   정차하면 다시 읽고 카드에서 30초 안에 고른다. 정차 없이 [holdMaxMs](10분)가 지나면 ACK false.
 *   정차 중에 받은 제안에 답하기 전에 출발해도 같은 보류로 넘어간다.
 * - 수락: 경유지가 있으면 `requestRouteWithWaypoints`, 없으면 `requestRoute` (docs/10 R5) → ACK accepted=true.
 * - 설정 "자동 따라가기" 가 켜져 있으면 묻지 않고 바로 수락한다. 이 차가 방장이면 제안을 무시한다(A069 — 늦게 온 옛 제안).
 * - 새 제안이 대기 중인 서버 제안을 밀어내면 밀린 쪽에 ACK false 를 보낸다(A057). 수락·거절·만료는 대기 제안을 원자적으로 꺼낸 쪽만 처리한다.
 *
 * 합류 직후(T30, docs/09 §4 장면 3): 멤버로 그룹에 막 들어오면 같은 카드·음성으로 "선두차 목적지 OO까지 안내할까요?" 를 묻고,
 * 수락 시 `requestRoute(그룹 목적지)`. 서버 제안이 아니므로 ACK 는 없다. 앱 재시작으로 복원된 세션([joinWindowMs] 이 지난 것)에는 다시 묻지 않는다.
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
    /** 합류 직후 선두 목적지 안내 제안(T30). 테스트에서 끈다 */
    private val proposeDestinationOnJoin: Boolean = true,
    private val joinWindowMs: Long = JOIN_WINDOW_MS,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clock: () -> Long = System::currentTimeMillis,
    /** 주행 여부(DrivingStateMonitor). null = 늘 정차(기존 동작) */
    private val drivingFlow: Flow<Boolean>? = null,
    /**
     * 주행 중 음성 질문(A034) — 질문을 읽고 '네/아니'를 듣는다. null 을 돌려주면 '물을 수 없었다'(질문도 읽지 않음 — 엔진이 읽고 보류한다).
     * 배선(di/SignalsModule): 음성 안내 켜짐·무전 쉬는 중·인식 단계가 있을 때만 VoiceAdapter.speakAndWait → RecognizerChain.listen.
     */
    private val askYesNo: (suspend (question: String) -> VoiceAnswer?)? = null,
    private val holdMaxMs: Long = HOLD_MAX_MS,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var job: Job? = null

    @Volatile
    private var expiryJob: Job? = null

    @Volatile
    private var askJob: Job? = null
    private var debounceJob: Job? = null

    private val _state = MutableStateFlow(RouteSyncState())
    val state: StateFlow<RouteSyncState> = _state

    /** 마지막으로 안 주행 상태 */
    @Volatile
    private var driving = false

    // 선두차: 마지막으로 안 내비 목적지·경유지와 마지막으로 보낸 내용
    private var naviDestination: Place? = null
    private var naviWaypoints: List<Place> = emptyList()
    private var lastSent: RouteChanged? = null

    /** 엔진 시작 시각 — 이보다 먼저 합류한 세션 = 앱 재시작으로 복원된 세션(A058) */
    private var startedAtMs = 0L

    /** 따라가는(수락·발행한) 경유지 — 내비 경유지 목록과 맞춰 '다음 정차'를 고친다(A056) */
    @Volatile
    private var followedWaypoints: List<Place> = emptyList()

    @Volatile
    private var naviHadFollowed = false

    fun setAutoFollow(enabled: Boolean) {
        _state.update { it.copy(autoFollow = enabled) }
        DhLog.i("routesync: autoFollow=$enabled")
        if (enabled) _state.value.pending?.let { scope.launch { accept() } }
    }

    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        startedAtMs = clock()
        _state.update { it.copy(running = true) }
        job = scope.launch {
            launch { proposals.collect { onProposal(it) } }
            launch { naviEvents.collect { onNavi(it) } }
            drivingFlow?.let { flow -> launch { flow.collect { onDriving(it) } } }
            launch {
                var lastGroupId: String? = null
                session.collect { s ->
                    if (s == null) {
                        // 그룹이 끝나면 대기 중 제안은 의미 없다(ACK 없음)
                        cancelTimers()
                        _state.update { it.copy(pending = null, activeWaypoint = null, followedDestination = null) }
                        lastSent = null
                        lastGroupId = null
                        followedWaypoints = emptyList()
                        naviHadFollowed = false
                        return@collect
                    }
                    if (s.groupId == lastGroupId) return@collect
                    if (lastGroupId != null) {
                        _state.update { it.copy(activeWaypoint = null, followedDestination = null) }
                        followedWaypoints = emptyList()
                        naviHadFollowed = false
                    }
                    lastGroupId = s.groupId
                    // T30: 멤버로 막 합류했으면(복원된 세션 제외) 선두 목적지 안내를 제안한다
                    val justJoined = (clock() - s.joinedAtMs) in 0..joinWindowMs
                    if (proposeDestinationOnJoin && !s.isLeader && justJoined) proposeLeaderDestination(s)
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel(); job = null
        cancelTimers(); debounceJob?.cancel()
        _state.update { it.copy(running = false, pending = null) }
    }

    private fun cancelTimers() {
        expiryJob?.cancel()
        expiryJob = null
        askJob?.cancel()
        askJob = null
    }

    // ---------------------------------------------------------------- 멤버 역할

    /** 제안 수신 (테스트에서 직접 부를 수 있다) */
    suspend fun onProposal(p: ReceivedProposal) {
        val s = session.value
        if (s == null) {
            DhLog.i("routesync: proposal ignored (no group)")
            return
        }
        if (s.isLeader) {
            // A069: 방장이 된 차에 늦게 온 옛 제안(승계 직전 발행) — 자기 경로를 바꾸지 않는다
            DhLog.i("routesync: proposal ${TextMasking.maskVehicleId(p.cmd.proposalId)} ignored (this car is the leader)")
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
        DhLog.i("routesync: proposal ${TextMasking.maskVehicleId(proposal.proposalId)} dest=${proposal.destination.toLogString()} waypoints=${proposal.waypoints.size} autoFollow=${_state.value.autoFollow} driving=$driving")
        offer(proposal)
    }

    /** 제안을 대기 상태로 올리고(이전 제안 대체) 묻는다. 자동 따라가기면 바로 수락 */
    private suspend fun offer(proposal: RouteProposal) {
        cancelTimers()
        val previous = _state.getAndUpdate { it.copy(pending = proposal) }.pending
        if (previous != null && !previous.local && previous.proposalId != proposal.proposalId) {
            // A057: 밀려난 서버 제안도 답(ACK false)으로 끝낸다 — 서버 집계가 열린 채 남지 않게
            val sent = publishAck(Ack(proposalId = previous.proposalId, accepted = false))
            _state.update { it.copy(superseded = it.superseded + 1) }
            DhLog.i("routesync: superseded ${TextMasking.maskVehicleId(previous.proposalId)} ack=$sent")
        }
        if (_state.value.autoFollow) {
            accept()
            return
        }
        if (driving) askWhileDriving(proposal) else askParked(proposal, speakIt = true)
    }

    /** 정차 중(기존 동작): 읽고 카드 — [autoDeclineMs] 뒤 자동 거절 */
    private fun askParked(proposal: RouteProposal, speakIt: Boolean) {
        if (speakIt) speak(proposal.speech, Priority.NORMAL)
        val id = proposal.proposalId
        expiryJob = scope.launch {
            delay(autoDeclineMs)
            declineIf(id, auto = true)
        }
    }

    /** A034: 주행 중 — 음성으로 묻고, 답이 없으면 정차까지 보류 */
    private fun askWhileDriving(proposal: RouteProposal) {
        val id = proposal.proposalId
        val ask = askYesNo
        askJob = scope.launch {
            val answer = if (ask == null) {
                null
            } else {
                try {
                    ask("${proposal.speech} $ASK_SUFFIX")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DhLog.w("routesync: voice ask failed ${e.javaClass.simpleName}")
                    null
                }
            }
            // 이 질문이 아직 대기 중일 때만(그 사이 화면에서 답했거나 새 제안이 왔으면 끝)
            if (_state.value.pending?.proposalId != id) return@launch
            if (askJob === coroutineContext[Job]) askJob = null
            when (answer) {
                VoiceAnswer.YES -> {
                    val p = take(id) ?: return@launch
                    _state.update { it.copy(voiceAnswered = it.voiceAnswered + 1) }
                    speak(SPEECH_FOLLOWING, Priority.NORMAL)
                    acceptTaken(p)
                }
                VoiceAnswer.NO -> {
                    val p = take(id) ?: return@launch
                    _state.update { it.copy(voiceAnswered = it.voiceAnswered + 1) }
                    speak(SPEECH_NOT_FOLLOWING, Priority.NORMAL)
                    finishDecline(p, auto = false, held = false)
                }
                VoiceAnswer.NO_ANSWER, null -> {
                    // 물을 수 없었으면 질문을 그대로 읽어 준다(예전처럼) — 그 뒤 '정차하면 다시'
                    if (answer == null) speak(proposal.speech, Priority.NORMAL)
                    if (driving) {
                        hold(id)
                    } else {
                        // 듣는 사이 정차했다 — 지금부터 카드로 30초
                        val refreshed = proposal.copy(expiresAtMs = clock() + autoDeclineMs)
                        _state.update { s -> if (s.pending?.proposalId == id) s.copy(pending = refreshed) else s }
                        askParked(refreshed, speakIt = false)
                    }
                }
            }
        }
    }

    /** 정차할 때까지 보류 — [holdMaxMs] 가 지나면 자동 거절 */
    private fun hold(id: String) {
        val now = clock()
        var held = false
        _state.update {
            val p = it.pending
            if (p?.proposalId == id && !p.heldUntilParked) {
                held = true
                it.copy(pending = p.copy(heldUntilParked = true, expiresAtMs = now + holdMaxMs), held = it.held + 1)
            } else {
                it
            }
        }
        if (!held) return
        speak(SPEECH_HOLD, Priority.LOW)
        DhLog.i("routesync: proposal ${TextMasking.maskVehicleId(id)} held until parked (max ${holdMaxMs / 60_000}m)")
        expiryJob?.cancel()
        expiryJob = scope.launch {
            delay(holdMaxMs)
            if (declineIf(id, auto = true, held = true)) speak(SPEECH_HOLD_EXPIRED, Priority.LOW)
        }
    }

    private fun onDriving(isDriving: Boolean) {
        val was = driving
        driving = isDriving
        if (was == isDriving) return
        val p = _state.value.pending ?: return
        if (isDriving) {
            // 정차 중 카드에 답하기 전에 출발했다 — 30초 자동 거절 대신 정차까지 보류(다시 묻지 않는다: 방금 읽었다)
            if (!p.heldUntilParked && askJob?.isActive != true) hold(p.proposalId)
        } else if (p.heldUntilParked) {
            // A006: 정차 → 다시 보이고 다시 읽는다. 지금부터 30초 안에 카드로 고른다(docs/05 §4 '정차 시 다시 노출')
            val now = clock()
            var reexposed: RouteProposal? = null
            _state.update {
                val cur = it.pending
                if (cur?.proposalId == p.proposalId && cur.heldUntilParked) {
                    reexposed = cur.copy(heldUntilParked = false, expiresAtMs = now + autoDeclineMs)
                    it.copy(pending = reexposed)
                } else {
                    it
                }
            }
            val r = reexposed ?: return
            DhLog.i("routesync: parked → held proposal ${TextMasking.maskVehicleId(r.proposalId)} re-exposed")
            expiryJob?.cancel()
            askParked(r, speakIt = true)
        }
    }

    /** T30: 합류 직후 "선두차 목적지까지 안내할까요?" — 서버 제안이 아니므로 ACK 없이 내비만 요청한다 */
    private suspend fun proposeLeaderDestination(s: GroupSession) {
        val now = clock()
        val proposal = RouteProposal(
            proposalId = "local-join-${s.groupId}",
            destination = s.destination,
            waypoints = emptyList(),
            speech = joinSpeech(s.destination.name),
            receivedAtMs = now,
            expiresAtMs = now + autoDeclineMs,
            local = true,
        )
        DhLog.i("routesync: join proposal dest=${s.destination.toLogString()} autoFollow=${_state.value.autoFollow}")
        offer(proposal)
    }

    private fun joinSpeech(name: String): String =
        if (name.isBlank()) "선두차 목적지까지 안내할까요?" else "선두차 목적지 ${name}까지 안내할까요?"

    suspend fun accept() {
        // A057: 대기 제안을 원자적으로 꺼낸 쪽만 처리한다(화면 탭과 자동 거절이 겹쳐도 한 번)
        val p = _state.getAndUpdate { it.copy(pending = null) }.pending ?: return
        acceptTaken(p)
    }

    /** [id] 가 아직 대기 중이면 꺼낸다(원자적) */
    private fun take(id: String): RouteProposal? =
        _state.getAndUpdate { if (it.pending?.proposalId == id) it.copy(pending = null) else it }.pending?.takeIf { it.proposalId == id }

    private suspend fun acceptTaken(p: RouteProposal) {
        expiryJob?.cancel()
        val ok = runCatching {
            if (p.waypoints.isEmpty()) navi.requestRoute(p.destination) else navi.requestRouteWithWaypoints(p.destination, p.waypoints)
        }.onFailure { DhLog.w("routesync: 내비 경로 요청 실패 ${it.javaClass.simpleName}") }.isSuccess
        val sent = if (p.local) false else publishAck(Ack(proposalId = p.proposalId, accepted = true))
        val label = if (p.local) "수락(선두 목적지 안내)" else "수락 ${TextMasking.maskVehicleId(p.proposalId)}"
        followedWaypoints = p.waypoints
        naviHadFollowed = false
        _state.update {
            it.copy(
                accepted = it.accepted + 1, lastResult = "$label navi=${if (ok) "요청" else "실패"}${if (p.local) "" else " ack=$sent"}",
                activeWaypoint = p.waypoints.firstOrNull(), followedDestination = p.destination,
            )
        }
        DhLog.i("routesync: accepted ${TextMasking.maskVehicleId(p.proposalId)} local=${p.local} navi=$ok ack=$sent")
    }

    fun decline(auto: Boolean = false) {
        val p = _state.getAndUpdate { it.copy(pending = null) }.pending ?: return
        finishDecline(p, auto, held = false)
    }

    /** [id] 가 아직 대기 중일 때만 거절(만료 타이머용). 거절했으면 true */
    private fun declineIf(id: String, auto: Boolean, held: Boolean = false): Boolean {
        val p = take(id) ?: return false
        finishDecline(p, auto, held)
        return true
    }

    private fun finishDecline(p: RouteProposal, auto: Boolean, held: Boolean) {
        expiryJob?.cancel()
        val sent = if (p.local) false else publishAck(Ack(proposalId = p.proposalId, accepted = false))
        val ackText = if (p.local) "" else " ack=$sent"
        _state.update {
            when {
                auto && held -> it.copy(autoDeclined = it.autoDeclined + 1, lastResult = "자동 거절(정차 없이 ${holdMaxMs / 60_000}분) ${TextMasking.maskVehicleId(p.proposalId)}$ackText")
                auto -> it.copy(autoDeclined = it.autoDeclined + 1, lastResult = "자동 거절(30초 무응답) ${TextMasking.maskVehicleId(p.proposalId)}$ackText")
                else -> it.copy(declined = it.declined + 1, lastResult = "거절 ${TextMasking.maskVehicleId(p.proposalId)}$ackText")
            }
        }
        DhLog.i("routesync: declined auto=$auto held=$held ${TextMasking.maskVehicleId(p.proposalId)} local=${p.local} ack=$sent")
    }

    // ---------------------------------------------------------------- 선두차 역할

    private fun onNavi(e: NaviEvent) {
        when (e) {
            is NaviEvent.DestinationChanged -> naviDestination = e.destination
            is NaviEvent.WaypointChanged -> {
                naviWaypoints = e.waypoints
                syncActiveWaypoint(e.waypoints)
            }
            is NaviEvent.WaypointArrived -> {
                // 휴게소에 도착했으면 '다음 정차지' 는 지난 것이다
                if (_state.value.activeWaypoint != null) {
                    _state.update { it.copy(activeWaypoint = null) }
                    DhLog.i("routesync: waypoint arrived → activeWaypoint cleared")
                }
                return
            }
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

    /**
     * A056: 내비 경유지 목록에 맞춰 '다음 정차'를 고친다(역할 무관). 따라가는 경유지 중 목록에 남은 첫 것이 다음 정차지.
     * 내비가 한 번 그 경유지를 가졌다가 목록에서 뺐으면(운전자가 지움·건너뜀·지남) 비운다. 내비가 아직 모르는 경유지(요청 반영 전·시연용 테스트 발행)는 그대로 둔다.
     */
    private fun syncActiveWaypoint(list: List<Place>) {
        val followed = followedWaypoints
        if (followed.isEmpty()) return
        val inList = followed.firstOrNull { f -> list.any { near(it, f) } }
        when {
            inList != null -> {
                naviHadFollowed = true
                if (_state.value.activeWaypoint != inList) _state.update { it.copy(activeWaypoint = inList) }
            }
            naviHadFollowed && _state.value.activeWaypoint != null -> {
                _state.update { it.copy(activeWaypoint = null) }
                DhLog.i("routesync: followed waypoint left the navi route → activeWaypoint cleared")
            }
        }
    }

    private fun publishIfChanged(s: GroupSession) {
        val dest = naviDestination ?: return
        val change = RouteChanged(destination = dest, waypoints = naviWaypoints)
        // 그룹 목적지 그대로 + 경유지 없음 = 변화 아님(구독 직후 현재 목적지 콜백)
        if (naviWaypoints.isEmpty() && samePlace(dest, s.destination) && lastSent == null) return
        if (change == lastSent) return
        if (lastSent == null && s.joinedAtMs < startedAtMs && clock() - startedAtMs <= BASELINE_WINDOW_MS) {
            // A058: 앱 재시작 뒤 첫 내비 현재값 — 이미 보냈던 경로일 수 있다(경유지는 구독 직후 당겨 오지 않아 빠져 있을 수 있음). 기준선으로만
            lastSent = change
            DhLog.i("routesync: restored session — first navi route recorded as baseline (not published) waypoints=${change.waypoints.size}")
            return
        }
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
        if (sent) {
            lastSent = change
            followedWaypoints = change.waypoints
            naviHadFollowed = false
        }
        val summary = "dest=${change.destination.name} waypoints=${change.waypoints.joinToString { it.name }}"
        _state.update {
            it.copy(
                routeChangedSent = if (sent) it.routeChangedSent + 1 else it.routeChangedSent, lastRouteChangedAtMs = clock(), lastRouteChangedSummary = summary,
                activeWaypoint = if (sent) change.waypoints.firstOrNull() else it.activeWaypoint,
                followedDestination = if (sent) change.destination else it.followedDestination,
            )
        }
        DhLog.i("routesync: ROUTE_CHANGED published=$sent ${change.destination.toLogString()} waypoints=${change.waypoints.size}")
        return sent
    }

    private fun samePlace(a: Place, b: Place): Boolean =
        kotlin.math.abs(a.lat - b.lat) < 1e-5 && kotlin.math.abs(a.lon - b.lon) < 1e-5

    /** 내비가 좌표를 조금 다듬어 돌려줘도 같은 경유지로 본다(약 10 m) */
    private fun near(a: Place, b: Place): Boolean =
        kotlin.math.abs(a.lat - b.lat) < 1e-4 && kotlin.math.abs(a.lon - b.lon) < 1e-4

    companion object {
        const val AUTO_DECLINE_MS = 30_000L
        const val DEBOUNCE_MS = 2_000L
        /** 합류 뒤 이 시간 안의 세션만 "막 합류"로 본다(앱 재시작 복원 세션 제외) */
        const val JOIN_WINDOW_MS = 60_000L

        /** 주행 중 보류한 제안을 정차 없이 기다리는 상한(A006) */
        const val HOLD_MAX_MS = 10 * 60_000L

        /** 앱 시작 뒤 이 시간 안의 첫 내비 묶음만 기준선으로 본다(A058) */
        const val BASELINE_WINDOW_MS = 30_000L

        const val ASK_SUFFIX = "네 또는 아니요로 답해 주세요."
        const val SPEECH_FOLLOWING = "선두 경로로 안내할게요."
        const val SPEECH_NOT_FOLLOWING = "지금 경로를 유지할게요."
        const val SPEECH_HOLD = "정차하면 다시 물어볼게요."
        const val SPEECH_HOLD_EXPIRED = "선두 경로 제안은 넘어갈게요."

        /** 시연용 경유지(공공장소 근방 좌표) */
        val DEMO_WAYPOINT = Place(lat = 37.2963, lon = 127.4120, name = "덕평휴게소", poiId = null, address = null)
    }
}
