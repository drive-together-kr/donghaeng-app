package app.donghaeng.identity.repo

import app.donghaeng.common.jdbc.instant
import app.donghaeng.common.jdbc.requireInstant
import app.donghaeng.common.jdbc.toDb
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant

/** `vehicle` 행 (docs/04 §1). `revokedAt != null` 이면 페어링 해제 상태. */
data class VehicleRecord(
    val vehicleId: String,
    val carInfoIdHash: String,
    val ownerId: String,
    val nickname: String,
    val brand: String?,
    val model: String?,
    val isPrimary: Boolean,
    val createdAt: Instant,
    val revokedAt: Instant?,
) {
    val active: Boolean get() = revokedAt == null
}

@Repository
class VehicleRepository(private val jdbc: NamedParameterJdbcTemplate) {

    fun insert(
        vehicleId: String,
        carInfoIdHash: String,
        ownerId: String,
        nickname: String,
        brand: String?,
        model: String?,
        isPrimary: Boolean,
    ): VehicleRecord =
        jdbc.queryForObject(
            """
            INSERT INTO vehicle (vehicle_id, car_info_id_hash, owner_id, nickname, brand, model, is_primary)
            VALUES (:vehicleId, :hash, :ownerId, :nickname, :brand, :model, :isPrimary)
            RETURNING $COLUMNS
            """.trimIndent(),
            mapOf(
                "vehicleId" to vehicleId,
                "hash" to carInfoIdHash,
                "ownerId" to ownerId,
                "nickname" to nickname,
                "brand" to brand,
                "model" to model,
                "isPrimary" to isPrimary,
            ),
            ::mapRow,
        )!!

    /** 페어링 재승인: 소유자 이전(또는 재등록) — 소유자·닉네임·대표 여부를 바꾸고 해제 상태를 푼다. brand/model 은 새 값이 있을 때만 갱신. */
    fun transfer(
        vehicleId: String,
        ownerId: String,
        nickname: String,
        brand: String?,
        model: String?,
        isPrimary: Boolean,
    ): VehicleRecord =
        jdbc.queryForObject(
            """
            UPDATE vehicle
               SET owner_id = :ownerId,
                   nickname = :nickname,
                   brand = COALESCE(:brand, brand),
                   model = COALESCE(:model, model),
                   is_primary = :isPrimary,
                   revoked_at = NULL
             WHERE vehicle_id = :vehicleId
            RETURNING $COLUMNS
            """.trimIndent(),
            mapOf(
                "vehicleId" to vehicleId,
                "ownerId" to ownerId,
                "nickname" to nickname,
                "brand" to brand,
                "model" to model,
                "isPrimary" to isPrimary,
            ),
            ::mapRow,
        )!!

    /** 해제 여부와 무관하게 해시로 찾는다 (행 잠금 — 승인 트랜잭션 안에서 호출). */
    fun findByCarInfoIdHashForUpdate(hash: String): VehicleRecord? =
        jdbc.query("SELECT $COLUMNS FROM vehicle WHERE car_info_id_hash = :hash FOR UPDATE", mapOf("hash" to hash), ::mapRow)
            .firstOrNull()

    fun findById(vehicleId: String): VehicleRecord? =
        jdbc.query("SELECT $COLUMNS FROM vehicle WHERE vehicle_id = :vehicleId", mapOf("vehicleId" to vehicleId), ::mapRow)
            .firstOrNull()

    /** 여러 ID 를 한 번에 (해제 여부 무관). 빈 입력이면 빈 목록 */
    fun findAllByIds(vehicleIds: Collection<String>): List<VehicleRecord> =
        if (vehicleIds.isEmpty()) emptyList()
        else jdbc.query("SELECT $COLUMNS FROM vehicle WHERE vehicle_id IN (:ids)", mapOf("ids" to vehicleIds.toSet()), ::mapRow)

    fun findActiveByIdAndOwner(vehicleId: String, ownerId: String): VehicleRecord? =
        jdbc.query(
            "SELECT $COLUMNS FROM vehicle WHERE vehicle_id = :vehicleId AND owner_id = :ownerId AND revoked_at IS NULL",
            mapOf("vehicleId" to vehicleId, "ownerId" to ownerId),
            ::mapRow,
        ).firstOrNull()

    /** 소유자의 활성 차량 — 대표 차량 먼저, 그다음 등록순 */
    fun listActiveByOwner(ownerId: String): List<VehicleRecord> =
        jdbc.query(
            "SELECT $COLUMNS FROM vehicle WHERE owner_id = :ownerId AND revoked_at IS NULL ORDER BY is_primary DESC, created_at ASC",
            mapOf("ownerId" to ownerId),
            ::mapRow,
        )

    /** 소유자에게 (해당 차량을 뺀) 활성 대표 차량이 있는가 — 새로 등록·이전되는 차량의 대표 여부 결정용 */
    fun hasActivePrimary(ownerId: String, excludingVehicleId: String? = null): Boolean {
        val exclusion = if (excludingVehicleId == null) "" else " AND vehicle_id <> :excluding"
        val count = jdbc.queryForObject(
            "SELECT count(*) FROM vehicle WHERE owner_id = :ownerId AND is_primary AND revoked_at IS NULL$exclusion",
            mapOf("ownerId" to ownerId, "excluding" to excludingVehicleId),
            Long::class.javaObjectType,
        )!!
        return count > 0
    }

    fun clearPrimary(ownerId: String) {
        jdbc.update("UPDATE vehicle SET is_primary = false WHERE owner_id = :ownerId AND is_primary", mapOf("ownerId" to ownerId))
    }

    fun setPrimary(vehicleId: String, isPrimary: Boolean) {
        jdbc.update("UPDATE vehicle SET is_primary = :isPrimary WHERE vehicle_id = :vehicleId", mapOf("vehicleId" to vehicleId, "isPrimary" to isPrimary))
    }

    fun updateNickname(vehicleId: String, nickname: String) {
        jdbc.update("UPDATE vehicle SET nickname = :nickname WHERE vehicle_id = :vehicleId", mapOf("vehicleId" to vehicleId, "nickname" to nickname))
    }

    /** 페어링 해제. 이미 해제됐거나 소유자가 다르면 0 */
    fun revoke(vehicleId: String, ownerId: String, now: Instant): Int =
        jdbc.update(
            """
            UPDATE vehicle SET revoked_at = :now, is_primary = false
             WHERE vehicle_id = :vehicleId AND owner_id = :ownerId AND revoked_at IS NULL
            """.trimIndent(),
            mapOf("vehicleId" to vehicleId, "ownerId" to ownerId, "now" to now.toDb()),
        )

    private fun mapRow(rs: ResultSet, @Suppress("UNUSED_PARAMETER") rowNum: Int) = VehicleRecord(
        vehicleId = rs.getString("vehicle_id"),
        carInfoIdHash = rs.getString("car_info_id_hash"),
        ownerId = rs.getString("owner_id"),
        nickname = rs.getString("nickname"),
        brand = rs.getString("brand"),
        model = rs.getString("model"),
        isPrimary = rs.getBoolean("is_primary"),
        createdAt = rs.requireInstant("created_at"),
        revokedAt = rs.instant("revoked_at"),
    )

    companion object {
        private const val COLUMNS = "vehicle_id, car_info_id_hash, owner_id, nickname, brand, model, is_primary, created_at, revoked_at"
    }
}
