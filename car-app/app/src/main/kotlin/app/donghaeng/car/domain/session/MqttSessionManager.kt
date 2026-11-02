package app.donghaeng.car.domain.session

import app.donghaeng.car.core.common.DhLog
import app.donghaeng.car.core.common.JwtClaims
import app.donghaeng.car.core.common.StoredCredentials
import app.donghaeng.car.core.common.TextMasking
import app.donghaeng.car.core.common.TokenClearReason
import app.donghaeng.car.core.common.TokenStore
import app.donghaeng.car.core.common.TokenStoreState
import app.donghaeng.car.core.network.mqtt.InboundMessage
import app.donghaeng.car.core.network.mqtt.MqttAuthFailure
import app.donghaeng.car.core.network.mqtt.MqttGateway
import app.donghaeng.car.core.network.mqtt.MqttState
import app.donghaeng.car.domain.pairing.RefreshOutcome
import app.donghaeng.car.domain.pairing.RefreshTrigger
import app.donghaeng.car.domain.pairing.TokenRefresher
import app.donghaeng.protocol.CmdType
import app.donghaeng.protocol.DhJson
import app.donghaeng.protocol.GroupEnded
import app.donghaeng.protocol.GroupEvent
import app.donghaeng.protocol.GroupEventType
import app.donghaeng.protocol.GroupJoined
import app.donghaeng.protocol.GroupState
import app.donghaeng.protocol.MessageTypes
import app.donghaeng.protocol.RouteFollowProposal
import app.donghaeng.protocol.Topics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** 진단 화면용 — 마지막으로 받은 cmd 요약(민감 정보 없음) */
data class LastCommand(val type: String, val groupIdMasked: String?, val atMs: Long)

/** 수신 시각이 붙은 경로 따라가기 제안 (T28) */
data class ReceivedProposal(val cmd: RouteFollowProposal, val receivedAtMs: Long)

/** 봉투 ts(서버 발행 시각)·수신 시각이 붙은 그룹 이벤트 */
data class ReceivedGroupEvent(val event: GroupEvent, val envelopeTsMs: Long, val receivedAtMs: Long)

/** 그룹 STATE 스냅샷의 봉투 ts(서버 발행)·수신 시각 — T31 지연 측정용 */
data class ReceivedGroupState(val envelopeTsMs: Long, val receivedAtMs: Long, val members: Int)

/** 서버와 소속 맞추기(reconcile, A000/A001) — 진단용. 결과는 짧은 영어 한 줄(민감 정보 없음) */
data class ReconcileState(val count: Int = 0, val lastAtMs: Long? = null, val lastTrigger: String? = null, val lastResult: String? = null)

/** 토큰 저장소 읽기 재시도 상태 (T33) — 진단·홈 배너용 */
data class StoreRetryState(val attempts: Int = 0, val maxAttempts: Int = MqttSessionManager.STORE_RETRY_MAX, val exhausted: Boolean = false)

/**
 * 페어링 상태 ↔ MQTT 연결 ↔ 그룹 세션을 잇는다 (T16; 호출 순서는 docs/03 §4·§5.5).
 *
 * - [TokenStore] 에 자격 증명이 있으면 [MqttGateway.connect] + 자기 `cmd` 토픽(+ 저장된 그룹 토픽) 구독. **Empty**(연결 해제)면 끊고 그룹 세션도
 *   [SessionClearReason.UNPAIRED] 로 지운다. **Unavailable**(저장소 읽기 실패, T33)은 미페어링이 아니다 — 세션을 지우지 않고 연결도 하지 않으며
 *   [STORE_RETRY_INTERVAL_MS](30 s) 마다 최대 [STORE_RETRY_MAX] 회 다시 읽는다(docs/10 R13).
 * - cmd `GROUP_JOINED` → [GroupSessionStore] 에 저장하고 그룹 `state/event` 구독. `GROUP_ENDED` 또는 내 차의 그룹 이벤트 `MEMBER_LEFT`(폰이 내보냄) → 세션 삭제·구독 해제.
 *   `TOKEN_REVOKED` → 자격 증명 삭제([TokenClearReason.TOKEN_REVOKED_CMD]) — 단 **지금 토큰보다 먼저 발행된 것**(봉투 `ts`(epoch ms)를 초로 내린 값 ≤ JWT `iat`(초),
 *   둘 다 서버 시계)은 재페어링 전 MQTT 영속 세션에 쌓였다 늦게 온 것이라 무시한다. 같은 초도 무시하므로 해제 직전 같은 초에 갱신된 토큰은 남을 수 있다(docs/03 §5.5).
 * - 브로커 인증 거부([MqttGateway.authFailures]) → [TokenRefresher] 로 갱신. 성공하면 저장소가 새 토큰을 내고, 그 토큰으로 [MqttGateway.connect] 가 즉시 다시 붙는다.
 *   회전한 토큰도 거부되면 갱신기가 MQTT 발 회전을 1→2→4…→30분 백오프한다 — 접속에 성공하면([TokenRefresher.onMqttConnected]) 처음부터.
 * - 그룹 토픽 수신은 [groupEvents]/[groupState] 로 내보낸다(T25 주행 화면·T27 음성이 구독).
 * - 스냅샷의 내 차 닉네임이 저장값과 다르면(폰에서 이름을 바꿈) [TokenStore] 의 닉네임만 바꾼다 — 홈·설정·그룹 기본 이름이 따라온다.
 *
 * - **reconcile**(A000/A001, docs/03 계약 1): MQTT 가 (다시) 붙을 때마다 차량 JWT 로 `GET /v1/vehicles/me/group` 을 불러 로컬 세션을 서버와 맞춘다 —
 *   영속 세션(300 s)보다 오래 꺼져 있던 동안 놓친 `GROUP_JOINED`(전날 합류)·`GROUP_ENDED`·`MEMBER_LEFT` 를 따라잡는다. 서버가 그 경로를 모르면(404·405)
 *   저장된 그룹만 `GET /groups/{id}` 로 확인해 끝났거나 내가 멤버가 아니면 지운다. 네트워크 실패면 아무것도 바꾸지 않는다(다음 연결 때 다시).
 *   요청하는 동안 세션이 바뀌었으면(cmd·생성·나가기) 결과를 버린다. 그룹 토픽 구독이 거듭 거부돼도([MqttGateway.subscriptionDenied]) 다시 맞춘다.
 * - 방장 갱신(A004): 그룹 이벤트 `LEADER_CHANGED` 와 스냅샷의 `leaderVehicleId` 로 세션의 방장·[GroupSession.isLeader] 를 맞춘다(방장이 아니게 되면 초대 삭제).
 *   같은 그룹의 `GROUP_JOINED` 를 다시 받아도 세션(합류 시각·초대)을 유지하고 방장만 맞춘다.
 * - 그룹 이벤트 `GROUP_ENDED` 도 세션 정리 신호로 쓴다(cmd 만 놓친 경우). 그룹이 바뀌거나 끝나면 [groupEvents] 의 replay 를 비운다(A010).
 *
 * 앱 프로세스가 살아 있는 동안 페어링만 돼 있으면 연결을 유지한다 — 폰이 이 차를 그룹에 넣는 순간(`GROUP_JOINED`)을 받으려면
 * 그룹 전에도 cmd 를 듣고 있어야 하기 때문이다(T18). 백그라운드 지속은 T23 의 DriveSessionService 가 맡는다.
 * 코루틴 예외는 [CoroutineExceptionHandler] 로 로그만 남기고, 메시지 하나의 처리 실패가 수신 루프를 끊지 않게 한다.
 */
@Singleton
class MqttSessionManager @Inject constructor(
    private val tokenStore: TokenStore,
    private val gateway: MqttGateway,
    private val sessionStore: GroupSessionStore,
    private val refresher: TokenRefresher,
    /** 서버 소속 조회(reconcile). 기본값 = 조회하지 않음(테스트) — 앱은 MqttModule 이 REST 구현을 준다 */
    private val membership: GroupMembershipSource = GroupMembershipSource.DISABLED,
) {
    private val started = AtomicBoolean(false)
    private lateinit var scope: CoroutineScope

    /** 세션을 읽고-바꾸는 구간(cmd·reconcile·방장 갱신·생성·나가기)을 한 줄로 세운다. 안에서 다시 잡지 않는다 */
    private val sessionMutex = Mutex()
    private val reconcileMutex = Mutex()

    private val _reconcile = MutableStateFlow(ReconcileState())
    val reconcileState: StateFlow<ReconcileState> = _reconcile.asStateFlow()

    val session: StateFlow<GroupSession?> get() = sessionStore.session

    /** 마지막 세션 삭제 기록(사유·시각) — 화면이 '왜 그룹이 끝났는지'를 고른다(A115) */
    val lastSessionClear: StateFlow<app.donghaeng.car.core.common.ClearRecord?> get() = sessionStore.lastClear

    private val _groupEvents = MutableSharedFlow<GroupEvent>(replay = 20, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val groupEvents: SharedFlow<GroupEvent> = _groupEvents.asSharedFlow()

    /** 수신 시각이 붙은 그룹 이벤트(replay 없음) — 음성 안내(T27)의 신선도 판정용 */
    private val _groupEventsReceived = MutableSharedFlow<ReceivedGroupEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val groupEventsReceived: SharedFlow<ReceivedGroupEvent> = _groupEventsReceived.asSharedFlow()

    /** 현재 접속에 쓰는 내 vehicleId (페어링 전 null) */
    val myVehicleId: String? get() = vehicleId

    /** cmd `ROUTE_FOLLOW_PROPOSAL` (T28 RouteSyncEngine 이 구독) */
    private val _proposals = MutableSharedFlow<ReceivedProposal>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val proposals: SharedFlow<ReceivedProposal> = _proposals.asSharedFlow()

    private val _groupState = MutableStateFlow<GroupState?>(null)
    val groupState: StateFlow<GroupState?> = _groupState.asStateFlow()

    /** 마지막 STATE 수신 시각(epoch ms)·누적 수 — 진단용 (T24) */
    private val _groupStateAt = MutableStateFlow<Long?>(null)
    val groupStateAt: StateFlow<Long?> = _groupStateAt.asStateFlow()

    /** STATE 수신 봉투 ts·수신 시각 (T31 LatencyMeter 가 구독) */
    private val _groupStatesReceived = MutableSharedFlow<ReceivedGroupState>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val groupStatesReceived: SharedFlow<ReceivedGroupState> = _groupStatesReceived.asSharedFlow()
    private var stateCount = 0

    private val _lastCommand = MutableStateFlow<LastCommand?>(null)
    val lastCommand: StateFlow<LastCommand?> = _lastCommand.asStateFlow()

    /** 토큰 저장소 Unavailable 재시도 (T33) */
    private val _storeRetry = MutableStateFlow(StoreRetryState())
    val storeRetry: StateFlow<StoreRetryState> = _storeRetry.asStateFlow()
    private var storeRetryJob: Job? = null

    @Volatile
    private var vehicleId: String? = null

    /** 한 번만 시작한다(Application.onCreate). [dispatcher] 는 테스트에서 바꿔 끼운다 */
    fun start(dispatcher: CoroutineDispatcher = Dispatchers.Default) {
        if (!started.compareAndSet(false, true)) return
        val handler = CoroutineExceptionHandler { _, e -> DhLog.e("mqtt session manager coroutine failed", e) }
        scope = CoroutineScope(SupervisorJob() + dispatcher + handler)
        scope.launch {
            // 세션 저장소가 깨져도 토큰 처리는 계속돼야 한다
            guarded("group session load") { sessionStore.load() }
            guarded("token store load") { tokenStore.load() }
            // 상태 객체를 문자열에 넣지 않는다 — Present 에는 자격 증명이 들어 있다(규칙 8, T33 리뷰). 종류 이름만
            tokenStore.state.collect { state -> guarded("token state ${state::class.simpleName}") { onTokenState(state) } }
        }
        scope.launch { gateway.inbound.collect { message -> guarded("inbound ${message.envelope.type}") { onInbound(message) } } }
        scope.launch { gateway.authFailures.collect { failure -> guarded("auth failure") { onAuthFailure(failure) } } }
        // 접속 성공 = 지금 토큰이 브로커에 통했다 → MQTT 발 토큰 회전 백오프를 처음부터(T33 리뷰).
        // connectedAtMs 를 함께 봐서 RECONNECTING 이 합쳐져(StateFlow conflation) 안 보여도 새 접속은 놓치지 않는다
        scope.launch {
            gateway.status.map { (it.state == MqttState.CONNECTED) to it.connectedAtMs }.distinctUntilChanged().collect { (connected, _) ->
                if (connected) {
                    guarded("mqtt connected") { refresher.onMqttConnected() }
                    // A000/A001: (재)접속 = 서버에 닿는다 → 꺼져 있던 동안 놓친 그룹 변화를 맞춘다(실패해도 다음 접속 때 다시)
                    requestReconcile("mqtt-connected")
                }
            }
        }
        // A018: 그룹 토픽 구독이 거듭 거부되면 '이 차가 멤버가 아님'일 수 있다 → 서버와 소속을 다시 맞춘다
        scope.launch {
            gateway.subscriptionDenied.collect { topic ->
                val s = sessionStore.session.value
                if (s != null && topic in s.subscribeTopics) requestReconcile("subscribe-denied")
            }
        }
        DhLog.i("mqtt session manager started")
    }

    /** 홈 '다시 시도'(저장소 오류 배너) — 재시도 횟수를 처음부터 센다 */
    fun retryTokenStore() {
        if (!started.get()) return
        storeRetryJob?.cancel()
        storeRetryJob = null
        _storeRetry.value = StoreRetryState()
        scope.launch {
            guarded("token store retry") { tokenStore.load() }
            if (tokenStore.state.value is TokenStoreState.Unavailable) scheduleStoreRetry()
        }
    }

    private suspend fun onTokenState(state: TokenStoreState) {
        when (state) {
            TokenStoreState.Loading -> Unit
            is TokenStoreState.Unavailable -> {
                // 읽기 실패는 미페어링이 아니다 — 세션은 그대로, 연결만 하지 않는다
                if (vehicleId != null) {
                    vehicleId = null
                    gateway.disconnect()
                }
                DhLog.w("token store unavailable reason=${state.reason} → keep group session, retry every ${STORE_RETRY_INTERVAL_MS / 1000}s")
                scheduleStoreRetry()
            }
            TokenStoreState.Empty -> {
                cancelStoreRetry()
                val previousVehicleId = vehicleId
                vehicleId = null
                sessionMutex.withLock {
                    val current = sessionStore.session.value
                    // 끊기 **전에** 그룹 토픽 구독을 푼다(A070) — 끊긴 뒤 푼 토픽은 로컬 목록에서만 빠지고 브로커 영속 세션에 남는다.
                    // 게이트웨이는 구독 목록을 기억해 다음 접속 때 다시 건다 — 다른 차량으로 다시 페어링될 수 있으니 옛 cmd 토픽도 뺀다
                    if (current != null) gateway.unsubscribe(current.subscribeTopics)
                    if (previousVehicleId != null) {
                        gateway.unsubscribe(listOf(Topics.vehicleCmd(previousVehicleId)))
                        gateway.disconnect()
                    }
                    if (current != null) {
                        clearSessionLocked(SessionClearReason.UNPAIRED)
                        DhLog.i("group session cleared (unpaired, last token clear=${tokenStore.lastClear.value?.reason ?: "?"})")
                    }
                }
            }
            is TokenStoreState.Present -> {
                cancelStoreRetry()
                val creds = state.credentials
                vehicleId = creds.vehicleId
                val topics = buildList {
                    add(Topics.vehicleCmd(creds.vehicleId))
                    sessionStore.session.value?.let { addAll(it.subscribeTopics) }
                }
                gateway.subscribe(topics)
                gateway.connect(creds)
            }
        }
    }

    private fun scheduleStoreRetry() {
        if (storeRetryJob?.isActive == true || _storeRetry.value.exhausted) return
        storeRetryJob = scope.launch {
            while (_storeRetry.value.attempts < STORE_RETRY_MAX) {
                delay(STORE_RETRY_INTERVAL_MS)
                if (tokenStore.state.value !is TokenStoreState.Unavailable) return@launch
                val attempt = _storeRetry.value.attempts + 1
                _storeRetry.value = _storeRetry.value.copy(attempts = attempt)
                DhLog.i("token store retry #$attempt/$STORE_RETRY_MAX")
                guarded("token store retry") { tokenStore.load() }
                if (tokenStore.state.value !is TokenStoreState.Unavailable) return@launch
            }
            _storeRetry.value = _storeRetry.value.copy(exhausted = true)
            DhLog.w("token store still unavailable after $STORE_RETRY_MAX retries — 홈 '다시 시도' 또는 설정 '저장소 초기화'")
        }
    }

    private fun cancelStoreRetry() {
        storeRetryJob?.cancel()
        storeRetryJob = null
        if (_storeRetry.value != StoreRetryState()) _storeRetry.value = StoreRetryState()
    }

    private suspend fun onAuthFailure(failure: MqttAuthFailure) {
        if (tokenStore.state.value !is TokenStoreState.Present) return
        val outcome = refresher.refreshAfterRejection(failure.accessToken, RefreshTrigger.MQTT_AUTH)
        // Throttled(백오프 중)는 재연결마다 오므로 줄을 남기지 않는다 — 게이트웨이 'mqtt auth rejected' 줄과 갱신기의 백오프 줄(구간마다 한 번)로 충분
        if (outcome !is RefreshOutcome.Throttled) DhLog.i("mqtt auth rejected reason=${failure.reasonCode} → token refresh $outcome")
        // Refreshed: 저장소가 새 토큰을 냈고 onTokenState → gateway.connect(새 토큰) 이 대기 중인 재연결을 바로 연다.
        // Rejected: 저장소가 Empty → 연결 해제·세션 정리. 나머지(Failed/Throttled)는 게이트웨이가 잠시 뒤 가진 토큰으로 다시 시도한다
    }

    private suspend fun onInbound(message: InboundMessage) {
        val vid = vehicleId ?: return
        val topic = message.topic
        val current = sessionStore.session.value
        when {
            topic == Topics.vehicleCmd(vid) -> onCommand(message)
            current != null && topic == current.topics.event && message.envelope.type == MessageTypes.EVENT -> {
                runCatching { DhJson.decodeData<GroupEvent>(message.envelope).data }
                    .onSuccess { event ->
                        _groupEvents.emit(event)
                        _groupEventsReceived.emit(ReceivedGroupEvent(event, envelopeTsMs = message.envelope.ts, receivedAtMs = message.receivedAtMs))
                        when {
                            // T30: 폰(공식 앱)·REST 가 이 차를 그룹에서 내보내면(MEMBER_LEFT, 내 차) 세션을 지운다 — GROUP_ENDED 와 같은 정리
                            event.type == GroupEventType.MEMBER_LEFT.name && event.sourceVehicleId == vid ->
                                clearIfStill(current.groupId, SessionClearReason.MEMBER_LEFT_ME, "MEMBER_LEFT (me)")
                            // A000: 그룹 이벤트 GROUP_ENDED 도 정리 신호 — cmd 만 놓친 경우(서버는 이벤트를 cmd 보다 먼저 낸다)
                            event.type == GroupEventType.GROUP_ENDED.name ->
                                clearIfStill(current.groupId, SessionClearReason.GROUP_ENDED, "GROUP_ENDED event")
                            // A004: 방장 승계·위임 — 세션의 방장을 바로 맞춘다(스냅샷도 같은 값으로 확정한다)
                            event.type == GroupEventType.LEADER_CHANGED.name -> {
                                val leader = runCatching { event.payload["leaderVehicleId"]?.jsonPrimitive?.contentOrNull }.getOrNull()
                                if (!leader.isNullOrBlank()) updateLeader(current.groupId, leader, vid, "LEADER_CHANGED")
                            }
                        }
                    }
                    .onFailure { DhLog.w("group event decode failed cause=${it.javaClass.simpleName}") }
            }
            current != null && topic == current.topics.state && message.envelope.type == MessageTypes.STATE -> {
                runCatching { DhJson.decodeData<GroupState>(message.envelope).data }
                    .onSuccess { state ->
                        _groupState.value = state
                        _groupStateAt.value = message.receivedAtMs
                        stateCount++
                        _groupStatesReceived.emit(ReceivedGroupState(envelopeTsMs = message.envelope.ts, receivedAtMs = message.receivedAtMs, members = state.members.size))
                        syncNickname(state, vid)
                        // A004: 이벤트를 놓쳤거나 앱을 다시 켠 뒤에도 스냅샷(2초마다)이 방장을 확정한다. 같으면 저장하지 않는다
                        if (state.leaderVehicleId.isNotBlank() && state.leaderVehicleId != current.leaderVehicleId) {
                            updateLeader(current.groupId, state.leaderVehicleId, vid, "state")
                        }
                        if (stateCount == 1 || stateCount % 15 == 0) {
                            DhLog.i("group state #$stateCount members=${state.members.size} online=${state.members.count { it.online }} status=${state.members.joinToString(",") { it.status.name }}")
                        }
                    }
                    .onFailure { DhLog.w("group state decode failed cause=${it.javaClass.simpleName}") }
            }
            else -> DhLog.d("inbound ignored type=${message.envelope.type}")
        }
    }

    /**
     * 내 차 닉네임을 서버 값으로 맞춘다 (2026-10-28). 닉네임은 페어링 때 한 번 받아 [TokenStore] 에 저장하는데,
     * 폰(`PATCH /me/vehicles/{id}`, docs/03 §1.3)에서 바꾸면 차에는 알려 주는 cmd 가 없어 홈·설정 화면과 그룹 기본 이름이 옛 값으로 남았다.
     * 그룹 스냅샷(`GroupState`, 서버가 2초마다 DB 에서 다시 읽음)에 내 차가 다른 닉네임으로 있으면 저장값만 바꾼다 —
     * 같은 차·같은 토큰이라 [MqttGateway.connect] 는 연결을 그대로 둔다. 같으면 아무것도 하지 않는다(2초마다 디스크 쓰기 없음).
     * 그룹이 없을 때 바꾼 닉네임은 다음 그룹 스냅샷에서 반영된다(시연은 debug 리시버 `nickname` 으로 바로 맞춘다).
     */
    private suspend fun syncNickname(state: GroupState, vid: String) {
        val target = nicknameFromState(state, vid, tokenStore.state.value) ?: return
        // A055: 닉네임만 저장소 잠금 안에서 바꾼다 — 읽고-덮어쓰면 그 사이 회전된 토큰을 옛 토큰으로 되돌릴 수 있다
        val updated = tokenStore.updateNickname(vid, target.nickname) ?: return
        DhLog.i("nickname synced from group state vid=${TextMasking.maskVehicleId(vid)} len=${updated.nickname.length}")
    }

    private suspend fun onCommand(message: InboundMessage) {
        val type = CmdType.entries.firstOrNull { it.name == message.envelope.type }
        if (type == null) {
            DhLog.w("unknown cmd type=${message.envelope.type}")
            return
        }
        when (type) {
            CmdType.GROUP_JOINED -> {
                val cmd = runCatching { DhJson.decodeData<GroupJoined>(message.envelope).data }.getOrElse {
                    DhLog.w("GROUP_JOINED decode failed cause=${it.javaClass.simpleName}")
                    return
                }
                note(type, cmd.groupId)
                val vid = vehicleId ?: return
                sessionMutex.withLock { applyServerGroupLocked(cmd, vid, "GROUP_JOINED") }
            }
            CmdType.GROUP_ENDED -> {
                val cmd = runCatching { DhJson.decodeData<GroupEnded>(message.envelope).data }.getOrNull()
                note(type, cmd?.groupId)
                sessionMutex.withLock {
                    val current = sessionStore.session.value
                    if (current != null && (cmd == null || cmd.groupId == current.groupId)) {
                        clearSessionLocked(SessionClearReason.GROUP_ENDED)
                        DhLog.i("GROUP_ENDED groupId=${TextMasking.maskVehicleId(current.groupId)} reason=${cmd?.reason} → session cleared")
                    }
                }
            }
            CmdType.TOKEN_REVOKED -> {
                note(type, null)
                val creds = (tokenStore.state.value as? TokenStoreState.Present)?.credentials
                val issuedAt = creds?.let { JwtClaims.issuedAtSeconds(it.vehicleAccessToken) }
                if (issuedAt != null && message.envelope.ts / 1000 <= issuedAt) {
                    // 재페어링 전에 발행돼 MQTT 영속 세션(같은 clientId)에 남아 있던 것 — 지금 토큰과 무관하다
                    DhLog.w("TOKEN_REVOKED ignored — issued before the current token (ts=${message.envelope.ts / 1000} ≤ iat=$issuedAt)")
                    return
                }
                DhLog.w("TOKEN_REVOKED → clearing credentials")
                tokenStore.clear(TokenClearReason.TOKEN_REVOKED_CMD)
            }
            CmdType.ROUTE_FOLLOW_PROPOSAL -> {
                note(type, null)
                val cmd = runCatching { DhJson.decodeData<RouteFollowProposal>(message.envelope).data }.getOrElse {
                    DhLog.w("ROUTE_FOLLOW_PROPOSAL decode failed cause=${it.javaClass.simpleName}")
                    return
                }
                DhLog.i("cmd ROUTE_FOLLOW_PROPOSAL proposal=${TextMasking.maskVehicleId(cmd.proposalId)} waypoints=${cmd.waypoints.size}")
                _proposals.emit(ReceivedProposal(cmd, message.receivedAtMs))
            }
            CmdType.REST_STOP_PROPOSAL -> {
                note(type, null)
                DhLog.i("cmd REST_STOP_PROPOSAL received (handled from T50)")
            }
        }
    }

    // ---------------------------------------------------------------- group 모듈이 부르는 진입점 (T17)

    /** 이 차가 그룹을 만들었다(방장). 서버는 만든 차량에 GROUP_JOINED 를 보내지 않으므로 여기서 세션을 저장하고 그룹 토픽을 구독한다 */
    suspend fun onGroupCreated(session: GroupSession) = sessionMutex.withLock {
        val previous = sessionStore.session.value
        if (previous != null && previous.groupId != session.groupId) {
            gateway.unsubscribe(previous.subscribeTopics)
            _groupEvents.resetReplayCache()
        }
        sessionStore.save(session)
        _groupState.value = null
        gateway.subscribe(session.subscribeTopics)
        DhLog.i("group session saved (leader) groupId=${TextMasking.maskVehicleId(session.groupId)}")
    }

    /**
     * 나가기·종료를 이 차가 직접 했을 때(또는 그룹 관리 화면이 상세로 유령 세션을 알아냈을 때) — 구독 해제 + 세션 삭제.
     * [expectedGroupId] 는 호출 쪽이 보고 판단한 그룹 — **잠금 안에서** 지금 세션과 다시 비교해, 그 사이 다른 그룹 GROUP_JOINED 가 적용됐으면
     * 새 그룹 세션은 지우지 않는다(RA-5, [clearIfStill]).
     */
    suspend fun onGroupLeftLocally(expectedGroupId: String) =
        clearIfStill(expectedGroupId, SessionClearReason.LEFT_LOCALLY, "group session (left/ended locally)")

    // ---------------------------------------------------------------- 서버와 소속 맞추기 (A000/A001)

    /** 백그라운드로 [reconcile] — 실패는 로그만 */
    fun requestReconcile(trigger: String) {
        if (!started.get()) return
        scope.launch { guarded("reconcile $trigger") { reconcile(trigger) } }
    }

    /**
     * 서버가 보는 이 차의 그룹과 로컬 세션을 맞춘다. 한 번에 하나만 돈다. 결과(짧은 영어)를 돌려준다 — 테스트·진단용.
     * 요청을 보낸 뒤 세션이 바뀌었으면(그 사이 cmd·생성·나가기) 결과를 적용하지 않는다(그쪽이 더 새롭다).
     */
    suspend fun reconcile(trigger: String): String = reconcileMutex.withLock {
        val vid = vehicleId ?: return@withLock "not-paired"
        val before = sessionStore.session.value
        val result = when (val m = membership.myActiveGroup()) {
            is ServerMembership.Active -> sessionMutex.withLock {
                if (sessionStore.session.value !== before) "skipped(changed)"
                else applyServerGroupLocked(m.group, vid, "reconcile")
            }
            ServerMembership.None -> sessionMutex.withLock {
                when {
                    sessionStore.session.value !== before -> "skipped(changed)"
                    before == null -> "none"
                    else -> {
                        clearSessionLocked(SessionClearReason.RECONCILED)
                        DhLog.i("reconcile: server has no active group → stale session groupId=${TextMasking.maskVehicleId(before.groupId)} cleared")
                        "cleared(none)"
                    }
                }
            }
            ServerMembership.Unsupported -> {
                if (before == null) {
                    "unsupported"
                } else {
                    // 옛 서버: 저장된 그룹만 확인한다(끝났거나 내가 멤버가 아니면 유령 세션)
                    when (membership.checkGroup(before.groupId, vid)) {
                        GroupCheck.GONE -> sessionMutex.withLock {
                            if (sessionStore.session.value !== before) {
                                "skipped(changed)"
                            } else {
                                clearSessionLocked(SessionClearReason.RECONCILED)
                                DhLog.i("reconcile: group ended or not a member → session groupId=${TextMasking.maskVehicleId(before.groupId)} cleared")
                                "cleared(gone)"
                            }
                        }
                        GroupCheck.ACTIVE_MEMBER -> "kept"
                        GroupCheck.UNKNOWN -> "unknown(detail)"
                    }
                }
            }
            is ServerMembership.Unknown -> "unknown(${m.summary})"
        }
        _reconcile.update { ReconcileState(count = it.count + 1, lastAtMs = System.currentTimeMillis(), lastTrigger = trigger, lastResult = result) }
        DhLog.i("reconcile trigger=$trigger → $result")
        result
    }

    /**
     * 서버가 알려 준 그룹([g], cmd `GROUP_JOINED` 또는 계약 1 응답)을 세션에 반영한다. [sessionMutex] 를 잡은 상태에서만.
     * 같은 그룹이면 합류 시각·초대를 유지하고 이름·방장·목적지·토픽만 맞춘다(A001 — 다시 보낸 cmd 가 방장을 강등하거나 합류 제안을 다시 띄우지 않게).
     */
    private suspend fun applyServerGroupLocked(g: GroupJoined, vid: String, source: String): String {
        val previous = sessionStore.session.value
        val amLeader = g.leaderVehicleId == vid
        if (previous != null && previous.groupId == g.groupId) {
            val merged = previous.copy(
                name = g.name, leaderVehicleId = g.leaderVehicleId, destination = g.destination, topics = g.topics,
                isLeader = amLeader, invite = if (amLeader) previous.invite else null,
            )
            if (merged == previous) return "same"
            if (merged.topics != previous.topics) {
                gateway.unsubscribe(previous.subscribeTopics)
                gateway.subscribe(merged.subscribeTopics)
            }
            sessionStore.save(merged)
            DhLog.i("$source same group groupId=${TextMasking.maskVehicleId(g.groupId)} → session updated leader=${TextMasking.maskVehicleId(g.leaderVehicleId)} isLeader=$amLeader")
            return "updated"
        }
        if (previous != null) gateway.unsubscribe(previous.subscribeTopics)
        _groupEvents.resetReplayCache()
        val session = GroupSession.from(g, System.currentTimeMillis(), vid)
        sessionStore.save(session)
        _groupState.value = null
        gateway.subscribe(session.subscribeTopics)
        DhLog.i("$source groupId=${TextMasking.maskVehicleId(g.groupId)} leader=${TextMasking.maskVehicleId(g.leaderVehicleId)} isLeader=$amLeader → subscribed group topics")
        return if (previous == null) "joined" else "switched"
    }

    /** 구독 해제 + 세션 삭제 + 스냅샷·replay 비우기. [sessionMutex] 를 잡은 상태에서만 */
    private suspend fun clearSessionLocked(reason: SessionClearReason) {
        val current = sessionStore.session.value ?: return
        gateway.unsubscribe(current.subscribeTopics)
        sessionStore.clear(reason)
        _groupState.value = null
        _groupEvents.resetReplayCache()
    }

    /** [groupId] 가 아직 지금 세션이면 지운다(그룹 이벤트 경로 — 그 사이 다른 그룹으로 바뀌었으면 그대로) */
    private suspend fun clearIfStill(groupId: String, reason: SessionClearReason, what: String) = sessionMutex.withLock {
        if (sessionStore.session.value?.groupId != groupId) return@withLock
        clearSessionLocked(reason)
        DhLog.i("$what groupId=${TextMasking.maskVehicleId(groupId)} → session cleared")
    }

    /** A004: 방장이 바뀌었다 — 세션의 방장·isLeader 를 맞춘다(방장이 아니게 되면 초대 삭제). 값이 같으면 저장하지 않는다 */
    private suspend fun updateLeader(groupId: String, leaderVehicleId: String, vid: String, source: String) = sessionMutex.withLock {
        val s = sessionStore.session.value ?: return@withLock
        if (s.groupId != groupId) return@withLock
        val amLeader = leaderVehicleId == vid
        val updated = s.copy(leaderVehicleId = leaderVehicleId, isLeader = amLeader, invite = if (amLeader) s.invite else null)
        if (updated == s) return@withLock
        sessionStore.save(updated)
        DhLog.i("leader changed ($source) groupId=${TextMasking.maskVehicleId(groupId)} leader=${TextMasking.maskVehicleId(leaderVehicleId)} isLeader=$amLeader")
    }

    private fun note(type: CmdType, groupId: String?) {
        _lastCommand.value = LastCommand(type.name, groupId?.let(TextMasking::maskVehicleId), System.currentTimeMillis())
    }

    /** 한 단계의 예외가 수집 루프 전체를 끊지 않게 — 취소는 그대로 전파 */
    private inline fun guarded(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DhLog.e("mqtt session manager: $what failed", e)
        }
    }

    companion object {
        const val STORE_RETRY_INTERVAL_MS = 30_000L
        const val STORE_RETRY_MAX = 10

        /**
         * 스냅샷의 내 차 닉네임이 저장값과 다르면 바꾼 자격 증명, 아니면 null (순수 함수 — 테스트용).
         * 저장된 차와 다른 vehicleId 이거나, 스냅샷에 내 차가 없거나, 서버 값이 비었으면(별명 대신 id 가 올 때 포함) 바꾸지 않는다.
         */
        internal fun nicknameFromState(state: GroupState, vid: String, tokens: TokenStoreState): StoredCredentials? {
            val creds = (tokens as? TokenStoreState.Present)?.credentials ?: return null
            if (creds.vehicleId != vid) return null
            val serverNick = state.members.firstOrNull { it.vehicleId == vid }?.nickname?.trim().orEmpty()
            if (serverNick.isEmpty() || serverNick == vid || serverNick == creds.nickname) return null
            return creds.copy(nickname = serverNick)
        }
    }
}
