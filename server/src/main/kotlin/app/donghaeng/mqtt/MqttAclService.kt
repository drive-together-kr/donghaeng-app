package app.donghaeng.mqtt

import app.donghaeng.group.GroupMembershipApi
import app.donghaeng.protocol.Topics
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * EMQX HTTP 인가 훅의 판정 (docs/03 §4 ACL, docs/06 §5).
 *
 * EMQX 가 JWT 인증에서 `sub == username` 을 보장하므로 username 이 곧 신원이다:
 * - 차량 `veh_…`: publish 는 자기 `telemetry/event/ack/presence` 만, subscribe 는 자기 `cmd` 와 **현재 소속 그룹**의 `state/event` 만.
 *   와일드카드(`+`, `#`)·공유 구독(`$share/…`)·다른 차량 토픽은 전부 거부.
 * - 서버 `srv_…`: `dh/v1/` 아래 전부 + 공유 구독 허용.
 * - 그 외 username: 거부.
 */
@Service
class MqttAclService(private val membership: GroupMembershipApi) {

    enum class Action { PUBLISH, SUBSCRIBE }

    data class Decision(val allow: Boolean, val reason: String)

    fun decide(username: String?, action: Action, topic: String?): Decision {
        val user = username?.trim().orEmpty()
        val t = topic?.trim().orEmpty()
        if (user.isEmpty() || t.isEmpty()) return deny("empty username or topic")
        return when {
            user.startsWith(SERVER_PREFIX) -> decideServer(t)
            user.startsWith(VEHICLE_PREFIX) -> decideVehicle(user, action, t)
            else -> deny("unknown principal kind")
        }
    }

    private fun decideServer(topic: String): Decision {
        val real = topic.removePrefix("\$share/").let { if (topic.startsWith("\$share/")) it.substringAfter('/') else it }
        return if (real.startsWith(Topics.PREFIX + "/")) allow("server") else deny("server outside dh/v1")
    }

    private fun decideVehicle(vid: String, action: Action, topic: String): Decision {
        if (topic.startsWith("\$")) return deny("vehicle may not use \$share/\$SYS")
        if (topic.contains('+') || topic.contains('#')) return deny("vehicle may not use wildcards")
        val topicVid = Topics.vehicleIdFromTopic(topic)
        val kind = Topics.vehicleTopicKind(topic)
        if (topicVid != null) {
            if (topicVid != vid) return deny("other vehicle's topic")
            return when (action) {
                Action.PUBLISH -> if (kind in PUBLISHABLE) allow("own $kind") else deny("vehicle may not publish $kind")
                Action.SUBSCRIBE -> if (kind == Topics.VehicleTopicKind.CMD) allow("own cmd") else deny("vehicle may only subscribe own cmd")
            }
        }
        val gid = Topics.groupIdFromTopic(topic)
        if (gid != null) {
            if (action != Action.SUBSCRIBE) return deny("vehicle may not publish group topics")
            val leaf = topic.substringAfterLast('/')
            if (leaf != "state" && leaf != "event") return deny("unknown group leaf")
            if (membership.activeGroupIdOf(vid) == gid) return allow("member of group")
            // 캐시(Redis)가 옛 값일 수 있다(쓰기 실패·커밋 뒤 순서 역전, C101) — 거부하기 전에 DB 로 한 번 더 보고 캐시를 고친다(거부는 드물어 비용이 작다)
            return if (membership.verifiedActiveGroupIdOf(vid) == gid) allow("member of group (db)") else deny("not a member of that group")
        }
        return deny("unknown topic")
    }

    private fun allow(reason: String) = Decision(true, reason)
    private fun deny(reason: String) = Decision(false, reason)

    companion object {
        private val log = LoggerFactory.getLogger(MqttAclService::class.java)
        const val SERVER_PREFIX = "srv_"
        const val VEHICLE_PREFIX = "veh_"
        private val PUBLISHABLE = setOf(
            Topics.VehicleTopicKind.TELEMETRY,
            Topics.VehicleTopicKind.EVENT,
            Topics.VehicleTopicKind.ACK,
            Topics.VehicleTopicKind.PRESENCE,
        )
    }
}
