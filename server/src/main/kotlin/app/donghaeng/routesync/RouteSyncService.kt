package app.donghaeng.routesync

import app.donghaeng.common.id.Ids
import app.donghaeng.event.GroupEventSink
import app.donghaeng.event.SpeechComposer
import app.donghaeng.group.GroupMembershipApi
import app.donghaeng.mqtt.MqttInboundHandler
import app.donghaeng.mqtt.MqttPublisher
import app.donghaeng.protocol.Ack
import app.donghaeng.protocol.CmdType
import app.donghaeng.protocol.DhJson
import app.donghaeng.protocol.Envelope
import app.donghaeng.protocol.GroupEventType
import app.donghaeng.protocol.RouteChanged
import app.donghaeng.protocol.RouteFollowProposal
import app.donghaeng.protocol.Severity
import app.donghaeng.protocol.Topics
import app.donghaeng.protocol.VehicleEventType
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Lazy
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration

/** 제안 한 건의 진행 상태 (docs/03 §5.5·§5.6) */
data class ProposalRecord(
    val proposalId: String,
    val groupId: String,
    val leaderVehicleId: String,
    val targets: List<String>,
    val createdAtMs: Long,
    /** vehicleId → 수락 여부 */
    val acks: Map<String, Boolean> = emptyMap(),
) {
    val allAnswered: Boolean get() = targets.all { it in acks }
}

/** 제안 저장소 — Redis(`rs:prp:{id}`), 테스트는 메모리 구현 */
interface ProposalStore {
    fun save(record: ProposalRecord)
    fun find(proposalId: String): ProposalRecord?
    fun recordAck(proposalId: String, vehicleId: String, accepted: Boolean): ProposalRecord?
}

@Component
class RedisProposalStore(private val redis: StringRedisTemplate) : ProposalStore {
    override fun save(record: ProposalRecord) = safely("save") {
        val key = key(record.proposalId)
        redis.opsForHash<String, String>().putAll(
            key,
            mapOf(F_GROUP to record.groupId, F_LEADER to record.leaderVehicleId, F_TARGETS to record.targets.joinToString(","), F_CREATED to record.createdAtMs.toString()),
        )
        redis.expire(key, TTL)
    }

    override fun find(proposalId: String): ProposalRecord? = try {
        val m = redis.opsForHash<String, String>().entries(key(proposalId))
        if (m.isEmpty()) null else parse(proposalId, m)
    } catch (e: Exception) {
        log.warn("redis read failed op=find cause={}", e.javaClass.simpleName)
        null
    }

    override fun recordAck(proposalId: String, vehicleId: String, accepted: Boolean): ProposalRecord? {
        safely("recordAck") {
            redis.opsForHash<String, String>().put(key(proposalId), F_ACK_PREFIX + vehicleId, accepted.toString())
            redis.expire(key(proposalId), TTL)
        }
        return find(proposalId)
    }

    private fun parse(id: String, m: Map<String, String>): ProposalRecord = ProposalRecord(
        proposalId = id,
        groupId = m[F_GROUP].orEmpty(),
        leaderVehicleId = m[F_LEADER].orEmpty(),
        targets = m[F_TARGETS].orEmpty().split(',').filter { it.isNotBlank() },
        createdAtMs = m[F_CREATED]?.toLongOrNull() ?: 0L,
        acks = m.filterKeys { it.startsWith(F_ACK_PREFIX) }.map { (k, v) -> k.removePrefix(F_ACK_PREFIX) to v.toBoolean() }.toMap(),
    )

    private inline fun safely(op: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            log.warn("redis update failed op={} cause={}", op, e.javaClass.simpleName)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(RedisProposalStore::class.java)
        private val TTL: Duration = Duration.ofMinutes(15)
        fun key(id: String) = "rs:prp:$id"
        const val F_GROUP = "groupId"
        const val F_LEADER = "leader"
        const val F_TARGETS = "targets"
        const val F_CREATED = "createdAt"
        const val F_ACK_PREFIX = "ack:"
    }
}

/**
 * 선두 경로 동기화 (T28, docs/03 §5.2 ROUTE_CHANGED → §5.5 ROUTE_FOLLOW_PROPOSAL → §5.6 ACK, docs/06 §2 `routesync`).
 *
 * - 선두차(그룹 leader)의 `ROUTE_CHANGED` 만 받는다. 다른 멤버가 보내면 무시(로그).
 * - 멤버마다 cmd `ROUTE_FOLLOW_PROPOSAL { proposalId, destination, waypoints, speech }` 를 보낸다. 그룹 이벤트 `ROUTE_CHANGED` 도 남긴다(피드용, 음성 문구는 없음 — 멤버는 제안 cmd 의 문구를 듣는다).
 * - ACK 는 제안별로 모아 둔다(15분). 전원이 답하면 요약 로그.
 */
@Service
class RouteSyncService(
    private val membership: GroupMembershipApi,
    // ServerMqttClient → TopicRouter → RouteChangedHandler → 이 서비스 → MqttPublisher(= ServerMqttClient) 순환을 끊는다
    @Lazy private val publisher: MqttPublisher,
    private val sink: GroupEventSink,
    private val speech: SpeechComposer,
    private val proposals: ProposalStore,
    private val clock: Clock,
) {
    /** 만든 제안 ID. 그룹 없음·선두 아님·대상 없음이면 null */
    @Transactional
    fun onRouteChanged(vehicleId: String, change: RouteChanged): String? {
        val groupId = membership.activeGroupIdOf(vehicleId) ?: run {
            log.debug("route change ignored (no group) vid={}", vehicleId)
            return null
        }
        val roster = membership.rosterOf(groupId) ?: return null
        if (roster.leaderVehicleId != vehicleId) {
            log.info("route change ignored (not leader) vid={} groupId={}", vehicleId, groupId)
            return null
        }
        val targets = roster.members.map { it.vehicleId }.filter { it != vehicleId }
        val proposalId = Ids.proposal()
        val text = speech.routeFollowProposal(change.waypoints.lastOrNull()?.name, change.destination.name)
        proposals.save(ProposalRecord(proposalId, groupId, vehicleId, targets, clock.millis()))
        val cmd = RouteFollowProposal(proposalId = proposalId, destination = change.destination, waypoints = change.waypoints, speech = text)
        var sent = 0
        targets.forEach { vid ->
            if (publisher.publish(Topics.vehicleCmd(vid), CmdType.ROUTE_FOLLOW_PROPOSAL.name, cmd, RouteFollowProposal.serializer())) sent++
        }
        sink.record(
            groupId, GroupEventType.ROUTE_CHANGED, vehicleId, Severity.INFO, speech = null,
            payload = buildJsonObject {
                put("proposalId", proposalId)
                put("destination", DhJson.json.encodeToJsonElement(app.donghaeng.protocol.Place.serializer(), change.destination))
                putJsonArray("waypoints") { change.waypoints.forEach { add(DhJson.json.encodeToJsonElement(app.donghaeng.protocol.Place.serializer(), it)) } }
                putJsonArray("targets") { targets.forEach { add(JsonPrimitive(it)) } }
            },
        )
        log.info("route proposal created proposalId={} groupId={} targets={} sent={} waypoints={}", proposalId, groupId, targets.size, sent, change.waypoints.size)
        return proposalId
    }

    fun onAck(vehicleId: String, ack: Ack) {
        val record = proposals.recordAck(ack.proposalId, vehicleId, ack.accepted)
        if (record == null) {
            log.info("route ack for unknown proposal proposalId={} vid={} accepted={}", ack.proposalId, vehicleId, ack.accepted)
            return
        }
        log.info("route ack proposalId={} vid={} accepted={} answered={}/{}", ack.proposalId, vehicleId, ack.accepted, record.acks.size, record.targets.size)
        if (record.allAnswered) {
            log.info("route proposal settled proposalId={} accepted={} declined={}", ack.proposalId, record.acks.count { it.value }, record.acks.count { !it.value })
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(RouteSyncService::class.java)
    }
}

/** `…/event` 중 `ROUTE_CHANGED` 만 routesync 로 (다른 종류는 event 모듈 `VehicleEventHandler`) */
@Component
class RouteChangedHandler(private val service: RouteSyncService) : MqttInboundHandler {
    override val kind: Topics.VehicleTopicKind = Topics.VehicleTopicKind.EVENT

    override fun handle(vehicleId: String, envelope: Envelope<JsonElement>) {
        if (envelope.type != VehicleEventType.ROUTE_CHANGED.name) return
        val change = try {
            DhJson.decodeData(envelope, RouteChanged.serializer()).data
        } catch (e: Exception) {
            log.warn("route change dropped: bad body vid={} cause={}", vehicleId, e.javaClass.simpleName)
            return
        }
        service.onRouteChanged(vehicleId, change)
    }

    companion object {
        private val log = LoggerFactory.getLogger(RouteChangedHandler::class.java)
    }
}

/** `…/ack` → 제안 집계 */
@Component
class AckHandler(private val service: RouteSyncService) : MqttInboundHandler {
    override val kind: Topics.VehicleTopicKind = Topics.VehicleTopicKind.ACK

    override fun handle(vehicleId: String, envelope: Envelope<JsonElement>) {
        val ack = try {
            DhJson.decodeData(envelope, Ack.serializer()).data
        } catch (e: Exception) {
            log.warn("ack dropped: bad body vid={} cause={}", vehicleId, e.javaClass.simpleName)
            return
        }
        service.onAck(vehicleId, ack)
    }

    companion object {
        private val log = LoggerFactory.getLogger(AckHandler::class.java)
    }
}
