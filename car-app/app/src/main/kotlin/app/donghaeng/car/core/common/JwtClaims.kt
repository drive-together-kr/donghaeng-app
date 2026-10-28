package app.donghaeng.car.core.common

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.Base64

/**
 * 차량 JWT payload 의 시각 클레임 읽기 (T33 토큰 갱신·TOKEN_REVOKED 신선도 판정용).
 *
 * **서명은 검증하지 않는다** — 서버(REST·EMQX)가 검증하고, 차량은 "언제 만료되나 / 언제 발급됐나" 만 본다.
 * 형식이 다르거나(테스트용 가짜 토큰 등) 디코드에 실패하면 null. 토큰 원문은 로그에 남기지 않는다(규칙 8).
 * `java.util.Base64` 는 API 26+ (minSdk 28) — JVM 단위 테스트에서도 그대로 돈다.
 */
object JwtClaims {

    private val json = Json { ignoreUnknownKeys = true }

    /** `exp`(초) → epoch ms. 없거나 읽을 수 없으면 null */
    fun expiresAtMs(jwt: String?): Long? = seconds(jwt, "exp")?.times(1000)

    /** `iat`(초). 없거나 읽을 수 없으면 null */
    fun issuedAtSeconds(jwt: String?): Long? = seconds(jwt, "iat")

    private fun seconds(jwt: String?, claim: String): Long? {
        val payload = payload(jwt) ?: return null
        return runCatching { payload[claim]?.jsonPrimitive?.longOrNull }.getOrNull()
    }

    private fun payload(jwt: String?): JsonObject? {
        if (jwt.isNullOrBlank()) return null
        val parts = jwt.split('.')
        if (parts.size != 3 || parts[1].isEmpty()) return null
        return runCatching {
            val bytes = Base64.getUrlDecoder().decode(parts[1].padEnd((parts[1].length + 3) / 4 * 4, '='))
            json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject
        }.getOrNull()
    }
}
