package app.donghaeng.event

import app.donghaeng.common.text.TextSanitizer
import app.donghaeng.group.GroupAccessApi
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
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionOperations
import java.time.Clock
import java.time.Duration

/** 같은 차량·같은 종류 이벤트의 중복 억제 (docs/04 `evt:dedup:{vid}:{type}` 120s) */
interface EventDedup {
    /** 이 창 안에서 처음이면 true(기록하고), 이미 있으면 false */
    fun firstWithin(vehicleId: String, type: String, ttl: Duration): Boolean

    /**
     * [firstWithin] 으로 잡은 창을 푼다 — 기록이 실패·취소됐을 때(C107). 안 풀면 DB 가 잠깐 실패한 급제동·에어백 뒤 같은 종류가 120 s 동안 억제됐다.
     */
    fun release(vehicleId: String, type: String) {}
}

@Component
class RedisEventDedup(private val redis: StringRedisTemplate) : EventDedup {
    override fun firstWithin(vehicleId: String, type: String, ttl: Duration): Boolean = try {
        redis.opsForValue().setIfAbsent(key(vehicleId, type), "1", ttl) ?: true
    } catch (e: Exception) {
        log.warn("redis dedup failed → allow cause={}", e.javaClass.simpleName)
        true
    }

    override fun release(vehicleId: String, type: String) {
        try {
            redis.delete(key(vehicleId, type))
        } catch (e: Exception) {
            log.warn("redis dedup release failed cause={}", e.javaClass.simpleName)
        }
    }

    private fun key(vehicleId: String, type: String) = "evt:dedup:$vehicleId:$type"

    companion object {
        private val log = LoggerFactory.getLogger(RedisEventDedup::class.java)
    }
}

/**
 * 차량 이벤트 정규화 (T26, docs/06 §3): 소속 그룹 확인 → 본문 검증·정리 → 늦은 위험 이벤트 판정 → 중복 억제 → 음성 문구 → 수신 대상 → 그룹 이벤트 저장(커밋 뒤 MQTT 팬아웃).
 * ROUTE_CHANGED 는 routesync(T28), PRESET_MESSAGE 는 T53 이 맡으므로 여기서는 기록만 하지 않고 건너뛴다.
 *
 * 수신 대상(docs/03 §5.4 `audience`): `HARD_BRAKE` 의 문구는 "앞쪽 ○○ 차가 급제동"이라 **출처보다 경로상 뒤에 있는 차**만 들어야 한다.
 * 이벤트는 그룹 토픽 하나로 나가므로 서버가 지금 스냅샷([PresenceApi.snapshotOf], 패널 간격과 같은 `distanceToLeaderM`)으로
 * 뒤차 목록을 정해 `audience` 에 싣고, 차량 앱·companion 은 [EventAudience.isForMe] 로 자기 차가 대상일 때만 알린다.
 * 그 밖의 종류는 audience 없음(= 그룹 전체).
 *
 * **늦은 위험 이벤트(C017, 계약 4 — docs/03 §5.2)**: 망이 끊긴 동안 차량 MQTT 클라이언트가 쌓아 둔 `HARD_BRAKE`·`AIRBAG_DEPLOYED` 가 재연결 뒤 몇 분 늦게
 * 오면, 서버가 '지금' 이벤트로 팬아웃해 뒤차가 엉뚱한 때 경고를 들었다. 지연 = 서버 수신 시각 − 봉투 `ts`(차량은 ClockSync 가 맞으면 서버 시계로 찍는다)
 * − 그 차의 시계 지연 기준값([PresenceApi.clockLagMs] — 최근 텔레메트리의 최소 지연, 양수일 때만 빼서 **늦은 시계 차의 새 이벤트를 늦다고 오판하지 않게**)이
 * [LATE_THRESHOLD](60 s)를 넘으면 기록만 하고(speech 없음·`audience = []` → 어느 화면·음성에도 안 나옴) 로그 `late` 를 남긴다. 중복 억제 창은 쓰지 않는다
 * (재연결 직후의 **새** 급제동이 억제되지 않게).
 *
 * 트랜잭션([tx])은 **저장에만** 건다. 앞의 조회(소속·별명·수신 대상 스냅샷)는 트랜잭션 밖에서 한다 —
 * PostgreSQL 은 트랜잭션 안에서 문장 하나가 실패하면 그 트랜잭션의 나머지 문장을 모두 거부하므로(`25P02 current transaction is aborted`),
 * 스냅샷 읽기(명단 JDBC)를 같은 트랜잭션에서 하면 그 실패를 catch 해도 뒤따르는 INSERT 가 실패해 급제동이 통째로 사라진다(PresenceIT 로 재현).
 * 저장 트랜잭션 안에서는 [GroupAccessApi.lockActiveGroupOfVehicle] 로 **DB 의** 현재 그룹 행을 `FOR SHARE` 로 잡고 진행 중·멤버인지 다시 본다(C107) —
 * 종료가 먼저 커밋됐거나 Redis 소속 캐시가 옛 값이면 기록하지 않는다. 기록이 실패·취소되면 중복 억제 창을 푼다.
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
    private val access: GroupAccessApi,
    private val tx: TransactionOperations,
    private val clock: Clock,
) {
    /**
     * 저장된 그룹 이벤트. 그룹 없음·중복·미지원 종류·잘못된 본문이면 null.
     * @param sentAtMs 봉투 `ts`(차량이 보낸 시각, 계약 4 — ClockSync 가 맞으면 서버 시계). 없으면 늦음 판정을 하지 않는다
     */
    fun handle(vehicleId: String, type: VehicleEventType, payload: JsonObject, sentAtMs: Long? = null): GroupEvent? {
        val receivedAtMs = clock.millis()
        val cachedGroupId = membership.activeGroupIdOf(vehicleId)
        if (cachedGroupId == null) {
            log.debug("vehicle event ignored (no active group) vid={} type={}", vehicleId, type)
            return null
        }
        if (type == VehicleEventType.ROUTE_CHANGED || type == VehicleEventType.PRESET_MESSAGE) {
            log.debug("vehicle event deferred to another module vid={} type={}", vehicleId, type)
            return null
        }
        val body = sanitize(vehicleId, type, payload) ?: return null
        val lateMs = lateMs(vehicleId, type, sentAtMs, receivedAtMs)
        if (lateMs != null) {
            // 좌표·닉네임 없이 — 늦은 위험 이벤트는 기록만(음성·카드 없음)
            log.warn("vehicle event late → recorded without fan-out vid={} type={} lateMs={}", vehicleId, type, lateMs)
            val severity = if (type == VehicleEventType.AIRBAG_DEPLOYED) Severity.ALERT else Severity.WARN
            return record(vehicleId, type, cachedGroupId, severity, text = null, body, audience = emptyList())
        }
        if (!dedup.firstWithin(vehicleId, type.name, dedupTtl(type))) {
            log.info("vehicle event suppressed (dedup) vid={} type={}", vehicleId, type)
            return null
        }
        try {
            val nickname = identity.vehicle(vehicleId)?.nickname ?: vehicleId
            val (severity, text) = describe(type, nickname, body)
            // 저장 트랜잭션 밖에서 읽는다(클래스 설명) — 실패해도 이벤트는 audience 없이(그룹 전체) 저장된다
            val audience = audienceOf(cachedGroupId, vehicleId, type)
            val recorded = record(vehicleId, type, cachedGroupId, severity, text, body, audience)
            if (recorded == null) dedup.release(vehicleId, type.name)
            return recorded
        } catch (e: Exception) {
            // DB 오류 등으로 저장하지 못했다 — 창을 풀어 다음 같은 이벤트(차량이 다시 보내는 경우)가 억제되지 않게
            dedup.release(vehicleId, type.name)
            throw e
        }
    }

    /** 저장 트랜잭션: DB 의 현재 그룹을 잡고(종료·나가기와 직렬화) 기록. 그룹이 없으면 null */
    private fun record(
        vehicleId: String,
        type: VehicleEventType,
        cachedGroupId: String,
        severity: Severity,
        text: String?,
        body: JsonObject,
        audience: List<String>?,
    ): GroupEvent? {
        var dbGroupId: String? = null
        val recorded = tx.execute TxBlock@{
            val groupId = access.lockActiveGroupOfVehicle(vehicleId) ?: return@TxBlock null
            dbGroupId = groupId
            val effectiveAudience = when {
                groupId == cachedGroupId -> audience
                // 늦은 이벤트(문구 없음, [])는 어느 그룹이든 아무에게도 알리지 않는다
                text == null -> audience
                // 캐시가 다른 그룹을 가리켰다면 그 스냅샷으로 고른 대상은 틀리다 — 그룹 전체로(차량이 자기 스냅샷으로 다시 판정)
                else -> null
            }
            sink.record(groupId, GroupEventType.valueOf(type.name), vehicleId, severity, text, body, effectiveAudience)
        }
        if (dbGroupId != cachedGroupId) {
            log.warn("vehicle event group mismatch vid={} type={} cached={} db={}", vehicleId, type, cachedGroupId, dbGroupId)
            membership.verifiedActiveGroupIdOf(vehicleId) // 캐시를 DB 로 고친다(C101)
        }
        if (recorded == null) log.info("vehicle event not recorded (group ended or left) vid={} type={}", vehicleId, type)
        return recorded
    }

    /**
     * 본문 검증·정리(C105): 차량이 보낸 문자열·숫자를 음성 문구에 넣기 전에 — 범위 밖 `LOW_ENERGY.pct` 는 버리고(null),
     * 경유지·목적지 이름은 제어·서식 문자를 지우고 [MAX_PLACE_NAME_IN_SPEECH] 자로 자른다(저장되는 payload 도 정리한 값).
     */
    private fun sanitize(vehicleId: String, type: VehicleEventType, payload: JsonObject): JsonObject? = when (type) {
        VehicleEventType.LOW_ENERGY -> {
            val p = runCatching { DhJson.json.decodeFromJsonElement(LowEnergy.serializer(), payload) }.getOrNull()
            if (p == null || p.pct !in 0..100) {
                log.warn("vehicle event dropped: LOW_ENERGY pct out of range vid={} pct={}", vehicleId, p?.pct)
                null
            } else {
                payload
            }
        }
        VehicleEventType.WAYPOINT_ARRIVED, VehicleEventType.DESTINATION_ARRIVED -> {
            val raw = (payload["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val clean = TextSanitizer.clean(raw, MAX_PLACE_NAME_IN_SPEECH)
            if (clean == raw) payload else JsonObject(payload + ("name" to JsonPrimitive(clean ?: "")))
        }
        else -> payload
    }

    /** 늦은 위험 이벤트면 보정한 지연(ms), 아니면 null (클래스 설명) */
    private fun lateMs(vehicleId: String, type: VehicleEventType, sentAtMs: Long?, receivedAtMs: Long): Long? {
        if (type !in TIME_CRITICAL || sentAtMs == null) return null
        val lag = (presence.clockLagMs(vehicleId) ?: 0L).coerceAtLeast(0L)
        val late = receivedAtMs - sentAtMs - lag
        return late.takeIf { it > LATE_THRESHOLD.toMillis() }
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
            Severity.INFO to speech.waypointArrived(nickname, p?.name?.takeIf { it.isNotBlank() } ?: "경유지")
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

        /** 이보다 늦게 도착한 위험 이벤트는 기록만 한다(계약 4) */
        val LATE_THRESHOLD: Duration = Duration.ofSeconds(60)

        /** 늦음 판정 대상 — 그 순간에만 의미 있는 위험 경고 */
        val TIME_CRITICAL: Set<VehicleEventType> = setOf(VehicleEventType.HARD_BRAKE, VehicleEventType.AIRBAG_DEPLOYED)

        /** 음성 문구에 넣는 경유지·목적지 이름 상한(C105) */
        const val MAX_PLACE_NAME_IN_SPEECH = 40
    }
}

/** `dh/v1/vehicle/{vid}/event` → [VehicleEventService]. 봉투 `type` 이 차량 이벤트 종류, `data` 가 docs/03 §5.2 본문, `ts` 는 늦음 판정에(계약 4) */
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
        val recorded = service.handle(vehicleId, type, payload, sentAtMs = envelope.ts)
        if (recorded != null) log.info("vehicle event → group event vid={} type={} eventId={} seq={}", vehicleId, type, recorded.eventId, envelope.seq)
    }

    companion object {
        private val log = LoggerFactory.getLogger(VehicleEventHandler::class.java)
    }
}
