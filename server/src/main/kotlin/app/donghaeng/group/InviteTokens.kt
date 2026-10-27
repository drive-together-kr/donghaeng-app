package app.donghaeng.group

import app.donghaeng.common.security.SecureTokens

/**
 * 그룹 초대 토큰 (docs/06 §5): `inv_` + 랜덤 32바이트 Base62. 원문은 QR(`{base}/j/{token}`)로만 전달되고 DB 에는 SHA-256 만 남는다.
 */
object InviteTokens {
    const val PREFIX = "inv_"
    const val MAX_LENGTH = 96

    fun generate(): String = PREFIX + SecureTokens.randomBase62Bytes(32)

    fun hash(rawToken: String): String = SecureTokens.sha256Hex(rawToken)

    /** 형식이 맞으면 trim 한 원문, 아니면 null (조회 전에 거른다 — 해시 계산·DB 왕복 절약) */
    fun normalize(raw: String?): String? {
        val token = raw?.trim() ?: return null
        if (token.length <= PREFIX.length || token.length > MAX_LENGTH) return null
        if (!token.startsWith(PREFIX)) return null
        if (!SecureTokens.isBase62(token.substring(PREFIX.length))) return null
        return token
    }
}
