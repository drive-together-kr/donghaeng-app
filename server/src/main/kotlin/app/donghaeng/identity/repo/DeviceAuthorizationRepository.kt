package app.donghaeng.identity.repo

import app.donghaeng.common.jdbc.instant
import app.donghaeng.common.jdbc.requireInstant
import app.donghaeng.common.jdbc.toDb
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant

/** `device_authorization.status` (docs/04 §1) */
enum class DeviceAuthStatus { PENDING, APPROVED, CONSUMED, EXPIRED }

/** `device_authorization` 행 */
data class DeviceAuthorizationRecord(
    val deviceCode: String,
    val userCode: String,
    val carInfoIdHash: String,
    val brand: String?,
    val model: String?,
    val status: DeviceAuthStatus,
    val approvedBy: String?,
    val nickname: String?,
    val expiresAt: Instant,
    val createdAt: Instant,
)

@Repository
class DeviceAuthorizationRepository(private val jdbc: NamedParameterJdbcTemplate) {

    /** user_code UNIQUE 충돌 시 DuplicateKeyException — 호출자가 코드를 다시 뽑아 재시도한다. */
    fun insertPending(
        deviceCode: String,
        userCode: String,
        carInfoIdHash: String,
        brand: String?,
        model: String?,
        expiresAt: Instant,
    ) {
        jdbc.update(
            """
            INSERT INTO device_authorization (device_code, user_code, car_info_id_hash, brand, model, status, expires_at)
            VALUES (:deviceCode, :userCode, :hash, :brand, :model, 'PENDING', :expiresAt)
            """.trimIndent(),
            mapOf(
                "deviceCode" to deviceCode,
                "userCode" to userCode,
                "hash" to carInfoIdHash,
                "brand" to brand,
                "model" to model,
                "expiresAt" to expiresAt.toDb(),
            ),
        )
    }

    fun findByUserCodeForUpdate(userCode: String): DeviceAuthorizationRecord? =
        jdbc.query("SELECT $COLUMNS FROM device_authorization WHERE user_code = :userCode FOR UPDATE", mapOf("userCode" to userCode), ::mapRow)
            .firstOrNull()

    fun findByDeviceCodeForUpdate(deviceCode: String): DeviceAuthorizationRecord? =
        jdbc.query("SELECT $COLUMNS FROM device_authorization WHERE device_code = :deviceCode FOR UPDATE", mapOf("deviceCode" to deviceCode), ::mapRow)
            .firstOrNull()

    fun approve(deviceCode: String, approvedBy: String, nickname: String) {
        jdbc.update(
            """
            UPDATE device_authorization
               SET status = 'APPROVED', approved_by = :approvedBy, nickname = :nickname
             WHERE device_code = :deviceCode
            """.trimIndent(),
            mapOf("deviceCode" to deviceCode, "approvedBy" to approvedBy, "nickname" to nickname),
        )
    }

    fun setStatus(deviceCode: String, status: DeviceAuthStatus) {
        jdbc.update(
            "UPDATE device_authorization SET status = :status WHERE device_code = :deviceCode",
            mapOf("deviceCode" to deviceCode, "status" to status.name),
        )
    }

    private fun mapRow(rs: ResultSet, @Suppress("UNUSED_PARAMETER") rowNum: Int) = DeviceAuthorizationRecord(
        deviceCode = rs.getString("device_code"),
        userCode = rs.getString("user_code"),
        carInfoIdHash = rs.getString("car_info_id_hash"),
        brand = rs.getString("brand"),
        model = rs.getString("model"),
        status = DeviceAuthStatus.valueOf(rs.getString("status")),
        approvedBy = rs.getString("approved_by"),
        nickname = rs.getString("nickname"),
        expiresAt = rs.requireInstant("expires_at"),
        createdAt = rs.requireInstant("created_at"),
    )

    companion object {
        private const val COLUMNS =
            "device_code, user_code, car_info_id_hash, brand, model, status, approved_by, nickname, expires_at, created_at"
    }
}
