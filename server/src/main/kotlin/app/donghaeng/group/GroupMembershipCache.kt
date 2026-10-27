package app.donghaeng.group

import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component

/**
 * Redis 의 그룹 소속 캐시 (docs/04 §2): `grp:{gid}:members`(Set), `veh:{vid}:group`(String). 그룹 종료 시 삭제.
 * presence(T24)·MQTT ACL 훅(T15)이 DB 대신 이 키로 소속을 본다. 진실은 PostgreSQL 이므로 Redis 오류는 WARN 만 남기고 삼킨다.
 * 모든 메서드는 트랜잭션 커밋 뒤에 불린다([app.donghaeng.common.tx.afterCommit]).
 */
@Component
class GroupMembershipCache(private val redis: StringRedisTemplate) {

    fun joined(groupId: String, vehicleId: String) = safely("joined") {
        redis.opsForSet().add(membersKey(groupId), vehicleId)
        redis.opsForValue().set(vehicleGroupKey(vehicleId), groupId)
    }

    fun left(groupId: String, vehicleId: String) = safely("left") {
        redis.opsForSet().remove(membersKey(groupId), vehicleId)
        // 다른 그룹으로 이미 옮겨 갔으면 건드리지 않는다
        val current = redis.opsForValue().get(vehicleGroupKey(vehicleId))
        if (current == groupId) redis.delete(vehicleGroupKey(vehicleId))
    }

    /** 그룹 종료: 그룹 키 전부 + 멤버들의 소속·상태 키 삭제 ("위치 데이터 삭제", docs/03 §2.3) */
    fun ended(groupId: String, memberVehicleIds: Collection<String>) = safely("ended") {
        val keys = mutableListOf(membersKey(groupId), geoKey(groupId), drivingSinceKey(groupId))
        memberVehicleIds.forEach { vid ->
            keys += vehicleStateKey(vid)
            val current = redis.opsForValue().get(vehicleGroupKey(vid))
            if (current == groupId) keys += vehicleGroupKey(vid)
        }
        redis.delete(keys)
    }

    /** 차량의 현재 그룹 (캐시 기준). 없으면 null */
    fun activeGroupOf(vehicleId: String): String? =
        try {
            redis.opsForValue().get(vehicleGroupKey(vehicleId))
        } catch (e: Exception) {
            log.warn("redis read failed op=activeGroupOf cause={}", e.javaClass.simpleName)
            null
        }

    private inline fun safely(op: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            log.warn("redis update failed op={} cause={}", op, e.javaClass.simpleName)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(GroupMembershipCache::class.java)

        fun membersKey(groupId: String) = "grp:$groupId:members"
        fun geoKey(groupId: String) = "grp:$groupId:geo"
        fun drivingSinceKey(groupId: String) = "grp:$groupId:drivingSince"
        fun vehicleGroupKey(vehicleId: String) = "veh:$vehicleId:group"
        fun vehicleStateKey(vehicleId: String) = "veh:$vehicleId:state"
    }
}
