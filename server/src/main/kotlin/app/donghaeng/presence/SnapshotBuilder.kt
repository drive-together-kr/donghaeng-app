package app.donghaeng.presence

import app.donghaeng.common.config.DhProperties
import app.donghaeng.group.GroupMembershipApi
import app.donghaeng.group.GroupRoster
import app.donghaeng.protocol.GroupState
import app.donghaeng.protocol.MemberState
import app.donghaeng.protocol.MemberStatus
import org.springframework.stereotype.Component
import java.time.Clock
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 그룹 명단 + Redis 상태 → `GroupState` 스냅샷 (docs/03 §5.3, T24).
 *
 * 멤버 판정 규칙:
 * - `online`: presence 토픽 값이 있으면 그 값, 없으면 "텔레메트리가 [DhProperties.Presence.onlineByTelemetryMs] 안에 왔는가".
 * - 시각은 모두 **서버 수신 시각**이다([TelemetryIngestor] 가 서버 시계로 적는다, C099) — 차량 시계가 틀려도 판정이 흔들리지 않는다.
 * - `status`: 상태 없음·오프라인·마지막 위치가 [DhProperties.Presence.offlineAfterMs] 보다 오래됨 → `OFFLINE`;
 *   목적지 반경 [DhProperties.Presence.arrivedRadiusM] 안 + 거의 정지 → `ARRIVED`(docs/10 R4 보조 판정);
 *   속도 < 1 km/h 가 [DhProperties.Presence.stoppedAfterMs] 이상 지속 → `STOPPED`; 그 외 `DRIVING`.
 * - `distanceToLeaderM`: 선두차 자신은 0, 둘 다 위치가 있을 때 Haversine, 아니면 null.
 */
@Component
class SnapshotBuilder(
    private val store: PresenceStore,
    private val props: DhProperties,
    private val clock: Clock,
) {
    fun build(roster: GroupRoster): GroupState {
        val now = clock.millis()
        val p = props.presence
        val states = roster.members.associate { it.vehicleId to store.stateOf(it.vehicleId) }
        val leader = states[roster.leaderVehicleId]
        val members = roster.members.map { m ->
            val s = states[m.vehicleId]
            val online = store.onlineOf(m.vehicleId) ?: (s != null && now - s.ts <= p.onlineByTelemetryMs)
            val age = s?.let { (now - it.ts).coerceAtLeast(0) }
            val speed = s?.speedKph ?: 0f
            val status = when {
                s == null || !online || age!! > p.offlineAfterMs -> MemberStatus.OFFLINE
                roster.destination != null && distanceM(s.lat, s.lon, roster.destination.lat, roster.destination.lon) <= p.arrivedRadiusM && speed < ARRIVED_MAX_SPEED_KPH -> MemberStatus.ARRIVED
                s.stoppedSinceMs != null && now - s.stoppedSinceMs >= p.stoppedAfterMs -> MemberStatus.STOPPED
                else -> MemberStatus.DRIVING
            }
            val distanceToLeader = when {
                s == null -> null
                m.vehicleId == roster.leaderVehicleId -> 0
                leader == null -> null
                else -> distanceM(s.lat, s.lon, leader.lat, leader.lon).roundToInt()
            }
            MemberState(
                vehicleId = m.vehicleId,
                nickname = m.nickname,
                color = m.color,
                online = online,
                lat = s?.lat,
                lon = s?.lon,
                heading = s?.heading,
                speedKph = s?.speedKph,
                ageMs = age,
                eta = s?.eta,
                energy = s?.energy,
                distanceToLeaderM = distanceToLeader,
                status = status,
            )
        }
        return GroupState(groupId = roster.groupId, leaderVehicleId = roster.leaderVehicleId, members = members)
    }

    companion object {
        const val ARRIVED_MAX_SPEED_KPH = 3f

        /** Haversine 거리(m) */
        fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6_371_000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val h = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
            return 2 * r * atan2(sqrt(h), sqrt(1 - h))
        }
    }
}

/** 다른 모듈(group 의 `GET /groups/{id}`, event 의 급제동 대상·늦음 판정)이 presence 를 묻는 창구 (docs/06 §2 XxxApi) */
interface PresenceApi {
    /** 활성 그룹의 현재 스냅샷. 그룹이 없거나 끝났으면 null */
    fun snapshotOf(groupId: String): GroupState?

    /**
     * 그 차의 '봉투 ts → 서버 수신' 최소 지연(ms, [ClockLagTracker]) — 차 시계와 서버 시계의 차이 추정. 모르면 null.
     * 위험 이벤트 늦음 판정(계약 4)에서 늦은 시계를 보정하는 데 쓴다. 기본 구현은 null(테스트 대역용).
     */
    fun clockLagMs(vehicleId: String): Long? = null
}

@Component
class DefaultPresenceApi(
    private val membership: GroupMembershipApi,
    private val builder: SnapshotBuilder,
    private val lags: ClockLagTracker,
    private val clock: Clock,
) : PresenceApi {
    override fun snapshotOf(groupId: String): GroupState? = membership.rosterOf(groupId)?.let { builder.build(it) }

    override fun clockLagMs(vehicleId: String): Long? = lags.lagMs(vehicleId, clock.millis())
}
