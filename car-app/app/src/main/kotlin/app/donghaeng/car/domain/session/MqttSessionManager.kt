package app.donghaeng.car.domain.session

import app.donghaeng.car.core.common.DhLog
import app.donghaeng.car.core.common.TextMasking
import app.donghaeng.car.core.common.TokenStore
import app.donghaeng.car.core.common.TokenStoreState
import app.donghaeng.car.core.network.mqtt.InboundMessage
import app.donghaeng.car.core.network.mqtt.MqttGateway
import app.donghaeng.protocol.CmdType
import app.donghaeng.protocol.DhJson
import app.donghaeng.protocol.GroupEnded
import app.donghaeng.protocol.GroupEvent
import app.donghaeng.protocol.GroupJoined
import app.donghaeng.protocol.GroupState
import app.donghaeng.protocol.MessageTypes
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

/** 봉투 ts(서버 발행 시각)·수신 시각이 붙은 그룹 이벤트 */
data class ReceivedGroupEvent(val event: GroupEvent, val envelopeTsMs: Long, val receivedAtMs: Long)

/**
 * 페어링 상태 ↔ MQTT 연결 ↔ 그룹 세션을 잇는다 (T16; 호출 순서는 docs/03 §4·§5.5).
 *
 * - [TokenStore] 에 자격 증명이 있으면 [MqttGateway.connect] + 자기 `cmd` 토픽(+ 저장된 그룹 토픽) 구독. 없어지면(연결 해제) 끊고 그룹 세션도 지운다.
 * - cmd `GROUP_JOINED` → [GroupSessionStore] 에 저장하고 그룹 `state/event` 구독. `GROUP_ENDED` → 세션 삭제·구독 해제. `TOKEN_REVOKED` → 자격 증명 삭제.
 * - 그룹 토픽 수신은 [groupEvents]/[groupState] 로 내보낸다(T25 주행 화면·T27 음성이 구독).
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

    private val _groupState = MutableStateFlow<GroupState?>(null)
    val groupState: StateFlow<GroupState?> = _groupState.asStateFlow()

    /** 마지막 STATE 수신 시각(epoch ms)·누적 수 — 진단용 (T24) */
    private val _groupStateAt = MutableStateFlow<Long?>(null)
    val groupStateAt: StateFlow<Long?> = _groupStateAt.asStateFlow()
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
                    }
                    .onFailure { DhLog.w("group event decode failed cause=${it.javaClass.simpleName}") }
            }
            current != null && topic == current.topics.state && message.envelope.type == MessageTypes.STATE -> {
                runCatching { DhJson.decodeData<GroupState>(message.envelope).data }
                    .onSuccess { state ->
                        _groupState.value = state
                        _groupStateAt.value = message.receivedAtMs
                        stateCount++
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
            CmdType.ROUTE_FOLLOW_PROPOSAL, CmdType.REST_STOP_PROPOSAL -> {
                note(type, null)
                DhLog.i("cmd ${type.name} received (handled from T28/T50)")
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
}
