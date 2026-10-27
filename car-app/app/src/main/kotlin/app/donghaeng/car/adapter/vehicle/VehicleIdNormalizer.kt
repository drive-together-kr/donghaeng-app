package app.donghaeng.car.adapter.vehicle

import java.util.Locale

/**
 * SDK 가 준 차량 ID 원문 정리 (T11 실측: Pleos Connect AVD 의 CarInfo VIN/CRN 은 Gson 직렬화된 `"invalid"` — 따옴표 포함 9자).
 *
 * - 양끝 따옴표(JSON 문자열)와 공백을 벗긴다.
 * - 자리표시자(`invalid`, `unknown`, `null`, …)는 **없는 것**으로 본다 → 페어링은 설치별 폴백 ID 를 쓴다(`VehicleIdFallback`).
 * 원문을 로그에 남기지 않도록 [shape] 만 제공한다.
 */
object VehicleIdNormalizer {

    private val PLACEHOLDERS = setOf("", "invalid", "unknown", "null", "none", "n/a", "undefined", "0")

    /** 정리된 ID. 자리표시자·빈 값이면 null */
    fun normalize(raw: String?): String? {
        if (raw == null) return null
        var s = raw.trim()
        if (s.length >= 2 && s.first() == '"' && s.last() == '"') s = s.substring(1, s.length - 1).trim()
        return s.takeUnless { it.lowercase(Locale.ROOT) in PLACEHOLDERS }
    }

    /** 로그용 모양: `none` / `placeholder(len=9)` / `ok(len=17)` — 원문은 넣지 않는다(규칙 8). */
    fun shape(raw: String?): String {
        if (raw == null) return "none"
        val normalized = normalize(raw) ?: return "placeholder(len=${raw.length})"
        return "ok(len=${normalized.length})"
    }
}
