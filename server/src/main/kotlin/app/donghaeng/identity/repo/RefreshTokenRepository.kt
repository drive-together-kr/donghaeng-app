package app.donghaeng.identity.repo

import app.donghaeng.common.jdbc.instant
import app.donghaeng.common.jdbc.requireInstant
import app.donghaeng.common.jdbc.toDb
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant

/**
 * `refresh_token` 행 — 원문은 저장하지 않고 SHA-256 해시만 (docs/04 §1, docs/06 §5).
 * [replacedBy] = 회전으로 이 토큰을 대체한 다음 토큰의 해시(V4, 재사용 유예 판정용). 회전 외 폐기·살아 있는 토큰은 null.
 */
data class RefreshTokenRecord(
    val tokenHash: String,
    val subject: String,
    val expiresAt: Instant,
    val revokedAt: Instant?,
    val replacedBy: String? = null,
)

@Repository
class RefreshTokenRepository(private val jdbc: NamedParameterJdbcTemplate) {

    fun insert(tokenHash: String, subject: String, expiresAt: Instant) {
        jdbc.update(
            "INSERT INTO refresh_token (token_hash, subject, expires_at) VALUES (:hash, :subject, :expiresAt)",
            mapOf("hash" to tokenHash, "subject" to subject, "expiresAt" to expiresAt.toDb()),
        )
    }

    fun findByHashForUpdate(tokenHash: String): RefreshTokenRecord? =
        jdbc.query(
            "SELECT token_hash, subject, expires_at, revoked_at, replaced_by FROM refresh_token WHERE token_hash = :hash FOR UPDATE",
            mapOf("hash" to tokenHash),
            ::mapRow,
        ).firstOrNull()

    fun revoke(tokenHash: String, now: Instant): Int =
        jdbc.update(
            "UPDATE refresh_token SET revoked_at = :now WHERE token_hash = :hash AND revoked_at IS NULL",
            mapOf("hash" to tokenHash, "now" to now.toDb()),
        )

    /** 회전: 살아 있는 토큰을 폐기하면서 다음 토큰([successorHash])을 기록한다. 이미 폐기된 행이면 0 */
    fun markRotated(tokenHash: String, successorHash: String, now: Instant): Int =
        jdbc.update(
            "UPDATE refresh_token SET revoked_at = :now, replaced_by = :successor WHERE token_hash = :hash AND revoked_at IS NULL",
            mapOf("hash" to tokenHash, "successor" to successorHash, "now" to now.toDb()),
        )

    /** 유예 재발급: 이미 회전으로 폐기된 행의 다음 토큰만 바꾼다(`revoked_at` 은 처음 회전 시각 그대로 — 유예가 늘어나지 않게) */
    fun relink(tokenHash: String, successorHash: String): Int =
        jdbc.update(
            "UPDATE refresh_token SET replaced_by = :successor WHERE token_hash = :hash AND revoked_at IS NOT NULL",
            mapOf("hash" to tokenHash, "successor" to successorHash),
        )

    /** 주체(usr_/veh_)의 살아 있는 refresh 토큰을 모두 폐기 — 페어링 해제·소유자 이전 시 */
    fun revokeAllBySubject(subject: String, now: Instant): Int =
        jdbc.update(
            "UPDATE refresh_token SET revoked_at = :now WHERE subject = :subject AND revoked_at IS NULL",
            mapOf("subject" to subject, "now" to now.toDb()),
        )

    private fun mapRow(rs: ResultSet, @Suppress("UNUSED_PARAMETER") rowNum: Int) = RefreshTokenRecord(
        tokenHash = rs.getString("token_hash"),
        subject = rs.getString("subject"),
        expiresAt = rs.requireInstant("expires_at"),
        revokedAt = rs.instant("revoked_at"),
        replacedBy = rs.getString("replaced_by"),
    )
}
