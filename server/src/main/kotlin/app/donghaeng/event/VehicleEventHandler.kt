package app.donghaeng.event

import app.donghaeng.group.GroupMembershipApi
import app.donghaeng.identity.IdentityApi
import app.donghaeng.mqtt.MqttInboundHandler
import app.donghaeng.protocol.DestinationArrived
import app.donghaeng.protocol.DhJson
import app.donghaeng.protocol.EnergyKind
import app.donghaeng.protocol.Envelope
import app.donghaeng.protocol.GroupEvent
import app.donghaeng.protocol.GroupEventType
import app.donghaeng.protocol.LowEnergy
import app.donghaeng.protocol.Severity
import app.donghaeng.protocol.Topics
import app.donghaeng.protocol.VehicleEventType
import app.donghaeng.protocol.WaypointArrived
import app.donghaeng.protocol.vehicleEventSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration

/** 같은 차량·같은 종류 이벤트의 중복 억제 (docs/04 `evt:dedup:{vid}:{type}` 120s) */
interface EventDedup {
    /** 이 창 안에서 처음이면 true(기록하고), 이미 있으면 false */
    fun firstWithin(vehicleId: String, type: String, ttl: Duration): Boolean
}

@Component
class RedisEventDedup(private val redis: StringRedisTemplate) : EventDedup {
    override fun firstWithin(vehicleId: String, type: String, ttl: Duration): Boolean = try {
        redis.opsForValue().setIfAbsent("evt:dedup:$vehicleId:$type", "1", ttl) ?: true
    } catch (e: Exception) {
        log.warn("redis dedup failed → allow cause={}", e.javaClass.simpleName)
        true
    }

    companion object {
        private val log = LoggerFactory.getLogger(RedisEventDedup::class.java)
    }
}

/**
 * 차량 이벤트 정규화 (T26, docs/06 §3): 소속 그룹 확인 → 중복 억제 → 음성 문구 → 그룹 이벤트 저장(커밋 뒤 MQTT 팬아웃).
 * ROUTE_CHANGED 는 routesync(T28), PRESET_MESSAGE 는 T53 이 맡으므로 여기서는 기록만 하지 않고 건너뛴다.
 */
@Service
class VehicleEventService(
    private val membership: GroupMembershipApi,
    private val identity: IdentityApi,
    private val dedup: EventDedup,
    private val speech: SpeechComposer,
    private val sink: GroupEventSink,
) {
    /** 저장된 그룹 이벤트. 그룹 없음·중복·미지원 종류면 null */
    @Transactional
    fun handle(vehicleId: String, type: VehicleEventType, payload: JsonObject): GroupEvent? {
        val groupId = membership.activeGroupIdOf(vehicleId)
        if (groupId == null) {
            log.debug("vehicle event ignored (no active group) vid={} type={}", vehicleId, type)
            return null
        }
        if (type == VehicleEventType.ROUTE_CHANGED || type == VehicleEventType.PRESET_MESSAGE) {
            log.debug("vehicle event deferred to another module vid={} type={}", vehicleId, type)
            return null
        }
        if (!dedup.firstWithin(vehicleId, type.name, dedupTtl(type))) {
            log.info("vehicle event suppressed (dedup) vid={} type={}", vehicleId, type)
            return null
        }
        val nickname = identity.vehicle(vehicleId)?.nickname ?: vehicleId
        val (severity, text) = describe(type, nickname, payload)
        return sink.record(groupId, GroupEventType.valueOf(type.name), vehicleId, severity, text, payload)
    }

    private fun describe(type: VehicleEventType, nickname: String, payload: JsonObject): Pair<Severity, String?> = when (type) {
        VehicleEventType.LOW_ENERGY -> {
            val p = runCatching { DhJson.json.decodeFromJsonElement(LowEnergy.serializer(), payload) }.getOrNull()
            Severity.WARN to speech.lowEnergy(nickname, p?.kind ?: EnergyKind.FUEL, p?.pct ?: 0)
        }
        VehicleEventType.HARD_BRAKE -> Severity.WARN to speech.hardBrake(nickname)
        VehicleEventType.HAZARD_ON -> Severity.WARN to speech.hazardOn(nickname)
        VehicleEventType.AIRBAG_DEPLOYED -> Severity.ALERT to speech.airbagDeployed(nickname)
        VehicleEventType.WAYPOINT_ARRIVED -> {
            val p = runCatching { DhJson.json.decodeFromJsonElement(WaypointArrived.serializer(), payload) }.getOrNull()
            Severity.INFO to speech.waypointArrived(nickname, p?.name ?: "경유지")
        }
        VehicleEventType.DESTINATION_ARRIVED -> {
            runCatching { DhJson.json.decodeFromJsonElement(DestinationArrived.serializer(), payload) }
            Severity.INFO to speech.destinationArrived(nickname)
        }
        VehicleEventType.STOPPED -> Severity.INFO to speech.stopped(nickname)
        VehicleEventType.RESUMED -> Severity.INFO to speech.resumed(nickname)
        VehicleEventType.ROUTE_CHANGED, VehicleEventType.PRESET_MESSAGE -> Severity.INFO to null
    }

    private fun dedupTtl(type: VehicleEventType): Duration = when (type) {
        VehicleEventType.STOPPED, VehicleEventType.RESUMED -> SHORT_DEDUP
        else -> DEFAULT_DEDUP
    }

    companion object {
        private val log = LoggerFactory.getLogger(VehicleEventService::class.java)
        val DEFAULT_DEDUP: Duration = Duration.ofSeconds(120)
        /** 정차/재출발은 짧게 — 한 번 멈췄다 출발하는 사이클이 2분 안에 끝날 수 있다 */
        val SHORT_DEDUP: Duration = Duration.ofSeconds(20)
    }
}

/** `dh/v1/vehicle/{vid}/event` → [VehicleEventService]. 봉투 `type` 이 차량 이벤트 종류, `data` 가 docs/03 §5.2 본문 */
@Component
class VehicleEventHandler(private val service: VehicleEventService) : MqttInboundHandler {

    override val kind: Topics.VehicleTopicKind = Topics.VehicleTopicKind.EVENT

    override fun handle(vehicleId: String, envelope: Envelope<JsonElement>) {
        val type = VehicleEventType.entries.firstOrNull { it.name == envelope.type }
        if (type == null) {
            log.warn("vehicle event dropped: unknown type={} vid={}", envelope.type, vehicleId)
            return
        }
        val payload = envelope.data as? JsonObject
        if (payload == null) {
            log.warn("vehicle event dropped: body is not an object vid={} type={}", vehicleId, type)
            return
        }
        // 본문 형식 검증(필드 누락 등) — 실패하면 버린다. 본문은 로그에 찍지 않는다
        try {
            DhJson.json.decodeFromJsonElement(vehicleEventSerializer(type), payload)
        } catch (e: Exception) {
            log.warn("vehicle event dropped: bad body vid={} type={} cause={}", vehicleId, type, e.javaClass.simpleName)
            return
        }
        val recorded = service.handle(vehicleId, type, payload)
        if (recorded != null) log.info("vehicle event → group event vid={} type={} eventId={} seq={}", vehicleId, type, recorded.eventId, envelope.seq)
    }

    companion object {
        private val log = LoggerFactory.getLogger(VehicleEventHandler::class.java)
    }
}
