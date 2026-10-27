package app.donghaeng.presence

import app.donghaeng.event.GroupEventRecorded
import app.donghaeng.group.GroupMembershipApi
import app.donghaeng.mqtt.MqttPublisher
import app.donghaeng.protocol.GroupEventType
import app.donghaeng.protocol.GroupState
import app.donghaeng.protocol.MessageTypes
import app.donghaeng.protocol.Topics
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicLong

/**
 * 2초마다 활성 그룹마다 `dh/v1/group/{gid}/state` 에 스냅샷을 **retained, QoS 0** 으로 발행한다 (docs/03 §4·§5.3, T24).
 * 새로 구독한 차량·폰은 retained 덕에 바로 마지막 스냅샷을 받는다. 그룹이 끝나면 retained 를 지운다(빈 payload).
 * 브로커 연결이 없으면 조용히 건너뛴다(연결되면 다음 틱에 다시).
 */
@Component
class SnapshotPublisher(
    private val membership: GroupMembershipApi,
    private val builder: SnapshotBuilder,
    private val publisher: MqttPublisher,
) {
    private val ticks = AtomicLong()
    private val published = AtomicLong()

    /** 발행한 스냅샷 수 (진단·테스트용) */
    val publishedCount: Long get() = published.get()

    @Scheduled(fixedDelayString = "\${dh.presence.snapshot-interval-ms:2000}", initialDelayString = "\${dh.presence.snapshot-initial-delay-ms:5000}")
    fun tick() {
        if (!publisher.connected) return
        publishAll()
    }

    /** 모든 활성 그룹 스냅샷 발행. 돌려주는 값 = 이번에 발행한 그룹 수 */
    fun publishAll(): Int {
        val rosters = try {
            membership.activeRosters()
        } catch (e: Exception) {
            log.warn("snapshot: roster load failed cause={}", e.javaClass.simpleName)
            return 0
        }
        var sent = 0
        rosters.forEach { roster ->
            val state = try {
                builder.build(roster)
            } catch (e: Exception) {
                log.warn("snapshot: build failed groupId={} cause={}", roster.groupId, e.javaClass.simpleName)
                return@forEach
            }
            if (publisher.publish(Topics.groupState(roster.groupId), MessageTypes.STATE, state, GroupState.serializer(), qos = 0, retain = true)) {
                sent++
                published.incrementAndGet()
            }
        }
        val t = ticks.incrementAndGet()
        if (sent > 0 && (t == 1L || t % LOG_EVERY_TICKS == 0L)) {
            log.info("snapshot published groups={} tick={} total={}", sent, t, published.get())
        }
        return sent
    }

    /** 그룹 종료 → retained 스냅샷 삭제 (멤버가 나중에 구독해도 끝난 그룹 상태를 받지 않게) */
    @EventListener
    fun onRecorded(e: GroupEventRecorded) {
        if (e.event.type != GroupEventType.GROUP_ENDED.name) return
        val cleared = publisher.clearRetained(Topics.groupState(e.groupId))
        log.info("snapshot retained cleared groupId={} ok={}", e.groupId, cleared)
    }

    companion object {
        private val log = LoggerFactory.getLogger(SnapshotPublisher::class.java)
        private const val LOG_EVERY_TICKS = 30L
    }
}
