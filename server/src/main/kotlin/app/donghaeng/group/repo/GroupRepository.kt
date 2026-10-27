package app.donghaeng.group.repo

import app.donghaeng.common.jdbc.instant
import app.donghaeng.common.jdbc.requireInstant
import app.donghaeng.common.jdbc.toDb
import app.donghaeng.protocol.Place
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant

/** `travel_group.status` (docs/04 §1) */
enum class GroupStatus { ACTIVE, ENDED }

/** `travel_group` 행 */
data class GroupRecord(
    val groupId: String,
    val name: String,
    val leaderVehicleId: String,
    val destination: Place?,
    val maxMembers: Int,
    val status: GroupStatus,
    val mode: String,
    val createdAt: Instant,
    val endedAt: Instant?,
) {
    val active: Boolean get() = status == GroupStatus.ACTIVE
}

@Repository
class GroupRepository(private val jdbc: NamedParameterJdbcTemplate) {

    fun insert(groupId: String, name: String, leaderVehicleId: String, destination: Place, maxMembers: Int): GroupRecord =
        jdbc.queryForObject(
            """
            INSERT INTO travel_group (group_id, name, leader_vehicle_id, dest_lat, dest_lon, dest_name, dest_poi_id, dest_address, max_members, status)
            VALUES (:groupId, :name, :leader, :lat, :lon, :destName, :poiId, :address, :maxMembers, 'ACTIVE')
            RETURNING $COLUMNS
            """.trimIndent(),
            mapOf(
                "groupId" to groupId,
                "name" to name,
                "leader" to leaderVehicleId,
                "lat" to destination.lat,
                "lon" to destination.lon,
                "destName" to destination.name,
                "poiId" to destination.poiId,
                "address" to destination.address,
                "maxMembers" to maxMembers,
            ),
            ::mapRow,
        )!!

    fun findById(groupId: String): GroupRecord? =
        jdbc.query("SELECT $COLUMNS FROM travel_group WHERE group_id = :groupId", mapOf("groupId" to groupId), ::mapRow).firstOrNull()

    /** 행 잠금 — 합류(정원 검사)·나가기·종료·위임은 같은 그룹에 대해 직렬화된다 */
    fun findByIdForUpdate(groupId: String): GroupRecord? =
        jdbc.query("SELECT $COLUMNS FROM travel_group WHERE group_id = :groupId FOR UPDATE", mapOf("groupId" to groupId), ::mapRow).firstOrNull()

    fun findAllByIds(groupIds: Collection<String>): List<GroupRecord> =
        if (groupIds.isEmpty()) emptyList()
        else jdbc.query("SELECT $COLUMNS FROM travel_group WHERE group_id IN (:ids)", mapOf("ids" to groupIds.toSet()), ::mapRow)

    fun setLeader(groupId: String, vehicleId: String) {
        jdbc.update("UPDATE travel_group SET leader_vehicle_id = :vehicleId WHERE group_id = :groupId", mapOf("groupId" to groupId, "vehicleId" to vehicleId))
    }

    /** ACTIVE → ENDED. 이미 종료됐으면 0 */
    fun end(groupId: String, endedAt: Instant): Int =
        jdbc.update(
            "UPDATE travel_group SET status = 'ENDED', ended_at = :endedAt WHERE group_id = :groupId AND status = 'ACTIVE'",
            mapOf("groupId" to groupId, "endedAt" to endedAt.toDb()),
        )

    private fun mapRow(rs: ResultSet, @Suppress("UNUSED_PARAMETER") rowNum: Int): GroupRecord {
        val lat = rs.getObject("dest_lat") as Double?
        val lon = rs.getObject("dest_lon") as Double?
        val destName = rs.getString("dest_name")
        val destination = if (lat != null && lon != null && destName != null) {
            Place(lat = lat, lon = lon, name = destName, poiId = rs.getString("dest_poi_id"), address = rs.getString("dest_address"))
        } else {
            null
        }
        return GroupRecord(
            groupId = rs.getString("group_id"),
            name = rs.getString("name"),
            leaderVehicleId = rs.getString("leader_vehicle_id"),
            destination = destination,
            maxMembers = rs.getInt("max_members"),
            status = GroupStatus.valueOf(rs.getString("status")),
            mode = rs.getString("mode"),
            createdAt = rs.requireInstant("created_at"),
            endedAt = rs.instant("ended_at"),
        )
    }

    companion object {
        private const val COLUMNS =
            "group_id, name, leader_vehicle_id, dest_lat, dest_lon, dest_name, dest_poi_id, dest_address, max_members, status, mode, created_at, ended_at"
    }
}
