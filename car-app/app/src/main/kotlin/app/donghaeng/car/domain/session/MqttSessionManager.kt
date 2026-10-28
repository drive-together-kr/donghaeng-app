package app.donghaeng.car.domain.session

import app.donghaeng.car.core.common.DhLog
import app.donghaeng.car.core.common.StoredCredentials
import app.donghaeng.car.core.common.TextMasking
import app.donghaeng.car.core.common.TokenStore
import app.donghaeng.car.core.common.TokenStoreState
import app.donghaeng.car.core.network.mqtt.InboundMessage
import app.donghaeng.car.core.network.mqtt.MqttGateway
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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
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

/**
 * 페어링 상태 ↔ MQTT 연결 ↔ 그룹 세션을 잇는다 (T16; 호출 순서는 docs/03 §4·§5.5).
 *
 * - [TokenStore] 에 자격 증명이 있으면 [MqttGateway.connect] + 자기 `cmd` 토픽(+ 저장된 그룹 토픽) 구독. 없어지면(연결 해제) 끊고 그룹 세션도 지운다.
 * - cmd `GROUP_JOINED` → [GroupSessionStore] 에 저장하고 그룹 `state/event` 구독. `GROUP_ENDED` 또는 내 차의 그룹 이벤트 `MEMBER_LEFT`(폰이 내보냄) → 세션 삭제·구독 해제. `TOKEN_REVOKED` → 자격 증명 삭제.
 * - 그룹 토픽 수신은 [groupEvents]/[groupState] 로 내보낸다(T25 주행 화면·T27 음성이 구독).
 * - 스냅샷의 내 차 닉네임이 저장값과 다르면(폰에서 이름을 바꿈) [TokenStore] 의 닉네임만 바꾼다 — 홈·설정·그룹 기본 이름이 따라온다.
 *
 * 앱 프로세스가 살아 있는 동안 페어링만 돼 있으면 연결을 유지한다 — 폰이 이 차를 그룹에 넣는 순간(`GROUP_JOINED`)을 받으려면
 * 그룹 전에도 cmd 를 듣고 있어야 하기 때문이다(T18). 백그라운드 지속은 T23 의 DriveSessionService 가 맡는다.
 */
@Singleton
class MqttSessionManager @Inject constructor(
    private val tokenStore: TokenStore,
    private val gateway: MqttGateway,
    private val sessionStore: GroupSessionStore,
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

    @Volatile
    private var vehicleId: String? = null

    /** 한 번만 시작한다(Application.onCreate). [dispatcher] 는 테스트에서 바꿔 끼운다 */
    fun start(dispatcher: CoroutineDispatcher = Dispatchers.Default) {
        if (!started.compareAndSet(false, true)) return
        scope = CoroutineScope(SupervisorJob() + dispatcher)
        scope.launch {
            sessionStore.load()
            tokenStore.load()
            tokenStore.state.collect { state -> onTokenState(state) }
        }
        scope.launch { gateway.inbound.collect { onInbound(it) } }
        DhLog.i("mqtt session manager started")
    }

    private suspend fun onTokenState(state: TokenStoreState) {
        when (state) {
            TokenStoreState.Loading -> Unit
            TokenStoreState.Empty -> {
                val wasConnected = vehicleId != null
                vehicleId = null
                if (wasConnected) gateway.disconnect()
                if (sessionStore.session.value != null) {
                    sessionStore.clear()
                    _groupState.value = null
                    DhLog.i("group session cleared (unpaired)")
                }
            }
            is TokenStoreState.Present -> {
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
                            sessionStore.clear()
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
                        syncNickname(state, vid)
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
        val updated = nicknameFromState(state, vid, tokenStore.state.value) ?: return
        tokenStore.save(updated)
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
                    sessionStore.clear()
                    _groupState.value = null
                    DhLog.i("GROUP_ENDED groupId=${TextMasking.maskVehicleId(current.groupId)} reason=${cmd?.reason} → session cleared")
                }
            }
            CmdType.TOKEN_REVOKED -> {
                note(type, null)
                DhLog.w("TOKEN_REVOKED → clearing credentials")
                tokenStore.clear()
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
        sessionStore.clear()
        _groupState.value = null
        DhLog.i("group session cleared (left/ended locally) groupId=${TextMasking.maskVehicleId(current.groupId)}")
    }

    private fun note(type: CmdType, groupId: String?) {
        _lastCommand.value = LastCommand(type.name, groupId?.let(TextMasking::maskVehicleId), System.currentTimeMillis())
    }

    companion object {
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
