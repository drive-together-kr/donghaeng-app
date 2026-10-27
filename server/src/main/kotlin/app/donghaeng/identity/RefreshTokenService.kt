package app.donghaeng.identity

import app.donghaeng.identity.repo.RefreshTokenRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration

/**
 * refresh 토큰 (docs/06 §5): 랜덤 32바이트 Base62 원문 → 클라이언트, DB 엔 SHA-256 해시만. 30일 유효, 사용할 때마다 회전(이전 토큰 즉시 폐기).
 * 주체는 `usr_…`(사용자) 또는 `veh_…`(차량).
 */
@Service
class RefreshTokenService(
    private val repo: RefreshTokenRepository,
    private val clock: Clock,
) {
    data class Rotation(val subject: String, val refreshToken: String)

    fun issue(subject: String): String {
        val raw = SecureTokens.randomBase62Bytes(TOKEN_BYTES)
        repo.insert(hash(raw), subject, clock.instant().plus(TTL))
        return raw
    }

    /** 회전: 유효한 토큰이면 폐기하고 같은 주체의 새 토큰을 돌려준다. 무효·폐기·만료는 모두 401 INVALID_TOKEN (구분하지 않음). */
    @Transactional
    fun rotate(rawToken: String): Rotation {
        val raw = rawToken.trim()
        if (raw.isEmpty() || raw.length > MAX_TOKEN_LENGTH || !SecureTokens.isBase62(raw)) throw IdentityErrors.invalidToken()
        val row = repo.findByHashForUpdate(hash(raw)) ?: throw IdentityErrors.invalidToken()
        val now = clock.instant()
        if (row.revokedAt != null || !row.expiresAt.isAfter(now)) {
            log.info("refresh token rejected subject={} revoked={} expired={}", row.subject, row.revokedAt != null, !row.expiresAt.isAfter(now))
            throw IdentityErrors.invalidToken()
        }
        repo.revoke(row.tokenHash, now)
        return Rotation(row.subject, issue(row.subject))
    }

    /** 주체의 살아 있는 refresh 토큰 전부 폐기 (페어링 해제, 소유자 이전) */
    fun revokeAll(subject: String): Int = repo.revokeAllBySubject(subject, clock.instant())

    companion object {
        private val log = LoggerFactory.getLogger(RefreshTokenService::class.java)
        const val TOKEN_BYTES = 32
        const val MAX_TOKEN_LENGTH = 128
        val TTL: Duration = Duration.ofDays(30)

        fun hash(rawToken: String): String = SecureTokens.sha256Hex(rawToken)
    }
}
