package app.donghaeng.event

import app.donghaeng.event.repo.GroupEventRepository
import app.donghaeng.protocol.GroupEventType
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration

/**
 * 그룹 이벤트 보관 정리 (C033·C091 — docs/03 §2.3 "이벤트는 24h 뒤 배치 삭제", docs/04 V1 주석, PRD §7):
 *
 * - 정기: `dh.retention.interval-ms`(기본 1시간)마다 [RETENTION](24시간)보다 오래된 `group_event` 를 지운다(진행 중 그룹도 같다 — 피드는 최근 것만 쓴다).
 * - 그룹 종료: 커밋 뒤 [GroupEventRecorded] 중 `GROUP_ENDED` 를 받아 그 그룹의 위치성 payload(`WAYPOINT_ARRIVED` 좌표, `ROUTE_CHANGED` 경로)를 지운다 —
 *   PRD §7 '도착·경유지: 그룹 종료 시 삭제'. 행·문구는 24시간 규칙대로 남아 종료 시점 멤버가 `GET /groups/{id}` 로 볼 수 있다(docs/03 §2.3).
 *   커밋 뒤 콜백은 끝난 트랜잭션의 연결이 묶여 있어 `VoiceRetention` 과 같이 REQUIRES_NEW 로 하고, 실패해도 예외를 밖으로 내지 않는다.
 */
@Component
class EventRetention(
    private val repo: GroupEventRepository,
    transactionManager: PlatformTransactionManager,
    private val clock: Clock,
) {
    private val newTx = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    @EventListener
    fun onRecorded(e: GroupEventRecorded) {
        if (e.event.type != GroupEventType.GROUP_ENDED.name) return
        try {
            val scrubbed = newTx.execute { repo.scrubLocations(e.groupId) } ?: 0
            log.info("group event locations scrubbed on group end groupId={} rows={}", e.groupId, scrubbed)
        } catch (ex: Exception) {
            log.warn("group event scrub on group end failed groupId={} cause={}", e.groupId, ex.javaClass.simpleName)
        }
    }

    /** 24시간 지난 이벤트 삭제. 돌려주는 값 = 지운 수 (테스트에서 직접 부른다) */
    @Scheduled(fixedDelayString = "\${dh.retention.interval-ms:3600000}", initialDelayString = "\${dh.retention.initial-delay-ms:120000}")
    fun purgeExpired(): Int =
        try {
            val deleted = newTx.execute { repo.deleteOlderThan(clock.instant().minus(RETENTION)) } ?: 0
            if (deleted > 0) log.info("group events purged count={}", deleted)
            deleted
        } catch (ex: Exception) {
            log.warn("group event purge failed cause={}", ex.javaClass.simpleName)
            0
        }

    companion object {
        private val log = LoggerFactory.getLogger(EventRetention::class.java)

        /** 그룹 이벤트 보관 기간 */
        val RETENTION: Duration = Duration.ofHours(24)
    }
}
