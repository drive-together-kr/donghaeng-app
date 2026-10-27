package app.donghaeng.mqtt

import app.donghaeng.protocol.DhJson
import app.donghaeng.protocol.Presence
import app.donghaeng.protocol.Topics
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * 공유 구독(`$share/server/dh/v1/vehicle/+/#`)으로 받은 메시지를 종류별 [MqttInboundHandler] 들에 나눠 준다 (docs/06 §3).
 *
 * 버리는 경우(WARN 로그, 본문은 찍지 않음): 토픽 형식 오류, 모르는 종류, JSON/봉투 해석 실패, 봉투 `vid` ≠ 토픽 `{vid}`.
 * 핸들러 하나의 예외는 다른 핸들러·다음 메시지에 번지지 않는다.
 * presence 토픽은 봉투가 없으므로(`{"online":…}`) [PresenceInboundHandler] 로 따로 보낸다.
 */
@Component
class TopicRouter(
    handlers: List<MqttInboundHandler>,
    private val presenceHandlers: List<PresenceInboundHandler>,
    private val clock: Clock,
) {

    private val byKind: Map<Topics.VehicleTopicKind, List<MqttInboundHandler>> = handlers.groupBy { it.kind }

    fun route(topic: String, payload: ByteArray) {
        val vehicleId = Topics.vehicleIdFromTopic(topic)
        val kind = Topics.vehicleTopicKind(topic)
        if (vehicleId == null || kind == null) {
            log.warn("inbound dropped: unexpected topic shape topic={}", topic)
            return
        }
        if (kind == Topics.VehicleTopicKind.PRESENCE) {
            routePresence(vehicleId, payload)
            return
        }
        val envelope = try {
            DhJson.peek(String(payload, Charsets.UTF_8))
        } catch (e: Exception) {
            log.warn("inbound dropped: bad envelope vid={} kind={} cause={}", vehicleId, kind, e.javaClass.simpleName)
            return
        }
        if (envelope.vid != vehicleId) {
            log.warn("inbound dropped: envelope vid mismatch topicVid={} envelopeVid={} kind={}", vehicleId, envelope.vid, kind)
            return
        }
        val targets = byKind[kind].orEmpty()
        if (targets.isEmpty()) {
            log.debug("inbound {} vid={} — no handler yet", kind, vehicleId)
            return
        }
        targets.forEach { handler ->
            try {
                handler.handle(vehicleId, envelope)
            } catch (e: Exception) {
                log.error("inbound handler failed handler={} kind={} vid={} cause={}", handler.javaClass.simpleName, kind, vehicleId, e.toString())
            }
        }
    }

    private fun routePresence(vehicleId: String, payload: ByteArray) {
        if (payload.isEmpty()) return   // retained 삭제
        val presence = try {
            DhJson.json.decodeFromString(Presence.serializer(), String(payload, Charsets.UTF_8))
        } catch (e: Exception) {
            log.warn("presence dropped: bad payload vid={} cause={}", vehicleId, e.javaClass.simpleName)
            return
        }
        val now = clock.millis()
        presenceHandlers.forEach { handler ->
            try {
                handler.onPresence(vehicleId, presence.online, now)
            } catch (e: Exception) {
                log.error("presence handler failed handler={} vid={} cause={}", handler.javaClass.simpleName, vehicleId, e.toString())
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(TopicRouter::class.java)
    }
}
