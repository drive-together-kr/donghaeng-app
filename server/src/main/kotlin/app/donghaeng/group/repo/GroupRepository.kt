package app.donghaeng.group.repo

import app.donghaeng.common.jdbc.instant
import app.donghaeng.common.jdbc.requireInstant
import app.donghaeng.common.jdbc.toDb
import app.donghaeng.protocol.DhJson
import app.donghaeng.protocol.Place
import kotlinx.serialization.builtins.ListSerializer
import org.springframework.jdbc.core.SqlParameterValue
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Types
import java.time.Instant

/** `travel_group.status` (docs/04 §1) */
enum class GroupStatus { ACTIVE, ENDED }

/**
 * `travel_group` 행. [destination] 은 그룹 목적지 — 선두 `ROUTE_CHANGED` 가 오면 최신 값으로 바뀐다(C032, V7).
 * [waypoints] 는 선두의 마지막 경로의 경유지(V7 `route_waypoints`, 없으면 빈 목록 — 그룹이 끝나면 지운다).
 */
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
    val waypoints: List<Place> = emptyList(),
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

    /**
     * 공유 잠금 — 그룹에 딸린 자원(F16 무전)을 올리는 트랜잭션이 잡는다. 여러 업로드끼리는 서로 막지 않고,
     * 종료·나가기·합류의 [findByIdForUpdate] 와는 직렬화된다(업로드가 끝난 그룹에 무전을 남기지 않게 — `GroupAccessApi`).
     */
    fun findByIdForShare(groupId: String): GroupRecord? =
        jdbc.query("SELECT $COLUMNS FROM travel_group WHERE group_id = :groupId FOR SHARE", mapOf("groupId" to groupId), ::mapRow).firstOrNull()

    /** 활성 그룹 전부 — presence 2초 스냅샷용(테이블이 작다는 전제, 생성순) */
    fun findActive(): List<GroupRecord> =
        jdbc.query("SELECT $COLUMNS FROM travel_group WHERE status = 'ACTIVE' ORDER BY created_at ASC", ::mapRow)

    fun findAllByIds(groupIds: Collection<String>): List<GroupRecord> =
        if (groupIds.isEmpty()) emptyList()
        else jdbc.query("SELECT $COLUMNS FROM travel_group WHERE group_id IN (:ids)", mapOf("ids" to groupIds.toSet()), ::mapRow)

    /**
     * 선두 경로 변경(C032): 그룹 목적지와 경유지를 최신 값으로 바꾼다. 늦게 합류한 차의 `GROUP_JOINED`·초대 미리보기·스냅샷 ARRIVED 판정이 이 값을 쓴다.
     * 호출자가 그룹 행을 잠근 뒤 부른다.
     */
    fun updateRoute(groupId: String, destination: Place, waypoints: List<Place>, now: Instant): Int =
        jdbc.update(
            """
            UPDATE travel_group
               SET dest_lat = :lat, dest_lon = :lon, dest_name = :destName, dest_poi_id = :poiId, dest_address = :address,
                   route_waypoints = CAST(:waypoints AS jsonb), route_updated_at = :now
             WHERE group_id = :groupId AND status = 'ACTIVE'
            """.trimIndent(),
            mapOf(
                "groupId" to groupId,
                "lat" to destination.lat,
                "lon" to destination.lon,
                "destName" to destination.name,
                "poiId" to destination.poiId,
                "address" to destination.address,
                "waypoints" to SqlParameterValue(Types.VARCHAR, if (waypoints.isEmpty()) null else DhJson.json.encodeToString(PLACES, waypoints)),
                "now" to now.toDb(),
            ),
        )

    fun setLeader(groupId: String, vehicleId: String) {
        jdbc.update("UPDATE travel_group SET leader_vehicle_id = :vehicleId WHERE group_id = :groupId", mapOf("groupId" to groupId, "vehicleId" to vehicleId))
    }

    /** ACTIVE → ENDED. 이미 종료됐으면 0. 경유지(위치성 데이터)는 지운다 — PRD §7 '도착·경유지: 그룹 종료 시 삭제' */
    fun end(groupId: String, endedAt: Instant): Int =
        jdbc.update(
            "UPDATE travel_group SET status = 'ENDED', ended_at = :endedAt, route_waypoints = NULL WHERE group_id = :groupId AND status = 'ACTIVE'",
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
            waypoints = rs.getString("route_waypoints")?.let { runCatching { DhJson.json.decodeFromString(PLACES, it) }.getOrNull() }.orEmpty(),
        )
    }

    companion object {
        private val PLACES = ListSerializer(Place.serializer())
        private const val COLUMNS =
            "group_id, name, leader_vehicle_id, dest_lat, dest_lon, dest_name, dest_poi_id, dest_address, max_members, status, mode, created_at, ended_at, " +
                "route_waypoints::text AS route_waypoints"
    }
}
