package app.donghaeng.identity.repo

import app.donghaeng.common.jdbc.instant
import app.donghaeng.common.jdbc.requireInstant
import app.donghaeng.common.jdbc.toDb
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant

/** `refresh_token` 행 — 원문은 저장하지 않고 SHA-256 해시만 (docs/04 §1, docs/06 §5) */
data class RefreshTokenRecord(
    val tokenHash: String,
    val subject: String,
    val expiresAt: Instant,
    val revokedAt: Instant?,
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
            "SELECT token_hash, subject, expires_at, revoked_at FROM refresh_token WHERE token_hash = :hash FOR UPDATE",
            mapOf("hash" to tokenHash),
            ::mapRow,
        ).firstOrNull()

    fun revoke(tokenHash: String, now: Instant): Int =
        jdbc.update(
            "UPDATE refresh_token SET revoked_at = :now WHERE token_hash = :hash AND revoked_at IS NULL",
            mapOf("hash" to tokenHash, "now" to now.toDb()),
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
    )
}
