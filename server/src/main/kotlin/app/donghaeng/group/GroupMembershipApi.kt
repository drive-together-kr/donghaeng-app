package app.donghaeng.group

import app.donghaeng.group.repo.GroupMemberRepository
import app.donghaeng.group.repo.GroupRepository
import app.donghaeng.identity.IdentityApi
import app.donghaeng.protocol.Place
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/** 스냅샷용 멤버 한 줄 (identity 의 별명 + 그룹의 색·역할) */
data class RosterMember(
    val vehicleId: String,
    val nickname: String,
    val color: String,
    val role: String,
    val joinedAtMs: Long,
)

/**
 * 활성 그룹 하나의 명단 (presence 가 Redis 상태와 합쳐 `GroupState` 를 만든다).
 * [destination]·[waypoints] 는 그룹의 **최신** 경로 — 선두 `ROUTE_CHANGED` 로 갱신된다(C032).
 */
data class GroupRoster(
    val groupId: String,
    val name: String,
    val leaderVehicleId: String,
    val destination: Place?,
    val members: List<RosterMember>,
    val waypoints: List<Place> = emptyList(),
)

/**
 * 다른 모듈(mqtt ACL 훅, presence)이 그룹 소속·명단을 묻는 창구 (docs/06 §2: 모듈 간 `XxxApi`).
 * 소속은 Redis 캐시를 먼저 보고 없으면 DB, 명단은 DB(+identity 별명)에서 읽는다.
 */
interface GroupMembershipApi {
    /** 차량의 현재 활성 그룹 ID(캐시 우선 — 자주 부르는 경로용). 없으면 null */
    fun activeGroupIdOf(vehicleId: String): String?

    /**
     * DB(진실) 기준 현재 활성 그룹 ID — 캐시가 다르면 DB 값으로 고친다(read-repair, C101).
     * 캐시가 '아니다'라고 답했을 때 한 번 더 확인하는 용도(MQTT ACL 거부 직전 등). 기본 구현은 [activeGroupIdOf](테스트 대역용).
     */
    fun verifiedActiveGroupIdOf(vehicleId: String): String? = activeGroupIdOf(vehicleId)

    /** 활성 그룹의 명단. 없거나 끝났으면 null */
    fun rosterOf(groupId: String): GroupRoster?

    /** 활성 그룹 전부의 명단 (presence 2초 스냅샷용) */
    fun activeRosters(): List<GroupRoster>
}

@Component
class DefaultGroupMembershipApi(
    private val cache: GroupMembershipCache,
    private val members: GroupMemberRepository,
    private val groups: GroupRepository,
    private val identity: IdentityApi,
) : GroupMembershipApi {

    override fun activeGroupIdOf(vehicleId: String): String? =
        cache.activeGroupOf(vehicleId) ?: members.findActiveMembershipOf(vehicleId)?.groupId

    override fun verifiedActiveGroupIdOf(vehicleId: String): String? {
        val truth = members.findActiveMembershipOf(vehicleId)?.groupId
        val cached = cache.activeGroupOf(vehicleId)
        if (cached != truth) {
            // 그 사이 합류·나가기가 커밋됐으면(두 번 읽은 DB 값이 다름) 그 쓰기의 커밋 뒤 갱신에 맡긴다 — 옛 값으로 덮지 않게
            val again = members.findActiveMembershipOf(vehicleId)?.groupId
            if (again != truth) return again
            // 쓰기 실패·커밋 뒤 순서 역전으로 남은 옛 값 — 진실(DB)로 맞춘다. ID 만 남긴다
            log.warn("membership cache repaired vehicleId={} cached={} db={}", vehicleId, cached, truth)
            cache.repair(vehicleId, cached, truth)
        }
        return truth
    }

    override fun rosterOf(groupId: String): GroupRoster? {
        val group = groups.findById(groupId)?.takeIf { it.active } ?: return null
        return roster(group.groupId, group.name, group.leaderVehicleId, group.destination, group.waypoints)
    }

    override fun activeRosters(): List<GroupRoster> =
        groups.findActive().map { g -> roster(g.groupId, g.name, g.leaderVehicleId, g.destination, g.waypoints) }

    private fun roster(groupId: String, name: String, leaderVehicleId: String, destination: Place?, waypoints: List<Place>): GroupRoster {
        val current = members.listActive(groupId)
        val info = identity.vehicles(current.map { it.vehicleId })
        return GroupRoster(
            groupId = groupId,
            name = name,
            leaderVehicleId = leaderVehicleId,
            destination = destination,
            members = current.map { m ->
                RosterMember(
                    vehicleId = m.vehicleId,
                    nickname = info[m.vehicleId]?.nickname ?: m.vehicleId,
                    color = m.color,
                    role = m.role.name,
                    joinedAtMs = m.joinedAt.toEpochMilli(),
                )
            },
            waypoints = waypoints,
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(DefaultGroupMembershipApi::class.java)
    }
}
