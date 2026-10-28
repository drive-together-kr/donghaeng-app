package app.donghaeng.voice

import app.donghaeng.event.GroupEventRecorded
import app.donghaeng.protocol.GroupEventType
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock

/**
 * 무전 보관 정리 (docs/03 §2.6: 업로드 + 24시간, 그룹이 끝나면 즉시 삭제).
 *
 * - 그룹 종료: event 모듈이 커밋 뒤 발행하는 [GroupEventRecorded] 중 `GROUP_ENDED` 를 받아 그 그룹 무전을 지운다
 *   (`SnapshotPublisher` 가 retained 스냅샷을 지우는 것과 같은 경로 — 종료의 모든 갈래(방장 종료·전원 이탈·차량 해제)가 `GROUP_ENDED` 를 남긴다).
 *   커밋 뒤 콜백 안에서는 끝난 트랜잭션의 연결이 아직 묶여 있어 **새 트랜잭션(REQUIRES_NEW)** 으로 지운다. 실패해도 예외를 밖으로 내지 않는다
 *   (종료 요청은 이미 커밋됨) — 다운로드는 끝난 그룹의 무전을 내주지 않고, 남은 행은 24시간 정리가 지운다.
 * - 정기 정리: `dh.voice.purge-interval-ms`(기본 10분)마다 만료된 무전을 지운다. 다운로드는 만료 시각을 직접 비교하므로 정리 주기는 저장 공간 문제일 뿐이다.
 */
@Component
class VoiceRetention(
    private val store: VoiceStore,
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
            val deleted = newTx.execute { store.deleteByGroup(e.groupId) } ?: 0
            log.info("voice deleted on group end groupId={} count={}", e.groupId, deleted)
        } catch (ex: Exception) {
            // 다운로드는 끝난 그룹의 무전을 내주지 않으므로 노출은 없다. 남은 행은 만료 정리가 지운다
            log.warn("voice delete on group end failed groupId={} cause={}", e.groupId, ex.javaClass.simpleName)
        }
    }

    /** 만료된 무전 삭제. 돌려주는 값 = 지운 수 (테스트에서 직접 부른다) */
    @Scheduled(
        fixedDelayString = "\${dh.voice.purge-interval-ms:600000}",
        initialDelayString = "\${dh.voice.purge-initial-delay-ms:60000}",
    )
    fun purgeExpired(): Int =
        try {
            val deleted = newTx.execute { store.deleteExpired(clock.instant()) } ?: 0
            if (deleted > 0) log.info("voice purged expired count={}", deleted)
            deleted
        } catch (ex: Exception) {
            log.warn("voice purge failed cause={}", ex.javaClass.simpleName)
            0
        }

    companion object {
        private val log = LoggerFactory.getLogger(VoiceRetention::class.java)
    }
}
