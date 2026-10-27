package app.donghaeng.group.repo

import app.donghaeng.common.jdbc.instant
import app.donghaeng.common.jdbc.requireInstant
import app.donghaeng.common.jdbc.toDb
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant

/** `group_member.role` (docs/04 §1, docs/03 §2.4) */
enum class MemberRole { LEADER, MEMBER }

/** `group_member` 행. `leftAt != null` 이면 나간 멤버. */
data class MemberRecord(
    val groupId: String,
    val vehicleId: String,
    val role: MemberRole,
    val color: String,
    val joinedBy: String?,
    val joinedAt: Instant,
    val leftAt: Instant?,
)

@Repository
class GroupMemberRepository(private val jdbc: NamedParameterJdbcTemplate) {

    /**
     * 차량 단위 직렬화 — "차량 하나는 활성 그룹 하나" 규칙을 트랜잭션 사이에서 지킨다 (그룹이 달라 행 잠금이 겹치지 않을 때 대비).
     * 트랜잭션이 끝나면 자동 해제되는 advisory lock. 그룹 행 잠금보다 **먼저** 잡는다(교착 방지 순서: 차량 → 그룹).
     */
    fun lockVehicle(vehicleId: String) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtext(:key))", mapOf("key" to "vehicle:$vehicleId"), Any::class.java)
    }

    /** 합류. 같은 그룹에 다시 들어오면(나갔다 재합류) 행을 되살린다. */
    fun upsertJoin(groupId: String, vehicleId: String, role: MemberRole, color: String, joinedBy: String?, joinedAt: Instant): MemberRecord =
        jdbc.queryForObject(
            """
            INSERT INTO group_member (group_id, vehicle_id, role, color, joined_by, joined_at, left_at)
            VALUES (:groupId, :vehicleId, :role, :color, :joinedBy, :joinedAt, NULL)
            ON CONFLICT (group_id, vehicle_id) DO UPDATE
               SET role = EXCLUDED.role, color = EXCLUDED.color, joined_by = EXCLUDED.joined_by, joined_at = EXCLUDED.joined_at, left_at = NULL
            RETURNING $COLUMNS
            """.trimIndent(),
            mapOf(
                "groupId" to groupId,
                "vehicleId" to vehicleId,
                "role" to role.name,
                "color" to color,
                "joinedBy" to joinedBy,
                "joinedAt" to joinedAt.toDb(),
            ),
            ::mapRow,
        )!!

    /** 현재(나가지 않은) 멤버 — 합류순 */
    fun listActive(groupId: String): List<MemberRecord> =
        jdbc.query(
            "SELECT $COLUMNS FROM group_member WHERE group_id = :groupId AND left_at IS NULL ORDER BY joined_at ASC, vehicle_id ASC",
            mapOf("groupId" to groupId),
            ::mapRow,
        )

    fun findActive(groupId: String, vehicleId: String): MemberRecord? =
        jdbc.query(
            "SELECT $COLUMNS FROM group_member WHERE group_id = :groupId AND vehicle_id = :vehicleId AND left_at IS NULL",
            mapOf("groupId" to groupId, "vehicleId" to vehicleId),
            ::mapRow,
        ).firstOrNull()

    /** 차량이 지금 들어가 있는 ACTIVE 그룹의 멤버 행 (규칙상 최대 하나). 없으면 null */
    fun findActiveMembershipOf(vehicleId: String): MemberRecord? =
        jdbc.query(
            """
            SELECT ${COLUMNS.prefixed("m")}
              FROM group_member m JOIN travel_group g ON g.group_id = m.group_id
             WHERE m.vehicle_id = :vehicleId AND m.left_at IS NULL AND g.status = 'ACTIVE'
             ORDER BY m.joined_at DESC
             LIMIT 1
            """.trimIndent(),
            mapOf("vehicleId" to vehicleId),
            ::mapRow,
        ).firstOrNull()

    /** 여러 차량의 현재 멤버 행 전부 (ACTIVE·ENDED 그룹 모두 — `GET /me/groups`). */
    fun listActiveMembershipsOf(vehicleIds: Collection<String>): List<MemberRecord> =
        if (vehicleIds.isEmpty()) emptyList()
        else jdbc.query(
            "SELECT $COLUMNS FROM group_member WHERE vehicle_id IN (:ids) AND left_at IS NULL",
            mapOf("ids" to vehicleIds.toSet()),
            ::mapRow,
        )

    /** 그룹별 현재 멤버 수 */
    fun countActiveByGroups(groupIds: Collection<String>): Map<String, Int> =
        if (groupIds.isEmpty()) emptyMap()
        else jdbc.query(
            "SELECT group_id, count(*) AS n FROM group_member WHERE group_id IN (:ids) AND left_at IS NULL GROUP BY group_id",
            mapOf("ids" to groupIds.toSet()),
        ) { rs, _ -> rs.getString("group_id") to rs.getInt("n") }.toMap()

    /** 나가기. 이미 나갔거나 멤버가 아니면 0 */
    fun leave(groupId: String, vehicleId: String, leftAt: Instant): Int =
        jdbc.update(
            "UPDATE group_member SET left_at = :leftAt WHERE group_id = :groupId AND vehicle_id = :vehicleId AND left_at IS NULL",
            mapOf("groupId" to groupId, "vehicleId" to vehicleId, "leftAt" to leftAt.toDb()),
        )

    fun setRole(groupId: String, vehicleId: String, role: MemberRole) {
        jdbc.update(
            "UPDATE group_member SET role = :role WHERE group_id = :groupId AND vehicle_id = :vehicleId",
            mapOf("groupId" to groupId, "vehicleId" to vehicleId, "role" to role.name),
        )
    }

    private fun mapRow(rs: ResultSet, @Suppress("UNUSED_PARAMETER") rowNum: Int) = MemberRecord(
        groupId = rs.getString("group_id"),
        vehicleId = rs.getString("vehicle_id"),
        role = MemberRole.valueOf(rs.getString("role")),
        color = rs.getString("color"),
        joinedBy = rs.getString("joined_by"),
        joinedAt = rs.requireInstant("joined_at"),
        leftAt = rs.instant("left_at"),
    )

    private fun String.prefixed(alias: String) = split(", ").joinToString(", ") { "$alias.$it" }

    companion object {
        private const val COLUMNS = "group_id, vehicle_id, role, color, joined_by, joined_at, left_at"
    }
}
