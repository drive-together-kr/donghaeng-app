package app.donghaeng.car.core.network.mqtt

import app.donghaeng.car.core.common.DhLog
import app.donghaeng.car.core.common.StoredCredentials
import app.donghaeng.car.core.common.TextMasking
import app.donghaeng.car.core.network.ServerConfig
import app.donghaeng.protocol.DhJson
import app.donghaeng.protocol.Envelope
import app.donghaeng.protocol.Presence
import app.donghaeng.protocol.Topics
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.MqttGlobalPublishFilter
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.lifecycle.MqttClientDisconnectedContext
import com.hivemq.client.mqtt.lifecycle.MqttDisconnectSource
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient
import com.hivemq.client.mqtt.mqtt5.exceptions.Mqtt5ConnAckException
import com.hivemq.client.mqtt.mqtt5.lifecycle.Mqtt5ClientReconnector
import com.hivemq.client.mqtt.mqtt5.message.connect.connack.Mqtt5ConnAckReasonCode
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * HiveMQ MQTT 5 클라이언트 구현 (T16, docs/03 §4·§5, docs/08 T16).
 *
 * - 접속: username = `mqtt.username`(vehicleId), clientId = `mqtt.clientId`, password = 차량 JWT. 재연결 때마다 **가장 최근 토큰**으로 다시 인증한다.
 * - 재연결: HiveMQ automaticReconnect 를 켜 두되 **대기 시간은 이 클래스가 정한다**(T33) — 끊길 때마다 `reconnectWhen(gate)` 로 다음 시도를
 *   우리 타이머(1s → 최대 30s 지수 백오프 ±20%)에 묶고, 시도 직전에 최신 토큰을 다시 싣는다. 브로커 재시작·네트워크 단절 뒤 스스로 복구하고 구독을 다시 건다.
 * - **인증 거부**(CONNACK NOT_AUTHORIZED / BAD_USER_NAME_OR_PASSWORD, T33): [authFailures] 로 알리고 재연결을 최대 15 s 미룬다.
 *   그 사이 `TokenRefresher` 가 새 토큰을 받아 [connect] 가 오면 즉시 새 토큰으로 붙는다. [MqttStatus.authFailures] 로 횟수를 보인다.
 * - 앱이 끊거나 클라이언트를 갈아 끼우면 이전 클라이언트는 retired — 대기 중이던 재연결은 취소된다(같은 clientId 로 두 클라이언트가 서로 밀어내지 않게).
 * - LWT: `dh/v1/vehicle/{vid}/presence` 에 `{"online":false}`(QoS 1, retained). 접속 직후 `{"online":true}` 를 같은 토픽에 retained 로 올린다.
 * - TLS: [MqttUriPolicy] — `ssl://` 이면 시스템 신뢰 저장소로 TLS, 서버가 https 인데 `tcp://` 면 접속을 거부한다(평문 차단).
 * - 로그: 토픽의 차량 ID 는 마스킹, 본문은 찍지 않는다(CLAUDE.md 규칙 8).
 */
@Singleton
class HiveMqttGateway @Inject constructor() : MqttGateway {

    private val _status = MutableStateFlow(MqttStatus())
    override val status: StateFlow<MqttStatus> = _status.asStateFlow()

    private val _inbound = MutableSharedFlow<InboundMessage>(extraBufferCapacity = 128, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val inbound: SharedFlow<InboundMessage> = _inbound.asSharedFlow()

    private val _authFailures = MutableSharedFlow<MqttAuthFailure>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val authFailures: SharedFlow<MqttAuthFailure> = _authFailures.asSharedFlow()

    private val mutex = Mutex()
    private val subscriptions = ConcurrentHashMap.newKeySet<String>()
    private val seqs = ConcurrentHashMap<String, AtomicLong>()

    /** 재연결 대기 타이머 — 백오프·인증 대기가 끝나면 gate 를 연다 */
    private val timer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "dh-mqtt-timer").apply { isDaemon = true } }

    @Volatile
    private var client: Mqtt5AsyncClient? = null

    @Volatile
    private var credentials: StoredCredentials? = null

    @Volatile
    private var connection: Connection? = null

    /** 클라이언트 하나의 수명. retired 가 되면 그 클라이언트의 남은 재연결은 취소된다 */
    private class Connection(val vehicleId: String) {
        @Volatile
        var retired = false

        /** 마지막 CONNECT 에 실은 토큰(인증 거부가 '현재 토큰' 때문인지 가리는 데만 쓴다) */
        @Volatile
        var attemptToken: String? = null

        /** 인증 거부 뒤 새 토큰을 기다리는 중 */
        @Volatile
        var awaitingAuth = false

        @Volatile
        var mqttClient: Mqtt5AsyncClient? = null

        private val gate = AtomicReference<CompletableFuture<Unit>?>(null)

        fun arm(next: CompletableFuture<Unit>) {
            gate.getAndSet(next)?.complete(Unit)
        }

        /** [expected] 가 아직 대기 중이면 연다(null 이면 무엇이든) */
        fun release(expected: CompletableFuture<Unit>? = null) {
            val current = gate.get() ?: return
            if (expected != null && current !== expected) return
            if (gate.compareAndSet(current, null)) current.complete(Unit)
        }
    }

    override suspend fun connect(credentials: StoredCredentials) {
        mutex.withLock { connectLocked(credentials) }
    }

    private fun connectLocked(credentials: StoredCredentials) {
        val current = this.credentials
        val existing = connection
        if (client != null && existing != null && current != null && current.vehicleId == credentials.vehicleId) {
            if (current.vehicleAccessToken != credentials.vehicleAccessToken) {
                // 토큰만 바뀜(갱신) — 연결돼 있으면 유지하고 다음 재연결부터 새 토큰. 인증 거부로 기다리는 중이면 지금 바로 붙는다
                this.credentials = credentials
                if (existing.awaitingAuth) {
                    DhLog.i("mqtt token refreshed → reconnect now vid=${TextMasking.maskVehicleId(credentials.vehicleId)}")
                    existing.release()
                } else {
                    DhLog.i("mqtt token refreshed for next reconnect vid=${TextMasking.maskVehicleId(credentials.vehicleId)}")
                }
            }
            return
        }
        if (client != null) disconnectLocked()

        val endpoint = try {
            MqttUriPolicy.resolve(credentials.mqttUri, ServerConfig.publicBaseUrl)
        } catch (e: MqttUriPolicy.PolicyViolation) {
            DhLog.e("mqtt refused: ${e.message}")
            _status.update { it.copy(state = MqttState.DISCONNECTED, brokerUri = credentials.mqttUri, lastError = e.message, notConnectedSinceMs = null) }
            return
        }
        this.credentials = credentials
        val vid = credentials.vehicleId
        val conn = Connection(vid)
        connection = conn
        val presenceTopic = Topics.vehiclePresence(vid)
        _status.update {
            MqttStatus(
                state = MqttState.CONNECTING, brokerUri = credentials.mqttUri, tls = endpoint.tls, subscribed = subscriptions.toSet(), reconnects = 0,
                authFailures = it.authFailures, lastAuthFailureAtMs = it.lastAuthFailureAtMs, notConnectedSinceMs = System.currentTimeMillis(),
            )
        }

        val builder = MqttClient.builder()
            .useMqttVersion5()
            .identifier(credentials.mqttClientId)
            .serverHost(endpoint.host)
            .serverPort(endpoint.port)
            .automaticReconnect().initialDelay(1, TimeUnit.SECONDS).maxDelay(30, TimeUnit.SECONDS).applyAutomaticReconnect()
            .addConnectedListener {
                val c = conn.mqttClient ?: return@addConnectedListener
                if (conn.retired) {
                    // 앱이 이미 버린 클라이언트가 늦게 붙었다 → 바로 끊는다(새 클라이언트와 clientId 가 같다)
                    DhLog.w("mqtt retired client connected → disconnect")
                    c.disconnect()
                    return@addConnectedListener
                }
                conn.awaitingAuth = false
                val wasReconnect = _status.value.connectedAtMs != null
                _status.update {
                    it.copy(
                        state = MqttState.CONNECTED, lastError = null, connectedAtMs = System.currentTimeMillis(), notConnectedSinceMs = null,
                        reconnects = if (wasReconnect) it.reconnects + 1 else it.reconnects,
                    )
                }
                DhLog.i("mqtt connected broker=${endpoint.host}:${endpoint.port} tls=${endpoint.tls} vid=${TextMasking.maskVehicleId(vid)} reconnect=$wasReconnect")
                publishPresence(c, presenceTopic, online = true)
                resubscribe(c)
            }
            .addDisconnectedListener { ctx -> onDisconnected(ctx, conn, credentials) }
        val built = if (endpoint.tls) builder.sslWithDefaultConfig().buildAsync() else builder.buildAsync()
        conn.mqttClient = built
        client = built
        built.publishes(MqttGlobalPublishFilter.ALL) { publish ->
            val topic = publish.topic.toString()
            try {
                val envelope = DhJson.peek(String(publish.payloadAsBytes, Charsets.UTF_8))
                _status.update { it.copy(inboundCount = it.inboundCount + 1) }
                _inbound.tryEmit(InboundMessage(topic, envelope, System.currentTimeMillis()))
                DhLog.d("mqtt inbound topic=${maskTopic(topic)} type=${envelope.type} seq=${envelope.seq}")
            } catch (e: Exception) {
                DhLog.w("mqtt inbound dropped topic=${maskTopic(topic)} cause=${e.javaClass.simpleName}")
            }
        }
        conn.attemptToken = credentials.vehicleAccessToken
        built.connectWith()
            .simpleAuth().username(credentials.mqttUsername).password(credentials.vehicleAccessToken.toByteArray()).applySimpleAuth()
            .cleanStart(false)
            .sessionExpiryInterval(SESSION_EXPIRY_S)
            .willPublish()
            .topic(presenceTopic)
            .payload(DhJson.json.encodeToString(Presence.serializer(), Presence(online = false)).toByteArray())
            .qos(MqttQos.AT_LEAST_ONCE)
            .retain(true)
            .applyWillPublish()
            .send()
            .whenComplete { ack, error ->
                if (error != null) {
                    DhLog.w("mqtt first connect not completed cause=${error.message ?: error.javaClass.simpleName} — reconnect continues unless retired")
                } else {
                    DhLog.i("mqtt connack reason=${ack.reasonCode} sessionPresent=${ack.isSessionPresent}")
                }
            }
        Unit
    }

    /**
     * HiveMQ 이벤트 루프에서 불린다. 다음 재연결은 gate(백오프 또는 인증 대기 타이머)가 열릴 때 — 그 직전에 최신 토큰을 다시 싣는다.
     * `reconnectWhen`·`delay` 는 이 리스너 안에서만, `connectWith`·`reconnect(false)` 는 이벤트 루프의 콜백에서도 부를 수 있다(HiveMQ 1.3.4 javap 확인).
     */
    private fun onDisconnected(ctx: MqttClientDisconnectedContext, conn: Connection, initial: StoredCredentials) {
        val reconnector = ctx.reconnector as Mqtt5ClientReconnector
        if (conn.retired || ctx.source == MqttDisconnectSource.USER) {
            reconnector.reconnect(false)
            return
        }
        val now = System.currentTimeMillis()
        val usedToken = conn.attemptToken
        val latest = latestFor(conn) ?: initial
        applyAuth(reconnector, latest, conn)

        val authCode = authFailureCode(ctx.cause)
        // 거부된 게 '지금 가진 최신 토큰' 일 때만 갱신을 기다린다. 옛 토큰이 거부된 거면 새 토큰으로 바로(백오프 뒤) 다시 시도
        val rejectedLatest = authCode != null && (usedToken == null || usedToken == latest.vehicleAccessToken)
        val waitMs = if (rejectedLatest) maxOf(backoffMs(reconnector.attempts), AUTH_WAIT_MS) else backoffMs(reconnector.attempts)
        conn.awaitingAuth = rejectedLatest

        val gate = CompletableFuture<Unit>()
        conn.arm(gate)
        reconnector.delay(0, TimeUnit.MILLISECONDS)
        reconnector.reconnectWhen(gate) { _, _ ->
            if (conn.retired) {
                reconnector.reconnect(false)
            } else {
                latestFor(conn)?.let { applyAuth(reconnector, it, conn) }
            }
        }
        timer.schedule({ conn.release(gate) }, waitMs, TimeUnit.MILLISECONDS)

        val reason = ctx.cause.message ?: ctx.cause.javaClass.simpleName
        _status.update {
            it.copy(
                state = if (it.state == MqttState.DISCONNECTED) MqttState.DISCONNECTED else MqttState.RECONNECTING,
                lastError = if (authCode != null) "auth rejected: $authCode" else reason,
                notConnectedSinceMs = it.notConnectedSinceMs ?: now,
                authFailures = if (authCode != null) it.authFailures + 1 else it.authFailures,
                lastAuthFailureAtMs = if (authCode != null) now else it.lastAuthFailureAtMs,
            )
        }
        if (authCode != null) {
            DhLog.w("mqtt auth rejected reason=$authCode latest=$rejectedLatest attempt=${reconnector.attempts} → retry in ${waitMs / 1000}s")
            if (rejectedLatest) _authFailures.tryEmit(MqttAuthFailure(authCode, now, usedToken ?: latest.vehicleAccessToken))
        } else {
            DhLog.w("mqtt disconnected source=${ctx.source} cause=$reason attempt=${reconnector.attempts} → retry in ${waitMs}ms")
        }
    }

    private fun latestFor(conn: Connection): StoredCredentials? = credentials?.takeIf { it.vehicleId == conn.vehicleId }

    /** 다음 CONNECT 에 토큰을 싣는다. connectWith() 는 직전 CONNECT(will 포함)를 복사해 시작한다 */
    private fun applyAuth(reconnector: Mqtt5ClientReconnector, creds: StoredCredentials, conn: Connection) {
        reconnector.connectWith()
            .simpleAuth().username(creds.mqttUsername).password(creds.vehicleAccessToken.toByteArray()).applySimpleAuth()
            .cleanStart(false)
            .sessionExpiryInterval(SESSION_EXPIRY_S)
            .applyConnect()
        conn.attemptToken = creds.vehicleAccessToken
    }

    override suspend fun disconnect() {
        mutex.withLock { disconnectLocked() }
    }

    private fun disconnectLocked() {
        val c = client ?: return
        val conn = connection
        // 먼저 retired — 이 클라이언트의 대기 중 재연결은 gate 콜백에서 취소된다
        conn?.retired = true
        conn?.release()
        val vid = credentials?.vehicleId
        try {
            if (c.state.isConnected) {
                if (vid != null) publishPresence(c, Topics.vehiclePresence(vid), online = false)
                c.disconnect().get(3, TimeUnit.SECONDS)
            }
        } catch (e: Exception) {
            DhLog.d("mqtt disconnect: ${e.javaClass.simpleName}")
        }
        client = null
        credentials = null
        connection = null
        _status.update { it.copy(state = MqttState.DISCONNECTED, connectedAtMs = null, notConnectedSinceMs = null) }
        DhLog.i("mqtt disconnected by app")
    }

    override suspend fun subscribe(topics: Collection<String>) {
        val added = topics.filter { subscriptions.add(it) }
        _status.update { it.copy(subscribed = subscriptions.toSet()) }
        val c = client ?: return
        if (c.state.isConnected && added.isNotEmpty()) added.forEach { subscribeOne(c, it) }
    }

    override suspend fun unsubscribe(topics: Collection<String>) {
        val removed = topics.filter { subscriptions.remove(it) }
        _status.update { it.copy(subscribed = subscriptions.toSet()) }
        val c = client ?: return
        if (c.state.isConnected) {
            removed.forEach { topic ->
                c.unsubscribeWith().topicFilter(topic).send().whenComplete { _, error ->
                    if (error != null) DhLog.w("mqtt unsubscribe failed topic=${maskTopic(topic)} cause=${error.message}")
                }
            }
        }
    }

    override fun <T> publish(topic: String, type: String, data: T, serializer: KSerializer<T>, qos: Int, retain: Boolean): Boolean {
        val c = client
        val vid = credentials?.vehicleId
        if (c == null || vid == null || !c.state.isConnectedOrReconnect) {
            DhLog.w("mqtt publish skipped (not connected) topic=${maskTopic(topic)} type=$type")
            return false
        }
        val envelope = Envelope(type = type, vid = vid, ts = System.currentTimeMillis(), seq = seqs.computeIfAbsent(topic) { AtomicLong() }.incrementAndGet(), data = data)
        val payload = DhJson.encode(envelope, serializer).toByteArray(Charsets.UTF_8)
        c.publishWith().topic(topic).qos(MqttQos.fromCode(qos) ?: MqttQos.AT_LEAST_ONCE).retain(retain).payload(payload).send()
            .whenComplete { _, error ->
                if (error != null) DhLog.w("mqtt publish failed topic=${maskTopic(topic)} type=$type cause=${error.message}")
                else _status.update { it.copy(outboundCount = it.outboundCount + 1) }
            }
        return true
    }

    // ---------------------------------------------------------------- internals

    private fun resubscribe(c: Mqtt5AsyncClient) {
        subscriptions.forEach { subscribeOne(c, it) }
    }

    private fun subscribeOne(c: Mqtt5AsyncClient, topic: String) {
        c.subscribeWith().topicFilter(topic).qos(MqttQos.AT_LEAST_ONCE).send().whenComplete { ack, error ->
            when {
                error != null -> DhLog.w("mqtt subscribe failed topic=${maskTopic(topic)} cause=${error.message}")
                ack.reasonCodes.any { it.isError } -> DhLog.w("mqtt subscribe denied topic=${maskTopic(topic)} reason=${ack.reasonCodes}")
                else -> DhLog.i("mqtt subscribed topic=${maskTopic(topic)}")
            }
        }
    }

    /** presence 는 봉투 없이 `{"online":…}` (docs/03 §4, protocol Presence KDoc) */
    private fun publishPresence(c: Mqtt5AsyncClient, topic: String, online: Boolean) {
        val payload = DhJson.json.encodeToString(Presence.serializer(), Presence(online)).toByteArray()
        c.publishWith().topic(topic).qos(MqttQos.AT_LEAST_ONCE).retain(true).payload(payload).send()
            .whenComplete { _, error -> if (error != null) DhLog.w("mqtt presence publish failed online=$online cause=${error.message}") }
    }

    private companion object {
        const val SESSION_EXPIRY_S = 300L

        /** 인증 거부 뒤 새 토큰을 기다리는 최대 시간 — 갱신이 실패하면 이 뒤에 가진 토큰으로 다시 시도한다 */
        const val AUTH_WAIT_MS = 15_000L
        const val BACKOFF_INITIAL_MS = 1_000L
        const val BACKOFF_MAX_MS = 30_000L

        /** 1s, 2s, 4s … 최대 30s, ±20% 흔들기(여러 차가 동시에 다시 붙지 않게) */
        fun backoffMs(attempts: Int): Long {
            val base = (BACKOFF_INITIAL_MS shl attempts.coerceIn(0, 5)).coerceAtMost(BACKOFF_MAX_MS)
            return (base * (0.8 + Random.nextDouble() * 0.4)).toLong()
        }

        /** CONNACK 인증 거부면 사유 코드 이름, 아니면 null */
        fun authFailureCode(cause: Throwable): String? {
            val connAck = generateSequence(cause) { it.cause?.takeIf { c -> c !== it } }.take(4).filterIsInstance<Mqtt5ConnAckException>().firstOrNull() ?: return null
            val code = connAck.mqttMessage.reasonCode
            return if (code == Mqtt5ConnAckReasonCode.NOT_AUTHORIZED || code == Mqtt5ConnAckReasonCode.BAD_USER_NAME_OR_PASSWORD) code.name else null
        }

        /** 토픽 안의 `veh_…`/`grp_…` 세그먼트를 마스킹해 로그에 남긴다 */
        fun maskTopic(topic: String): String = topic.split('/').joinToString("/") { seg ->
            if (seg.startsWith("veh_") || seg.startsWith("grp_")) TextMasking.maskVehicleId(seg) else seg
        }
    }
}
