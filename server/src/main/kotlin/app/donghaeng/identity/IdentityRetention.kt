package app.donghaeng.identity

import app.donghaeng.identity.repo.DeviceAuthorizationRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration

/**
 * identity 보관 정리 (C033·C094, docs/06 §3 `Scheduler(1h)`): `dh.retention.interval-ms`(기본 1시간)마다
 * - `device_authorization`: 만료(`expires_at`) 뒤 [DEVICE_CODE_KEEP](1시간) 지난 행 — 인증 없는 `POST /device/code` 로 만들어지는 행이 쌓이지 않게
 *   (인덱스 `idx_device_authorization_expires_at`, V2). 폴링·승인은 만료 시각을 직접 비교하므로 정리 주기와 무관하다.
 * - `refresh_token`: 만료된 행 — 회전·재사용 유예 판정은 만료 전까지만 필요하다(V7 인덱스).
 * 실패해도 예외를 밖으로 내지 않는다(다음 주기에 다시).
 */
@Component
class IdentityRetention(
    private val codes: DeviceAuthorizationRepository,
    private val refreshTokens: RefreshTokenService,
    private val clock: Clock,
) {
    @Scheduled(fixedDelayString = "\${dh.retention.interval-ms:3600000}", initialDelayString = "\${dh.retention.initial-delay-ms:120000}")
    fun purge(): Int =
        try {
            val codesDeleted = codes.deleteExpiredBefore(clock.instant().minus(DEVICE_CODE_KEEP))
            val tokensDeleted = refreshTokens.deleteExpired()
            if (codesDeleted + tokensDeleted > 0) log.info("identity purged deviceCodes={} refreshTokens={}", codesDeleted, tokensDeleted)
            codesDeleted + tokensDeleted
        } catch (e: Exception) {
            log.warn("identity purge failed cause={}", e.javaClass.simpleName)
            0
        }

    companion object {
        private val log = LoggerFactory.getLogger(IdentityRetention::class.java)

        /** 만료된 디바이스 코드를 남겨 두는 시간 */
        val DEVICE_CODE_KEEP: Duration = Duration.ofHours(1)
    }
}
