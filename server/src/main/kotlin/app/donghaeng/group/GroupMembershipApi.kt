package app.donghaeng.group

import app.donghaeng.group.repo.GroupMemberRepository
import org.springframework.stereotype.Component

/**
 * 다른 모듈(mqtt ACL 훅, presence)이 "이 차량이 지금 어느 그룹에 있나"를 묻는 창구 (docs/06 §2: 모듈 간 `XxxApi`).
 * Redis 소속 캐시를 먼저 보고, 없으면 DB 를 본다(캐시 유실·Redis 장애 대비).
 */
interface GroupMembershipApi {
    /** 차량의 현재 활성 그룹 ID. 없으면 null */
    fun activeGroupIdOf(vehicleId: String): String?
}

@Component
class DefaultGroupMembershipApi(
    private val cache: GroupMembershipCache,
    private val members: GroupMemberRepository,
) : GroupMembershipApi {

    override fun activeGroupIdOf(vehicleId: String): String? =
        cache.activeGroupOf(vehicleId) ?: members.findActiveMembershipOf(vehicleId)?.groupId
}
