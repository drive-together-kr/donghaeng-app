package app.donghaeng.car.core.common

import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

/**
 * 좌표 마스킹 (CLAUDE.md 규칙 8: 로그에 좌표 원문 금지).
 *
 * 소수 첫째 자리까지만 남기고 나머지는 `**` 로 가린다. 0.1° ≈ 11 km 이므로
 * 로그만 보고 차량 위치를 특정할 수 없지만, 어느 지역인지(디버깅에 필요한 수준)는 알 수 있다.
 *
 * ```
 * CoordinateMasking.mask(37.123456)             // "37.1**"
 * CoordinateMasking.mask(37.123456, 127.65432)  // "(37.1**, 127.6**)"
 * ```
 */
object CoordinateMasking {

    private const val HIDDEN = "**"

    /** 위도 또는 경도 하나를 마스킹한다. null / NaN / 무한대는 "?" */
    fun mask(value: Double?): String {
        if (value == null || value.isNaN() || value.isInfinite()) return "?"
        val sign = if (value < 0) "-" else ""
        val magnitude = abs(value)
        val integerPart = floor(magnitude).toLong()
        // 소수 첫째 자리는 반올림이 아니라 버림 — 36.98 → "36.9**" (37.0 으로 올라가면 다른 칸처럼 보인다)
        val firstDecimal = floor((magnitude - integerPart) * 10 + 1e-9).toInt().coerceIn(0, 9)
        return String.format(Locale.ROOT, "%s%d.%d%s", sign, integerPart, firstDecimal, HIDDEN)
    }

    /** 위·경도 쌍을 "(lat, lon)" 형태로 마스킹한다. */
    fun mask(lat: Double?, lon: Double?): String = "(${mask(lat)}, ${mask(lon)})"

    /**
     * 임의의 문자열에서 좌표처럼 보이는 숫자(소수점 둘째 자리 이상)를 찾아 마스킹한다.
     * SDK 가 toString() 한 객체를 그대로 로그에 남겨야 할 때 마지막 안전망으로 쓴다.
     */
    fun maskInText(text: String): String =
        COORDINATE_LIKE.replace(text) { match -> mask(match.value.toDoubleOrNull()) }

    private val COORDINATE_LIKE = Regex("""-?\d{1,3}\.\d{2,}""")
}

/**
 * 닉네임·토큰 마스킹 (CLAUDE.md 규칙 8).
 */
object TextMasking {

    /** 닉네임: 첫 글자만 남긴다. "아빠차" → "아**" */
    fun maskNickname(nickname: String?): String {
        if (nickname.isNullOrEmpty()) return "?"
        return nickname.take(1) + "*".repeat((nickname.length - 1).coerceIn(1, 8))
    }

    /** 토큰·코드: 앞 4자리 + 길이만 남긴다. "eyJhbGciOi…" → "eyJh…(len=187)" */
    fun maskToken(token: String?): String {
        if (token.isNullOrEmpty()) return "?"
        return token.take(4) + "…(len=" + token.length + ")"
    }
}
