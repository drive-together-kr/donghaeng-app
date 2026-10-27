package app.donghaeng.mqtt

import app.donghaeng.protocol.Envelope
import app.donghaeng.protocol.Topics
import kotlinx.serialization.json.JsonElement
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 차량 토픽(`dh/v1/vehicle/{vid}/{telemetry|event|ack|presence}`)으로 들어온 메시지를 받는 쪽.
 * [TopicRouter] 가 토픽 종류별로 모든 구현체에 전달한다. presence(T24)·event(T26)·routesync(T28)가 각자 구현을 추가한다.
 *
 * 봉투의 `vid` 와 토픽의 `{vid}` 는 라우터가 이미 같음을 확인했다(EMQX 가 username = 토큰 sub = 토픽 vid 를 보장하고,
 * 서버는 봉투까지 한 번 더 본다).
 */
interface MqttInboundHandler {
    val kind: Topics.VehicleTopicKind

    /**
     * @param vehicleId 토픽의 `{vid}`
     * @param envelope  헤더만 해석된 봉투(`data` 는 원본 JSON). 종류별 본문은 `DhJson.decodeData` 로 마저 해석한다
     */
    fun handle(vehicleId: String, envelope: Envelope<JsonElement>)
}

/**
 * `dh/v1/vehicle/{vid}/presence` — 봉투 없이 `{"online":…}` 만 오는 토픽(브로커 LWT, protocol `Presence`).
 * [TopicRouter] 가 따로 해석해 전달한다. presence 모듈(T24)이 구현한다.
 */
interface PresenceInboundHandler {
    fun onPresence(vehicleId: String, online: Boolean, tsMs: Long)
}

/** T15 기본 구현 — 종류별 수신을 로그로만 남긴다(좌표·본문은 찍지 않는다). 담당 모듈이 생기면 그쪽 구현이 함께 호출된다 */
@Component
class LoggingInboundHandlers : MqttInboundHandler {

    override val kind: Topics.VehicleTopicKind = Topics.VehicleTopicKind.TELEMETRY

    override fun handle(vehicleId: String, envelope: Envelope<JsonElement>) {
        if (log.isDebugEnabled) log.debug("inbound {} vid={} seq={} ts={}", envelope.type, vehicleId, envelope.seq, envelope.ts)
    }

    companion object {
        private val log = LoggerFactory.getLogger(LoggingInboundHandlers::class.java)
    }
}
