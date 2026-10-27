package app.donghaeng.group.repo

import app.donghaeng.common.jdbc.requireInstant
import app.donghaeng.common.jdbc.toDb
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant

/** `group_invite` 행 — 토큰 원문은 없다(해시만) */
data class InviteRecord(
    val tokenHash: String,
    val groupId: String,
    val expiresAt: Instant,
    val maxUses: Int,
    val usedCount: Int,
) {
    fun expired(now: Instant): Boolean = !expiresAt.isAfter(now)
    val exhausted: Boolean get() = usedCount >= maxUses
}

@Repository
class GroupInviteRepository(private val jdbc: NamedParameterJdbcTemplate) {

    fun insert(tokenHash: String, groupId: String, expiresAt: Instant, maxUses: Int) {
        jdbc.update(
            "INSERT INTO group_invite (token_hash, group_id, expires_at, max_uses, used_count) VALUES (:hash, :groupId, :expiresAt, :maxUses, 0)",
            mapOf("hash" to tokenHash, "groupId" to groupId, "expiresAt" to expiresAt.toDb(), "maxUses" to maxUses),
        )
    }

    /** 잠금 없는 조회 — 어느 그룹의 초대인지 먼저 알아내는 용도(잠금 순서: 그룹 행 → 초대 행) */
    fun findByHash(tokenHash: String): InviteRecord? =
        jdbc.query("SELECT $COLUMNS FROM group_invite WHERE token_hash = :hash", mapOf("hash" to tokenHash), ::mapRow).firstOrNull()

    /** 행 잠금 — 사용 횟수 증가가 동시 합류 사이에서 정확하도록. 그룹 행을 잠근 뒤에 부른다 */
    fun findByHashForUpdate(tokenHash: String): InviteRecord? =
        jdbc.query("SELECT $COLUMNS FROM group_invite WHERE token_hash = :hash FOR UPDATE", mapOf("hash" to tokenHash), ::mapRow).firstOrNull()

    fun incrementUse(tokenHash: String) {
        jdbc.update("UPDATE group_invite SET used_count = used_count + 1 WHERE token_hash = :hash", mapOf("hash" to tokenHash))
    }

    /** 그룹의 살아 있는 초대를 모두 만료시킨다 (재발급·그룹 종료). 돌려주는 값은 만료된 행 수 */
    fun expireAllForGroup(groupId: String, now: Instant): Int =
        jdbc.update(
            "UPDATE group_invite SET expires_at = :now WHERE group_id = :groupId AND expires_at > :now",
            mapOf("groupId" to groupId, "now" to now.toDb()),
        )

    private fun mapRow(rs: ResultSet, @Suppress("UNUSED_PARAMETER") rowNum: Int) = InviteRecord(
        tokenHash = rs.getString("token_hash"),
        groupId = rs.getString("group_id"),
        expiresAt = rs.requireInstant("expires_at"),
        maxUses = rs.getInt("max_uses"),
        usedCount = rs.getInt("used_count"),
    )

    companion object {
        private const val COLUMNS = "token_hash, group_id, expires_at, max_uses, used_count"
    }
}
