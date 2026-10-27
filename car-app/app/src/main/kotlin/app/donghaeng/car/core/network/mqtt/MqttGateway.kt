package app.donghaeng.car.core.network.mqtt

import app.donghaeng.car.core.common.StoredCredentials
import app.donghaeng.protocol.Envelope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement

/** 브로커 연결 상태 (진단 화면·홈 표시용) */
enum class MqttState { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING }

/**
 * 게이트웨이 상태 스냅샷. 토큰·좌표는 들어 있지 않다(그대로 화면·로그에 써도 된다).
 *
 * @param brokerUri    실제 접속 URI(서버가 준 `mqtt.uri`)
 * @param tls          ssl:// 로 접속 중인가
 * @param lastError    마지막 연결 실패·거부 사유(사람이 읽는 한 줄). 연결되면 null
 * @param connectedAtMs 마지막으로 CONNECTED 가 된 시각(epoch ms)
 * @param reconnects   끊겼다가 다시 붙은 횟수
 * @param subscribed   현재 구독 중인 토픽
 */
data class MqttStatus(
    val state: MqttState = MqttState.DISCONNECTED,
    val brokerUri: String? = null,
    val tls: Boolean = false,
    val lastError: String? = null,
    val connectedAtMs: Long? = null,
    val reconnects: Int = 0,
    val inboundCount: Long = 0,
    val outboundCount: Long = 0,
    val subscribed: Set<String> = emptySet(),
)

/** 수신 메시지 — 헤더만 해석된 봉투(`data` 는 원본 JSON). 종류별 본문은 `DhJson.decodeData` 로 마저 해석한다 */
data class InboundMessage(
    val topic: String,
    val envelope: Envelope<JsonElement>,
    val receivedAtMs: Long,
)

/**
 * 차량 앱의 MQTT 출입구 (docs/03 §4·§5, docs/05 §2 `core/network`). 구현: [HiveMqttGateway](기기) / [InMemoryMqttGateway](테스트).
 *
 * - 접속 신원은 페어링 자격 증명(username = clientId = vehicleId, password = 차량 JWT, docs/03 §1.2 `mqtt`).
 * - 자동 재연결(지수 백오프)·QoS 1·LWT(`presence {"online":false}`, retained)는 구현이 책임진다.
 * - 토픽 구독 목록은 게이트웨이가 기억해 재연결 뒤 다시 건다.
 * - 밖에서는 `protocol` 타입만 다룬다(HiveMQ 타입은 구현 안에만).
 */
interface MqttGateway {
    val status: StateFlow<MqttStatus>

    /** 구독 중인 토픽으로 들어온 모든 메시지(봉투 해석 실패분은 버리고 로그만) */
    val inbound: SharedFlow<InboundMessage>

    /** 자격 증명으로 (재)접속한다. 이미 같은 차량으로 접속 중이면 아무것도 하지 않는다. 토큰이 바뀌면 끊고 다시 붙는다 */
    suspend fun connect(credentials: StoredCredentials)

    suspend fun disconnect()

    /** 구독 목록에 추가하고(기억), 연결돼 있으면 바로 건다 */
    suspend fun subscribe(topics: Collection<String>)

    suspend fun unsubscribe(topics: Collection<String>)

    /** 봉투(`vid` = 내 vehicleId, `seq` = 토픽별 단조 증가)에 싸서 발행. 연결이 없으면 false(버린다 — 재전송은 호출 쪽 정책) */
    fun <T> publish(topic: String, type: String, data: T, serializer: KSerializer<T>, qos: Int = 1, retain: Boolean = false): Boolean
}
