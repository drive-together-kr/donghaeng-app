package app.donghaeng.identity.repo

import app.donghaeng.common.id.Ids
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet

/** `app_user` 행 (docs/04 §1) */
data class UserRecord(
    val userId: String,
    val provider: String,
    val providerSub: String,
    val displayName: String,
)

@Repository
class UserRepository(private val jdbc: NamedParameterJdbcTemplate) {

    /**
     * `(provider, provider_sub)` 로 찾고 없으면 만든다. 있으면 표시 이름만 최신값으로 바꾼다.
     * 새 행의 user_id 는 `usr_` + ULID (충돌 시 버려진다).
     */
    fun upsert(provider: String, providerSub: String, displayName: String): UserRecord =
        jdbc.queryForObject(
            """
            INSERT INTO app_user (user_id, provider, provider_sub, display_name)
            VALUES (:userId, :provider, :providerSub, :displayName)
            ON CONFLICT (provider, provider_sub) DO UPDATE SET display_name = EXCLUDED.display_name
            RETURNING user_id, provider, provider_sub, display_name
            """.trimIndent(),
            mapOf(
                "userId" to Ids.user(),
                "provider" to provider,
                "providerSub" to providerSub,
                "displayName" to displayName,
            ),
            ::mapRow,
        )!!

    fun findById(userId: String): UserRecord? =
        jdbc.query(
            "SELECT user_id, provider, provider_sub, display_name FROM app_user WHERE user_id = :userId",
            mapOf("userId" to userId),
            ::mapRow,
        ).firstOrNull()

    private fun mapRow(rs: ResultSet, @Suppress("UNUSED_PARAMETER") rowNum: Int) = UserRecord(
        userId = rs.getString("user_id"),
        provider = rs.getString("provider"),
        providerSub = rs.getString("provider_sub"),
        displayName = rs.getString("display_name"),
    )
}
