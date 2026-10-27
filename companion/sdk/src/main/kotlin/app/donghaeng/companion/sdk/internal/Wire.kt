package app.donghaeng.companion.sdk.internal

import app.donghaeng.companion.sdk.AccountProvider
import app.donghaeng.protocol.DhJson
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.Base64

/*
 * REST 전송에 쓰는 작은 도우미 — 제공자 이름의 wire 표기, JWT exp 읽기.
 * 공개 API 가 아니므로 전부 internal.
 */

/** `POST /v1/auth/exchange` 의 `provider` 값 (docs/03 §1.1: dev | bluelink | myhyundai | kia_connect | genesis_connected) */
internal val AccountProvider.wireName: String
    get() = when (this) {
        AccountProvider.BLUELINK -> "bluelink"
        AccountProvider.MYHYUNDAI -> "myhyundai"
        AccountProvider.KIA_CONNECT -> "kia_connect"
        AccountProvider.GENESIS_CONNECTED -> "genesis_connected"
        AccountProvider.DEV -> "dev"
    }

/**
 * JWT 의 클레임을 **검증 없이** 읽는다(서명 검증은 서버 몫). 모듈은 동행 JWT 의 `exp` 만 캐시 만료 판단에 쓴다.
 */
internal object JwtClaims {

    /** `exp`(초) → epoch ms. JWT 형식이 아니거나 exp 가 없으면 null */
    fun expiresAtMs(token: String): Long? {
        val parts = token.split('.')
        if (parts.size < 2) return null
        val payload = runCatching { String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8) }.getOrNull() ?: return null
        val obj = runCatching { DhJson.json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return null
        val expS = runCatching { obj["exp"]?.jsonPrimitive?.longOrNull }.getOrNull() ?: return null
        return expS * 1000L
    }
}
