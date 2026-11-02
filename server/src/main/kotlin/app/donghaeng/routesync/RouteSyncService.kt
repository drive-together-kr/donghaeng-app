package app.donghaeng.routesync

import app.donghaeng.common.id.Ids
import app.donghaeng.common.text.TextSanitizer
import app.donghaeng.common.tx.afterCommit
import app.donghaeng.event.GroupEventSink
import app.donghaeng.event.SpeechComposer
import app.donghaeng.group.GroupMembershipApi
import app.donghaeng.group.GroupRouteApi
import app.donghaeng.group.VehicleJoinedGroup
import app.donghaeng.mqtt.MqttInboundHandler
import app.donghaeng.mqtt.MqttPublisher
import app.donghaeng.protocol.Ack
import app.donghaeng.protocol.CmdType
import app.donghaeng.protocol.DhJson
import app.donghaeng.protocol.Envelope
import app.donghaeng.protocol.GroupEventType
import app.donghaeng.protocol.Place
import app.donghaeng.protocol.RouteChanged
import app.donghaeng.protocol.RouteFollowProposal
import app.donghaeng.protocol.Severity
import app.donghaeng.protocol.Topics
import app.donghaeng.protocol.VehicleEventType
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.event.EventListener
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
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

/**
 * 제안 저장소 — Redis(`rs:prp:{id}`), 테스트는 메모리 구현.
 * 계약: [recordAck] 는 **이미 있는 제안에만** 응답을 적고 그 기록을 돌려준다. 모르는(없거나 만료된) 제안이면 아무것도 쓰지 않고 null(C076·C110).
 */
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
        if (m.isEmpty() || !m.containsKey(F_TARGETS)) null else parse(proposalId, m)
    } catch (e: Exception) {
        log.warn("redis read failed op=find cause={}", e.javaClass.simpleName)
        null
    }

    /**
     * 제안 해시가 **있을 때만**(save 가 쓴 `targets` 필드) 응답을 적는다 — Lua 한 번으로 원자적. 예전에는 HSET 을 먼저 해 아무 proposalId 로나 보낸 ACK 가
     * 새 키를 만들고(15분), 그 키를 '대상 없음·전원 응답' 제안으로 읽었다(C076·C110). TTL 은 save 때 한 번만 건다(만료 직전 ACK 가 TTL 없는 키를 만들지 않게).
     */
    override fun recordAck(proposalId: String, vehicleId: String, accepted: Boolean): ProposalRecord? {
        val written = try {
            redis.execute(ACK_SCRIPT, listOf(key(proposalId)), F_TARGETS, F_ACK_PREFIX + vehicleId, accepted.toString())
        } catch (e: Exception) {
            log.warn("redis update failed op=recordAck cause={}", e.javaClass.simpleName)
            null
        }
        if (written != 1L) return null
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

        /** KEYS[1] = 제안 키, ARGV = (targets 필드 이름, ack 필드, 값) → 있으면 HSET 후 1, 없으면 0 */
        private val ACK_SCRIPT = DefaultRedisScript(
            "if redis.call('HEXISTS', KEYS[1], ARGV[1]) == 1 then redis.call('HSET', KEYS[1], ARGV[2], ARGV[3]) return 1 else return 0 end",
            Long::class.javaObjectType,
        )
    }
}

/**
 * 선두 경로 변경의 검증·정리(C105) — 차량이 보낸 값이라 음성 문구·DB 에 넣기 전에 본다.
 * 좌표가 범위 밖이거나 경유지가 [MAX_WAYPOINTS] 개를 넘으면 제안을 만들지 않는다(null). 이름은 제어·서식 문자를 지우고 [MAX_PLACE_NAME] 자로 자르며,
 * 비면 '목적지'/'경유지'. POI ID·주소는 정리해 너무 길면 버린다(그룹 생성 REST 와 같은 상한).
 */
object RouteRules {
    const val MAX_WAYPOINTS = 10
    const val MAX_PLACE_NAME = 60
    const val MAX_POI_ID = 64
    const val MAX_ADDRESS = 200

    /** 음성 문구에 넣는 이름 상한 */
    const val MAX_NAME_IN_SPEECH = 40

    fun normalize(change: RouteChanged): RouteChanged? {
        if (change.waypoints.size > MAX_WAYPOINTS) return null
        val destination = place(change.destination, "목적지") ?: return null
        val waypoints = change.waypoints.map { place(it, "경유지") ?: return null }
        return RouteChanged(destination, waypoints)
    }

    private fun place(p: Place, fallbackName: String): Place? {
        if (p.lat.isNaN() || p.lat !in -90.0..90.0 || p.lon.isNaN() || p.lon !in -180.0..180.0) return null
        return Place(
            lat = p.lat,
            lon = p.lon,
            name = TextSanitizer.clean(p.name, MAX_PLACE_NAME) ?: fallbackName,
            poiId = TextSanitizer.normalize(p.poiId).takeIf { it.isNotEmpty() && it.length <= MAX_POI_ID },
            address = TextSanitizer.normalize(p.address).takeIf { it.isNotEmpty() && it.length <= MAX_ADDRESS },
        )
    }

    /** 문구용 짧은 이름 */
    fun speechName(name: String?): String? = TextSanitizer.clean(name, MAX_NAME_IN_SPEECH)
}

/**
 * 선두 경로 동기화 (T28, docs/03 §5.2 ROUTE_CHANGED → §5.5 ROUTE_FOLLOW_PROPOSAL → §5.6 ACK, docs/06 §2 `routesync`).
 *
 * - 선두차(그룹 leader)의 `ROUTE_CHANGED` 만 받는다. 다른 멤버가 보내면 무시(로그). 값은 [RouteRules] 로 검증·정리한다(C105).
 * - **그룹 경로를 갱신한다(C032)**: [GroupRouteApi.updateRoute] 로 그룹 목적지·경유지를 바꿔(그룹 행 FOR UPDATE, 지금도 방장인지 다시 확인)
 *   늦게 합류한 차의 `GROUP_JOINED`·초대 미리보기·스냅샷 ARRIVED 판정이 최신 경로를 쓴다. 합류한 차에는 [onVehicleJoined] 가 경유지 제안을 다시 보낸다.
 * - 그룹 이벤트 `ROUTE_CHANGED` 를 남긴다(피드용) — **음성 문구는 없다**(`speech = null`, 피드·companion 은 라벨 '경로 변경'). 차량 VoiceAnnouncer 는 문구가 있는
 *   이벤트를 멤버 차에서 읽으므로, 문구를 넣으면 멤버가 경로 변경 하나를 두 문장(알림 + 제안 질문)으로 듣는다(RC-1). 멤버는 제안 cmd 의 문구 하나만 듣는다.
 * - 멤버마다 cmd `ROUTE_FOLLOW_PROPOSAL { proposalId, destination, waypoints, speech }` 를 **커밋 뒤에** 보낸다(C076 — 기록이 롤백되면 나가지 않는다).
 * - ACK 는 제안별로 모아 둔다(15분). 형식이 틀린 ID·모르는 제안·대상이 아닌 차의 ACK 는 버린다(C110). 전원이 답하면 요약 로그.
 */
@Service
class RouteSyncService(
    private val membership: GroupMembershipApi,
    // ServerMqttClient → TopicRouter → RouteChangedHandler → 이 서비스 → MqttPublisher(= ServerMqttClient) 순환을 끊는다
    @Lazy private val publisher: MqttPublisher,
    private val sink: GroupEventSink,
    private val speech: SpeechComposer,
    private val proposals: ProposalStore,
    private val routes: GroupRouteApi,
    private val clock: Clock,
) {
    /** 만든 제안 ID. 그룹 없음·선두 아님·잘못된 경로·대상 없음이면 null */
    @Transactional
    fun onRouteChanged(vehicleId: String, raw: RouteChanged): String? {
        val change = RouteRules.normalize(raw) ?: run {
            // 좌표·이름은 찍지 않는다(규칙 8) — 개수만
            log.warn("route change dropped: invalid route vid={} waypoints={}", vehicleId, raw.waypoints.size)
            return null
        }
        val groupId = membership.activeGroupIdOf(vehicleId) ?: run {
            log.debug("route change ignored (no group) vid={}", vehicleId)
            return null
        }
        val roster = membership.rosterOf(groupId) ?: return null
        if (roster.leaderVehicleId != vehicleId) {
            log.info("route change ignored (not leader) vid={} groupId={}", vehicleId, groupId)
            return null
        }
        if (!routes.updateRoute(groupId, vehicleId, change.destination, change.waypoints)) return null
        val targets = roster.members.map { it.vehicleId }.filter { it != vehicleId }
        val proposalId = Ids.proposal()
        val cmd = proposalCmd(proposalId, change.destination, change.waypoints)
        proposals.save(ProposalRecord(proposalId, groupId, vehicleId, targets, clock.millis()))
        sink.record(
            // 문구 없음(RC-1) — 멤버가 듣는 말은 아래 제안 cmd 의 "…따라갈까요?" 하나
            groupId, GroupEventType.ROUTE_CHANGED, vehicleId, Severity.INFO, speech = null,
            payload = buildJsonObject {
                put("proposalId", proposalId)
                put("destination", DhJson.json.encodeToJsonElement(Place.serializer(), change.destination))
                putJsonArray("waypoints") { change.waypoints.forEach { add(DhJson.json.encodeToJsonElement(Place.serializer(), it)) } }
                putJsonArray("targets") { targets.forEach { add(JsonPrimitive(it)) } }
            },
        )
        afterCommit {
            val sent = targets.count { vid -> publisher.publish(Topics.vehicleCmd(vid), CmdType.ROUTE_FOLLOW_PROPOSAL.name, cmd, RouteFollowProposal.serializer()) }
            log.info("route proposal created proposalId={} groupId={} targets={} sent={} waypoints={}", proposalId, groupId, targets.size, sent, change.waypoints.size)
        }
        return proposalId
    }

    /**
     * 그룹에 막 합류한 차(C032): 그룹 경로에 경유지가 있으면 그 차에만 경로 제안을 다시 보낸다 — 선두는 같은 경로를 한 번만 보내므로 늦게 합류한 차는
     * 경유지를 받을 길이 없었다. group 모듈이 `GROUP_JOINED` cmd 를 보낸 **뒤**(커밋 뒤) 발행하므로 차량이 세션 없이 제안을 버리지 않는다.
     * 경유지가 없으면 보내지 않는다(차량이 `GROUP_JOINED.destination`(최신)으로 합류 직후 안내를 묻는다, T30). 제안 ID 는 새로, 대상은 그 차 하나.
     */
    @EventListener
    fun onVehicleJoined(e: VehicleJoinedGroup) {
        if (e.waypoints.isEmpty() || e.vehicleId == e.leaderVehicleId) return
        val proposalId = Ids.proposal()
        proposals.save(ProposalRecord(proposalId, e.groupId, e.leaderVehicleId, listOf(e.vehicleId), clock.millis()))
        val sent = publisher.publish(Topics.vehicleCmd(e.vehicleId), CmdType.ROUTE_FOLLOW_PROPOSAL.name, proposalCmd(proposalId, e.destination, e.waypoints), RouteFollowProposal.serializer())
        log.info("route proposal re-sent to joiner proposalId={} groupId={} vid={} waypoints={} sent={}", proposalId, e.groupId, e.vehicleId, e.waypoints.size, sent)
    }

    fun onAck(vehicleId: String, ack: Ack) {
        if (!PROPOSAL_ID.matches(ack.proposalId)) {
            // 원문은 찍지 않는다(길이만) — 임의 문자열이 로그·Redis 키를 키우지 않게
            log.info("route ack dropped: malformed proposalId vid={} length={}", vehicleId, ack.proposalId.length)
            return
        }
        val existing = proposals.find(ack.proposalId)
        if (existing == null) {
            log.info("route ack for unknown proposal proposalId={} vid={} accepted={}", ack.proposalId, vehicleId, ack.accepted)
            return
        }
        if (vehicleId !in existing.targets) {
            log.info("route ack dropped: not a target proposalId={} vid={}", ack.proposalId, vehicleId)
            return
        }
        val record = proposals.recordAck(ack.proposalId, vehicleId, ack.accepted) ?: run {
            log.info("route ack for expired proposal proposalId={} vid={}", ack.proposalId, vehicleId)
            return
        }
        log.info("route ack proposalId={} vid={} accepted={} answered={}/{}", ack.proposalId, vehicleId, ack.accepted, record.acks.size, record.targets.size)
        if (record.allAnswered) {
            log.info("route proposal settled proposalId={} accepted={} declined={}", ack.proposalId, record.acks.count { it.value }, record.acks.count { !it.value })
        }
    }

    private fun proposalCmd(proposalId: String, destination: Place, waypoints: List<Place>) = RouteFollowProposal(
        proposalId = proposalId,
        destination = destination,
        waypoints = waypoints,
        speech = speech.routeFollowProposal(RouteRules.speechName(waypoints.lastOrNull()?.name), RouteRules.speechName(destination.name) ?: "목적지"),
    )

    companion object {
        private val log = LoggerFactory.getLogger(RouteSyncService::class.java)

        /** `prp_` + ULID — 서버가 만든 제안 ID 형식 */
        private val PROPOSAL_ID = Regex("^prp_[0-9A-HJKMNP-TV-Z]{26}$")
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
