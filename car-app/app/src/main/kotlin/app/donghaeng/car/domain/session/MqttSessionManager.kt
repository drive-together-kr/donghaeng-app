package app.donghaeng.car.domain.session

import app.donghaeng.car.core.common.DhLog
import app.donghaeng.car.core.common.JwtClaims
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
import kotlinx.coroutines.launch
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
) {
    private val started = AtomicBoolean(false)
    private lateinit var scope: CoroutineScope

    val session: StateFlow<GroupSession?> get() = sessionStore.session

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
                if (connected) guarded("mqtt connected") { refresher.onMqttConnected() }
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
                if (previousVehicleId != null) {
                    // 게이트웨이는 구독 목록을 기억해 다음 접속 때 다시 건다 — 다른 차량으로 다시 페어링될 수 있으니 옛 cmd 토픽은 뺀다
                    gateway.unsubscribe(listOf(Topics.vehicleCmd(previousVehicleId)))
                    gateway.disconnect()
                }
                val current = sessionStore.session.value
                if (current != null) {
                    gateway.unsubscribe(current.subscribeTopics)
                    sessionStore.clear(SessionClearReason.UNPAIRED)
                    _groupState.value = null
                    DhLog.i("group session cleared (unpaired, last token clear=${tokenStore.lastClear.value?.reason ?: "?"})")
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
                        // T30: 폰(공식 앱)·REST 가 이 차를 그룹에서 내보내면(MEMBER_LEFT, 내 차) 세션을 지운다 — GROUP_ENDED 와 같은 정리
                        if (event.type == GroupEventType.MEMBER_LEFT.name && event.sourceVehicleId == vid) {
                            gateway.unsubscribe(current.subscribeTopics)
                            sessionStore.clear(SessionClearReason.MEMBER_LEFT_ME)
                            _groupState.value = null
                            DhLog.i("MEMBER_LEFT (me) groupId=${TextMasking.maskVehicleId(current.groupId)} → session cleared")
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
                        if (stateCount == 1 || stateCount % 15 == 0) {
                            DhLog.i("group state #$stateCount members=${state.members.size} online=${state.members.count { it.online }} status=${state.members.joinToString(",") { it.status.name }}")
                        }
                    }
                    .onFailure { DhLog.w("group state decode failed cause=${it.javaClass.simpleName}") }
            }
            else -> DhLog.d("inbound ignored type=${message.envelope.type}")
        }
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
                val previous = sessionStore.session.value
                if (previous != null && previous.groupId != cmd.groupId) gateway.unsubscribe(previous.subscribeTopics)
                val session = GroupSession.from(cmd, System.currentTimeMillis())
                sessionStore.save(session)
                _groupState.value = null
                gateway.subscribe(session.subscribeTopics)
                note(type, cmd.groupId)
                DhLog.i("GROUP_JOINED groupId=${TextMasking.maskVehicleId(cmd.groupId)} leader=${TextMasking.maskVehicleId(cmd.leaderVehicleId)} → subscribed group topics")
            }
            CmdType.GROUP_ENDED -> {
                val cmd = runCatching { DhJson.decodeData<GroupEnded>(message.envelope).data }.getOrNull()
                val current = sessionStore.session.value
                note(type, cmd?.groupId)
                if (current != null && (cmd == null || cmd.groupId == current.groupId)) {
                    gateway.unsubscribe(current.subscribeTopics)
                    sessionStore.clear(SessionClearReason.GROUP_ENDED)
                    _groupState.value = null
                    DhLog.i("GROUP_ENDED groupId=${TextMasking.maskVehicleId(current.groupId)} reason=${cmd?.reason} → session cleared")
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
    suspend fun onGroupCreated(session: GroupSession) {
        val previous = sessionStore.session.value
        if (previous != null && previous.groupId != session.groupId) gateway.unsubscribe(previous.subscribeTopics)
        sessionStore.save(session)
        _groupState.value = null
        gateway.subscribe(session.subscribeTopics)
        DhLog.i("group session saved (leader) groupId=${TextMasking.maskVehicleId(session.groupId)}")
    }

    /** 나가기·종료를 이 차가 직접 했을 때 — 구독 해제 + 세션 삭제 */
    suspend fun onGroupLeftLocally() {
        val current = sessionStore.session.value ?: return
        gateway.unsubscribe(current.subscribeTopics)
        sessionStore.clear(SessionClearReason.LEFT_LOCALLY)
        _groupState.value = null
        DhLog.i("group session cleared (left/ended locally) groupId=${TextMasking.maskVehicleId(current.groupId)}")
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
    }
}
