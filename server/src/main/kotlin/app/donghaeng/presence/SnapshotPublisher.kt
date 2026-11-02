package app.donghaeng.presence

import app.donghaeng.event.GroupEventRecorded
import app.donghaeng.group.GroupMembershipApi
import app.donghaeng.mqtt.MqttPublisher
import app.donghaeng.protocol.GroupEventType
import app.donghaeng.protocol.GroupState
import app.donghaeng.protocol.MessageTypes
import app.donghaeng.protocol.Topics
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 2초마다 활성 그룹마다 `dh/v1/group/{gid}/state` 에 스냅샷을 **retained, QoS 0** 으로 발행한다 (docs/03 §4·§5.3, T24).
 * 새로 구독한 차량·폰은 retained 덕에 바로 마지막 스냅샷을 받는다. 그룹이 끝나면 retained 를 지운다(빈 payload).
 * 브로커 연결이 없으면 조용히 건너뛴다(연결되면 다음 틱에 다시).
 *
 * **종료와의 경합(C071·C109)**: 틱이 명단(`activeRosters`)을 읽은 뒤 발행하기 전에 그룹이 끝나면, 종료 커밋 뒤의 retained 삭제가 먼저 나가고
 * 틱이 만든 옛 스냅샷(좌표 포함)이 다시 retained 로 남았다(EMQX retainer 기본값은 만료 없음). 세 겹으로 막는다:
 * ① 스냅샷에 MQTT 5 Message Expiry [STATE_EXPIRY_SECONDS](30 s) — 2초마다 새로 나가므로 정상 동작은 같고, 놓친 삭제·서버 중단으로 남은 retained 도 브로커가 지운다.
 * ② 방금 끝난 그룹 집합([RECENTLY_ENDED_KEEP_MS] 동안) — 발행 직전에 보고 건너뛴다.
 * ③ 삭제를 한 틱 뒤([reclearDelayMs]) 한 번 더 — ②의 확인과 발행 사이 틈, 재연결 중 놓친 삭제를 덮는다.
 */
@Component
class SnapshotPublisher(
    private val membership: GroupMembershipApi,
    private val builder: SnapshotBuilder,
    private val publisher: MqttPublisher,
) {
    private val ticks = AtomicLong()
    private val published = AtomicLong()

    /** 방금 끝난 그룹 → 끝난 시각(ms) */
    private val recentlyEnded = ConcurrentHashMap<String, Long>()

    private val reclear: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "snapshot-reclear").apply { isDaemon = true } }

    /** 두 번째 retained 삭제까지의 지연 — 스냅샷 주기(2 s) + 1 s. 테스트가 줄인다 */
    @Volatile
    internal var reclearDelayMs: Long = RECLEAR_DELAY_MS

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
        pruneEnded()
        var sent = 0
        rosters.forEach { roster ->
            if (recentlyEnded.containsKey(roster.groupId)) return@forEach
            val state = try {
                builder.build(roster)
            } catch (e: Exception) {
                log.warn("snapshot: build failed groupId={} cause={}", roster.groupId, e.javaClass.simpleName)
                return@forEach
            }
            // 발행 직전에 한 번 더 — 명단을 읽은 뒤 끝난 그룹(C071)
            if (recentlyEnded.containsKey(roster.groupId)) return@forEach
            if (publisher.publishExpiring(Topics.groupState(roster.groupId), MessageTypes.STATE, state, GroupState.serializer(), qos = 0, retain = true, expirySeconds = STATE_EXPIRY_SECONDS)) {
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

    /** 그룹 종료 → retained 스냅샷 삭제 (멤버가 나중에 구독해도 끝난 그룹 상태를 받지 않게). 한 틱 뒤 한 번 더 지운다 */
    @EventListener
    fun onRecorded(e: GroupEventRecorded) {
        if (e.event.type != GroupEventType.GROUP_ENDED.name) return
        recentlyEnded[e.groupId] = System.currentTimeMillis()
        val cleared = publisher.clearRetained(Topics.groupState(e.groupId))
        log.info("snapshot retained cleared groupId={} ok={}", e.groupId, cleared)
        try {
            reclear.schedule({
                val again = publisher.clearRetained(Topics.groupState(e.groupId))
                log.info("snapshot retained cleared again groupId={} ok={}", e.groupId, again)
            }, reclearDelayMs, TimeUnit.MILLISECONDS)
        } catch (ex: Exception) {
            log.warn("snapshot re-clear schedule failed groupId={} cause={}", e.groupId, ex.javaClass.simpleName)
        }
    }

    private fun pruneEnded() {
        val cutoff = System.currentTimeMillis() - RECENTLY_ENDED_KEEP_MS
        recentlyEnded.entries.removeIf { it.value < cutoff }
    }

    @PreDestroy
    fun shutdown() {
        reclear.shutdownNow()
    }

    companion object {
        private val log = LoggerFactory.getLogger(SnapshotPublisher::class.java)
        private const val LOG_EVERY_TICKS = 30L

        /** retained 스냅샷의 MQTT 5 Message Expiry(초) */
        const val STATE_EXPIRY_SECONDS = 30L

        /** 끝난 그룹을 발행 대상에서 빼 두는 시간 — 끝난 그룹은 다시 활성이 되지 않는다(ID 재사용 없음) */
        const val RECENTLY_ENDED_KEEP_MS = 60_000L

        const val RECLEAR_DELAY_MS = 3_000L
    }
}
