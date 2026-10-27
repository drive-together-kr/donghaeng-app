package app.donghaeng.group

import app.donghaeng.group.repo.GroupMemberRepository
import app.donghaeng.group.repo.GroupRepository
import app.donghaeng.identity.IdentityApi
import app.donghaeng.protocol.Place
import org.springframework.stereotype.Component

/** 스냅샷용 멤버 한 줄 (identity 의 별명 + 그룹의 색·역할) */
data class RosterMember(
    val vehicleId: String,
    val nickname: String,
    val color: String,
    val role: String,
    val joinedAtMs: Long,
)

/** 활성 그룹 하나의 명단 (presence 가 Redis 상태와 합쳐 `GroupState` 를 만든다) */
data class GroupRoster(
    val groupId: String,
    val name: String,
    val leaderVehicleId: String,
    val destination: Place?,
    val members: List<RosterMember>,
)

/**
 * 다른 모듈(mqtt ACL 훅, presence)이 그룹 소속·명단을 묻는 창구 (docs/06 §2: 모듈 간 `XxxApi`).
 * 소속은 Redis 캐시를 먼저 보고 없으면 DB, 명단은 DB(+identity 별명)에서 읽는다.
 */
interface GroupMembershipApi {
    /** 차량의 현재 활성 그룹 ID. 없으면 null */
    fun activeGroupIdOf(vehicleId: String): String?

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

    override fun rosterOf(groupId: String): GroupRoster? {
        val group = groups.findById(groupId)?.takeIf { it.active } ?: return null
        return roster(group.groupId, group.name, group.leaderVehicleId, group.destination)
    }

    override fun activeRosters(): List<GroupRoster> =
        groups.findActive().map { g -> roster(g.groupId, g.name, g.leaderVehicleId, g.destination) }

    private fun roster(groupId: String, name: String, leaderVehicleId: String, destination: Place?): GroupRoster {
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
        )
    }
}
