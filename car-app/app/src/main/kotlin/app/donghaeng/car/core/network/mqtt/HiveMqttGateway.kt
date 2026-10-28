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
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient
import com.hivemq.client.mqtt.mqtt5.lifecycle.Mqtt5ClientReconnector
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * HiveMQ MQTT 5 클라이언트 구현 (T16, docs/03 §4·§5, docs/08 T16).
 *
 * - 접속: username = `mqtt.username`(vehicleId), clientId = `mqtt.clientId`, password = 차량 JWT. 재연결 때마다 **가장 최근 토큰**으로 다시 인증한다.
 * - 재연결: HiveMQ automaticReconnect(1s → 최대 30s 지수 백오프). 브로커 재시작·네트워크 단절 뒤 스스로 복구하고 구독을 다시 건다.
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

    private val mutex = Mutex()
    private val subscriptions = ConcurrentHashMap.newKeySet<String>()
    private val seqs = ConcurrentHashMap<String, AtomicLong>()

    @Volatile
    private var client: Mqtt5AsyncClient? = null

    @Volatile
    private var credentials: StoredCredentials? = null

    override suspend fun connect(credentials: StoredCredentials) {
        mutex.withLock { connectLocked(credentials) }
    }

    private fun connectLocked(credentials: StoredCredentials) {
        when (MqttConnectPolicy.decide(connected = client != null, current = this.credentials, next = credentials)) {
            // 같은 차·같은 토큰(닉네임만 바뀐 자격 증명 포함) — 연결 유지
            ConnectDecision.KEEP -> return
            ConnectDecision.REFRESH_TOKEN -> {
                // 토큰만 바뀜(갱신) — 다음 재연결부터 새 토큰을 쓴다. 지금 연결은 유지
                this.credentials = credentials
                DhLog.i("mqtt token refreshed for next reconnect vid=${TextMasking.maskVehicleId(credentials.vehicleId)}")
                return
            }
            ConnectDecision.CONNECT -> Unit
        }
        if (client != null) disconnectLocked()

        val endpoint = try {
            MqttUriPolicy.resolve(credentials.mqttUri, ServerConfig.publicBaseUrl)
        } catch (e: MqttUriPolicy.PolicyViolation) {
            DhLog.e("mqtt refused: ${e.message}")
            _status.update { it.copy(state = MqttState.DISCONNECTED, brokerUri = credentials.mqttUri, lastError = e.message) }
            return
        }
        this.credentials = credentials
        val vid = credentials.vehicleId
        val presenceTopic = Topics.vehiclePresence(vid)
        _status.update { MqttStatus(state = MqttState.CONNECTING, brokerUri = credentials.mqttUri, tls = endpoint.tls, subscribed = subscriptions.toSet(), reconnects = 0) }

        val builder = MqttClient.builder()
            .useMqttVersion5()
            .identifier(credentials.mqttClientId)
            .serverHost(endpoint.host)
            .serverPort(endpoint.port)
            .automaticReconnect().initialDelay(1, TimeUnit.SECONDS).maxDelay(30, TimeUnit.SECONDS).applyAutomaticReconnect()
            .addConnectedListener {
                val wasReconnect = _status.value.connectedAtMs != null
                _status.update { it.copy(state = MqttState.CONNECTED, lastError = null, connectedAtMs = System.currentTimeMillis(), reconnects = if (wasReconnect) it.reconnects + 1 else it.reconnects) }
                DhLog.i("mqtt connected broker=${endpoint.host}:${endpoint.port} tls=${endpoint.tls} vid=${TextMasking.maskVehicleId(vid)} reconnect=$wasReconnect")
                val c = client ?: return@addConnectedListener
                publishPresence(c, presenceTopic, online = true)
                resubscribe(c)
            }
            .addDisconnectedListener { ctx ->
                val latest = this.credentials ?: credentials
                (ctx.reconnector as Mqtt5ClientReconnector).connectWith()
                    .simpleAuth().username(latest.mqttUsername).password(latest.vehicleAccessToken.toByteArray()).applySimpleAuth()
                    .cleanStart(false)
                    .sessionExpiryInterval(SESSION_EXPIRY_S)
                    .applyConnect()
                val reason = ctx.cause.message ?: ctx.cause.javaClass.simpleName
                _status.update { it.copy(state = if (it.state == MqttState.DISCONNECTED) MqttState.DISCONNECTED else MqttState.RECONNECTING, lastError = reason) }
                DhLog.w("mqtt disconnected source=${ctx.source} cause=$reason")
            }
        val built = if (endpoint.tls) builder.sslWithDefaultConfig().buildAsync() else builder.buildAsync()
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
                    DhLog.w("mqtt first connect failed cause=${error.message} — automatic reconnect continues")
                } else {
                    DhLog.i("mqtt connack reason=${ack.reasonCode} sessionPresent=${ack.isSessionPresent}")
                }
            }
        Unit
    }

    override suspend fun disconnect() {
        mutex.withLock { disconnectLocked() }
    }

    private fun disconnectLocked() {
        val c = client ?: return
        val vid = credentials?.vehicleId
        try {
            if (c.state.isConnected && vid != null) publishPresence(c, Topics.vehiclePresence(vid), online = false)
            c.disconnect().get(3, TimeUnit.SECONDS)
        } catch (e: Exception) {
            DhLog.d("mqtt disconnect: ${e.javaClass.simpleName}")
        }
        client = null
        credentials = null
        _status.update { it.copy(state = MqttState.DISCONNECTED, connectedAtMs = null) }
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

        /** 토픽 안의 `veh_…`/`grp_…` 세그먼트를 마스킹해 로그에 남긴다 */
        fun maskTopic(topic: String): String = topic.split('/').joinToString("/") { seg ->
            if (seg.startsWith("veh_") || seg.startsWith("grp_")) TextMasking.maskVehicleId(seg) else seg
        }
    }
}
