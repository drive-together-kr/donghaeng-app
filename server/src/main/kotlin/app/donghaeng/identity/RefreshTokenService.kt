package app.donghaeng.identity

import app.donghaeng.common.security.SecureTokens
import app.donghaeng.identity.repo.RefreshTokenRecord
import app.donghaeng.identity.repo.RefreshTokenRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * refresh 토큰 (docs/06 §5): 랜덤 32바이트 Base62 원문 → 클라이언트, DB 엔 SHA-256 해시만. 30일 유효, 사용할 때마다 회전(이전 토큰 즉시 폐기).
 * 주체는 `usr_…`(사용자) 또는 `veh_…`(차량).
 *
 * **재사용 유예 (T33 리뷰, docs/03 §1.1)**: 서버가 회전을 커밋한 뒤 응답이 클라이언트에 닿지 못하면(읽기 타임아웃·네트워크 단절·프로세스 종료)
 * 클라이언트는 이전 토큰만 가진 채 남고, 다음 시도는 반드시 401 이 돼 차량은 재페어링해야 했다. 그래서 이전 토큰이
 * **회전된 지 [REUSE_GRACE](60 s) 안에** 다시 오고 **그 다음 토큰이 아직 한 번도 쓰이지 않았으면**(폐기되지 않았으면) 응답 유실 재시도로 보고
 * 다음 토큰을 폐기하고 새로 회전한다 — 살아 있는 토큰은 계속 하나다. 다음 토큰을 이미 썼거나, 페어링 해제·소유자 이전으로 폐기됐거나,
 * 유예가 지났으면 401. 유예는 처음 회전 시각부터 재므로 재시도를 거듭해도 늘어나지 않는다.
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

    /**
     * 회전: 유효한 토큰이면 폐기하고 같은 주체의 새 토큰을 돌려준다(재사용 유예는 클래스 설명). 무효·폐기·만료는 모두 401 INVALID_TOKEN (구분하지 않음).
     * 같은 행을 `FOR UPDATE` 로 잡으므로 같은 토큰의 동시 회전은 줄 선다.
     */
    @Transactional
    fun rotate(rawToken: String): Rotation {
        val raw = rawToken.trim()
        if (raw.isEmpty() || raw.length > MAX_TOKEN_LENGTH || !SecureTokens.isBase62(raw)) throw IdentityErrors.invalidToken()
        val row = repo.findByHashForUpdate(hash(raw)) ?: throw IdentityErrors.invalidToken()
        val now = clock.instant()
        if (!row.expiresAt.isAfter(now)) {
            log.info("refresh token rejected subject={} revoked={} expired=true", row.subject, row.revokedAt != null)
            throw IdentityErrors.invalidToken()
        }
        val revokedAt = row.revokedAt
        if (revokedAt == null) {
            val next = issue(row.subject)
            repo.markRotated(row.tokenHash, hash(next), now)
            return Rotation(row.subject, next)
        }
        retryWithinGrace(row, revokedAt, now)?.let { return it }
        log.info("refresh token rejected subject={} revoked=true rotated={} expired=false", row.subject, row.replacedBy != null)
        throw IdentityErrors.invalidToken()
    }

    /** 응답 유실 재시도면 다음 토큰을 폐기하고 새로 회전한다. 아니면 null */
    private fun retryWithinGrace(row: RefreshTokenRecord, revokedAt: Instant, now: Instant): Rotation? {
        val successorHash = row.replacedBy ?: return null                      // 회전이 아닌 폐기(해제·이전)
        val sinceRotation = Duration.between(revokedAt, now)
        if (sinceRotation.isNegative || sinceRotation > REUSE_GRACE) return null
        val successor = repo.findByHashForUpdate(successorHash) ?: return null
        if (successor.revokedAt != null) return null                           // 다음 토큰을 이미 썼거나 해제·이전으로 폐기됨
        repo.revoke(successor.tokenHash, now)
        val next = issue(row.subject)
        repo.relink(row.tokenHash, hash(next))
        log.info("refresh token reused within grace subject={} sinceRotationMs={} — 응답 유실 재시도로 보고 다시 회전", row.subject, sinceRotation.toMillis())
        return Rotation(row.subject, next)
    }

    /** 주체의 살아 있는 refresh 토큰 전부 폐기 (페어링 해제, 소유자 이전) */
    fun revokeAll(subject: String): Int = repo.revokeAllBySubject(subject, clock.instant())

    companion object {
        private val log = LoggerFactory.getLogger(RefreshTokenService::class.java)
        const val TOKEN_BYTES = 32
        const val MAX_TOKEN_LENGTH = 128
        val TTL: Duration = Duration.ofDays(30)

        /** 회전된 이전 토큰을 응답 유실 재시도로 받아 주는 시간(처음 회전 시각부터) */
        val REUSE_GRACE: Duration = Duration.ofSeconds(60)

        fun hash(rawToken: String): String = SecureTokens.sha256Hex(rawToken)
    }
}
