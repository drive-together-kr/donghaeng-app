package app.donghaeng.car.core.network.mqtt

import app.donghaeng.car.core.common.StoredCredentials
import app.donghaeng.protocol.DhJson
import app.donghaeng.protocol.Envelope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.KSerializer

/**
 * 테스트·Fake 용 메모리 게이트웨이 — 브로커 없이 접속/구독/발행을 기록하고, [emitInbound] 로 수신을 흉내 낸다.
 * (CLAUDE.md 규칙 2: Fake 로 단위 테스트가 돌아야 한다)
 */
class InMemoryMqttGateway : MqttGateway {

    data class Published(val topic: String, val type: String, val json: String, val qos: Int, val retain: Boolean)

    private val _status = MutableStateFlow(MqttStatus())
    override val status: StateFlow<MqttStatus> = _status.asStateFlow()

    private val _inbound = MutableSharedFlow<InboundMessage>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val inbound: SharedFlow<InboundMessage> = _inbound.asSharedFlow()

    val connectedWith = mutableListOf<StoredCredentials>()
    var disconnectCount = 0
    val subscriptions = linkedSetOf<String>()
    val published = mutableListOf<Published>()

    /** false 면 [publish] 가 연결 없음처럼 false 를 돌려준다 */
    var online: Boolean = true

    override suspend fun connect(credentials: StoredCredentials) {
        connectedWith += credentials
        _status.update { it.copy(state = MqttState.CONNECTED, brokerUri = credentials.mqttUri, tls = credentials.mqttUri.startsWith("ssl://"), connectedAtMs = 1L) }
    }

    override suspend fun disconnect() {
        disconnectCount++
        _status.update { it.copy(state = MqttState.DISCONNECTED, connectedAtMs = null) }
    }

    override suspend fun subscribe(topics: Collection<String>) {
        subscriptions += topics
        _status.update { it.copy(subscribed = subscriptions.toSet()) }
    }

    override suspend fun unsubscribe(topics: Collection<String>) {
        subscriptions -= topics.toSet()
        _status.update { it.copy(subscribed = subscriptions.toSet()) }
    }

    override fun <T> publish(topic: String, type: String, data: T, serializer: KSerializer<T>, qos: Int, retain: Boolean): Boolean {
        if (!online) return false
        published += Published(topic, type, DhJson.json.encodeToString(serializer, data), qos, retain)
        _status.update { it.copy(outboundCount = it.outboundCount + 1) }
        return true
    }

    /** 브로커에서 메시지가 온 것처럼 흘려보낸다. [data] 는 본문 객체 */
    fun <T> emitInbound(topic: String, type: String, vid: String, data: T, serializer: KSerializer<T>, seq: Long = 1) {
        val raw = DhJson.encode(Envelope(type = type, vid = vid, ts = 1_700_000_000_000L, seq = seq, data = data), serializer)
        _inbound.tryEmit(InboundMessage(topic, DhJson.peek(raw), 1_700_000_000_000L))
        _status.update { it.copy(inboundCount = it.inboundCount + 1) }
    }
}
