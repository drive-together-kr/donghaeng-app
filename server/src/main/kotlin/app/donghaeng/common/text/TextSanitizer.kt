package app.donghaeng.common.text

/**
 * 사용자·차량이 보낸 문자열을 음성 문구·화면에 넣기 전에 정리한다 (C105).
 *
 * - 제어 문자(`\p{Cntrl}`)·서식 문자(`\p{Cf}` — 양방향 재정렬 U+202E, 폭 없는 공백 등)를 공백으로 바꾸고, 연속 공백을 하나로 접고 앞뒤를 자른다.
 * - [clean] 은 [max] 자(UTF-16 단위)를 넘으면 자르고(서로게이트 쌍을 반으로 가르지 않는다) `…` 을 붙이지 않는다 — 음성 문구가 끝없이 길어지지 않게.
 * - 정리 결과가 비면 null.
 *
 * 동승자 메시지(`GroupService`)는 길이를 넘으면 자르지 않고 400 으로 거부한다 — 사람이 쓴 문장을 몰래 바꾸지 않기 위해([normalize] 만 쓴다).
 */
object TextSanitizer {

    private val CONTROL_OR_FORMAT = Regex("[\\p{Cntrl}\\p{Cf}]")
    private val WHITESPACE = Regex("\\s+")

    /** 제어·서식 문자 제거 + 공백 접기 + trim. 길이는 건드리지 않는다. 비면 빈 문자열 */
    fun normalize(raw: String?): String =
        raw.orEmpty().replace(CONTROL_OR_FORMAT, " ").replace(WHITESPACE, " ").trim()

    /** [normalize] 뒤 [max] 자로 자른다. 비면 null */
    fun clean(raw: String?, max: Int): String? {
        require(max > 0) { "max must be positive" }
        val text = normalize(raw)
        if (text.isEmpty()) return null
        if (text.length <= max) return text
        var end = max
        // 서로게이트 쌍(이모지 등)의 앞쪽 반만 남지 않게
        if (Character.isHighSurrogate(text[end - 1])) end -= 1
        return text.substring(0, end).trimEnd().ifEmpty { null }
    }
}
