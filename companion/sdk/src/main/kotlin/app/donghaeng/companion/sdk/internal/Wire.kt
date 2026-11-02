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
 * 선택 헤더 `Idempotency-Key` (docs/03 — 동승자 메시지·무전 업로드). 서버는 (호출자, 경로, 키) → 첫 응답을 24 h 기억하고
 * 같은 키가 다시 오면 저장된 응답을 그대로 돌려준다(다시 저장·팬아웃하지 않음). 형식 `[A-Za-z0-9_-]{8,64}`.
 *
 * 임시 중복 정의(RD-6, CLAUDE.md 규칙 7): 헤더 이름·형식은 protocol 의 `ApiHeaders.IDEMPOTENCY_KEY`·`IdempotencyKey.isValid` 가 원본이다
 * (서버 배치가 추가 — 아직 병합 전). 병합 뒤 [HEADER]·[isValid] 를 그것으로 바꾸고 [generate] 만 여기 둔다. 그때까지 값이 어긋나지 않게
 * 테스트가 계약 값(헤더 이름, 8·64자 경계, 허용 문자)을 고정한다.
 */
internal object IdempotencyKeys {
    const val HEADER = "Idempotency-Key"
    private val FORMAT = Regex("^[A-Za-z0-9_-]{8,64}$")

    fun isValid(key: String): Boolean = FORMAT.matches(key)

    /** 호출마다 새 키(UUID, 36자 — 형식 안). 호스트가 재시도에 같은 키를 쓰려면 직접 만들어 넘긴다 */
    fun generate(): String = java.util.UUID.randomUUID().toString()
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
