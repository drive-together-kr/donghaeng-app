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
    /** 처음 토큰을 내준 시각(CONSUMED 가 된 시각, V7). 그 전이면 null */
    val consumedAt: Instant? = null,
    /** 마지막으로 내준 차량 refresh 토큰의 SHA-256(V7) — 응답 유실 재수령 때 그것을 폐기하고 새로 준다(C112) */
    val issuedRefreshHash: String? = null,
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

    /** 첫 토큰 발급: CONSUMED + 발급 시각 + 내준 refresh 해시 */
    fun markConsumed(deviceCode: String, now: Instant, refreshHash: String) {
        jdbc.update(
            "UPDATE device_authorization SET status = 'CONSUMED', consumed_at = :now, issued_refresh_hash = :hash WHERE device_code = :deviceCode",
            mapOf("deviceCode" to deviceCode, "now" to now.toDb(), "hash" to refreshHash),
        )
    }

    /** 응답 유실 재수령: 내준 refresh 해시만 바꾼다(`consumed_at` 은 처음 그대로 — 유예가 늘지 않게) */
    fun updateIssuedRefresh(deviceCode: String, refreshHash: String) {
        jdbc.update(
            "UPDATE device_authorization SET issued_refresh_hash = :hash WHERE device_code = :deviceCode",
            mapOf("deviceCode" to deviceCode, "hash" to refreshHash),
        )
    }

    /** 정기 정리 — [before] 보다 먼저 만료된 코드. 지운 수 */
    fun deleteExpiredBefore(before: Instant): Int =
        jdbc.update("DELETE FROM device_authorization WHERE expires_at < :before", mapOf("before" to before.toDb()))

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
        consumedAt = rs.instant("consumed_at"),
        issuedRefreshHash = rs.getString("issued_refresh_hash"),
    )

    companion object {
        private const val COLUMNS =
            "device_code, user_code, car_info_id_hash, brand, model, status, approved_by, nickname, expires_at, created_at, consumed_at, issued_refresh_hash"
    }
}
