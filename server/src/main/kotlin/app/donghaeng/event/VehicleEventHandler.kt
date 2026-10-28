package app.donghaeng.event

import app.donghaeng.group.GroupMembershipApi
import app.donghaeng.identity.IdentityApi
import app.donghaeng.mqtt.MqttInboundHandler
import app.donghaeng.presence.PresenceApi
import app.donghaeng.protocol.DestinationArrived
import app.donghaeng.protocol.DhJson
import app.donghaeng.protocol.EnergyKind
import app.donghaeng.protocol.Envelope
import app.donghaeng.protocol.EventAudience
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
import org.springframework.transaction.support.TransactionOperations
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
 * 차량 이벤트 정규화 (T26, docs/06 §3): 소속 그룹 확인 → 중복 억제 → 음성 문구 → 수신 대상 → 그룹 이벤트 저장(커밋 뒤 MQTT 팬아웃).
 * ROUTE_CHANGED 는 routesync(T28), PRESET_MESSAGE 는 T53 이 맡으므로 여기서는 기록만 하지 않고 건너뛴다.
 *
 * 수신 대상(docs/03 §5.4 `audience`): `HARD_BRAKE` 의 문구는 "앞쪽 ○○ 차가 급제동"이라 **출처보다 경로상 뒤에 있는 차**만 들어야 한다.
 * 이벤트는 그룹 토픽 하나로 나가므로 서버가 지금 스냅샷([PresenceApi.snapshotOf], 패널 간격과 같은 `distanceToLeaderM`)으로
 * 뒤차 목록을 정해 `audience` 에 싣고, 차량 앱·companion 은 [EventAudience.isForMe] 로 자기 차가 대상일 때만 알린다.
 * 그 밖의 종류는 audience 없음(= 그룹 전체).
 *
 * 트랜잭션([tx])은 **저장([GroupEventSink.record])에만** 건다. 앞의 조회(소속·별명·수신 대상 스냅샷)는 트랜잭션 밖에서 한다 —
 * PostgreSQL 은 트랜잭션 안에서 문장 하나가 실패하면 그 트랜잭션의 나머지 문장을 모두 거부하므로(`25P02 current transaction is aborted`),
 * 스냅샷 읽기(명단 JDBC)를 같은 트랜잭션에서 하면 그 실패를 catch 해도 뒤따르는 INSERT 가 실패해 급제동이 통째로 사라진다(PresenceIT 로 재현).
 * MQTT 수신 스레드([VehicleEventHandler])가 트랜잭션 없이 부른다.
 */
@Service
class VehicleEventService(
    private val membership: GroupMembershipApi,
    private val identity: IdentityApi,
    private val dedup: EventDedup,
    private val speech: SpeechComposer,
    private val sink: GroupEventSink,
    private val presence: PresenceApi,
    private val tx: TransactionOperations,
) {
    /** 저장된 그룹 이벤트. 그룹 없음·중복·미지원 종류면 null */
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
        // 저장 트랜잭션 밖에서 읽는다(클래스 설명) — 실패해도 이벤트는 audience 없이(그룹 전체) 저장된다
        val audience = audienceOf(groupId, vehicleId, type)
        return tx.execute { sink.record(groupId, GroupEventType.valueOf(type.name), vehicleId, severity, text, payload, audience) }
    }

    /**
     * 앞뒤로 대상을 고르는 종류([EventAudience.BEHIND_ONLY_TYPES] — HARD_BRAKE)면 출처보다 뒤에 있는 멤버(출처 제외, 위치 모르는 멤버 포함,
     * 뒤차가 없으면 빈 목록 = 아무도 알리지 않음). 그 밖 종류는 null.
     * - 스냅샷 없음(그룹이 막 끝남)·읽기 실패(명단 DB 오류) → null(그룹 전체 — 차량이 자기 스냅샷으로 다시 판정한다) + WARN.
     * - Redis 장애는 여기까지 오지 않는다: `RedisPresenceStore` 가 안에서 삼켜 모든 멤버의 위치가 '모름'이 되므로 **출처를 뺀 전원**이 대상이 된다.
     */
    private fun audienceOf(groupId: String, vehicleId: String, type: VehicleEventType): List<String>? {
        if (!EventAudience.isBehindOnly(type.name)) return null
        val state = try {
            presence.snapshotOf(groupId)
        } catch (e: Exception) {
            log.warn("event audience: snapshot failed → whole group groupId={} type={} cause={}", groupId, type, e.javaClass.simpleName)
            return null
        }
        if (state == null) {
            log.warn("event audience: no snapshot → whole group groupId={} type={}", groupId, type)
            return null
        }
        val behind = EventAudience.behind(vehicleId, state.members)
        val others = state.members.count { it.vehicleId != vehicleId }
        val unknown = state.members.count { it.vehicleId != vehicleId && it.distanceToLeaderM == null }
        val sourceKnown = state.members.firstOrNull { it.vehicleId == vehicleId }?.distanceToLeaderM != null
        // 좌표·닉네임 없이 수만 남긴다 (CLAUDE.md 규칙 8)
        log.info("event audience groupId={} vid={} type={} behind={}/{} unknownPos={} sourceKnown={}", groupId, vehicleId, type, behind.size, others, unknown, sourceKnown)
        return behind
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
