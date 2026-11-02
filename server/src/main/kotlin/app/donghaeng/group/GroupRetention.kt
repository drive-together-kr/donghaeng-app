package app.donghaeng.group

import app.donghaeng.group.repo.GroupInviteRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration

/**
 * group 보관 정리 (C033, docs/06 §3 `Scheduler(1h)`): `dh.retention.interval-ms`(기본 1시간)마다 만료된 지 [INVITE_KEEP](1일) 지난
 * `group_invite` 행을 지운다(재발급·그룹 종료로 만료된 것 포함 — 합류·미리보기는 만료 시각을 직접 비교하므로 정리 주기와 무관). 실패는 로그만.
 */
@Component
class GroupRetention(
    private val invites: GroupInviteRepository,
    private val clock: Clock,
) {
    @Scheduled(fixedDelayString = "\${dh.retention.interval-ms:3600000}", initialDelayString = "\${dh.retention.initial-delay-ms:120000}")
    fun purge(): Int =
        try {
            invites.deleteExpiredBefore(clock.instant().minus(INVITE_KEEP)).also { if (it > 0) log.info("group invites purged count={}", it) }
        } catch (e: Exception) {
            log.warn("group invite purge failed cause={}", e.javaClass.simpleName)
            0
        }

    companion object {
        private val log = LoggerFactory.getLogger(GroupRetention::class.java)
        val INVITE_KEEP: Duration = Duration.ofDays(1)
    }
}
