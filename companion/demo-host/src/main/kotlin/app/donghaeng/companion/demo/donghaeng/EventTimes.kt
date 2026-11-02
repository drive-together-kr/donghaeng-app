package app.donghaeng.companion.demo.donghaeng

/**
 * 그룹 이벤트에는 시각 필드가 없다(protocol GroupEvent). 서버 이벤트 ID 는 `evt_` + ULID 이고 ULID 앞 10자가 생성 시각(epoch ms, Crockford Base32)이라
 * 알림 목록의 "3분 전" 을 그 값으로 그린다(D083 — 예전에는 받은 순간 = 모두 '방금'). ULID 가 아닌 ID(시연 `evt_demo_…`)는 받은 시각.
 * 폰 시계가 서버보다 늦어 미래 시각이 나오면 받은 시각으로 자른다.
 */
object EventTimes {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val ULID_LENGTH = 26

    /** 2020-01-01 — 이보다 이른 값은 ULID 가 아닌 우연한 문자열로 본다 */
    private const val MIN_PLAUSIBLE_MS = 1_577_836_800_000L

    fun atMs(eventId: String, receivedAtMs: Long): Long {
        val ulid = eventId.substringAfter('_', missingDelimiterValue = "")
        if (ulid.length != ULID_LENGTH) return receivedAtMs
        var ms = 0L
        for (c in ulid.substring(0, 10)) {
            val v = ALPHABET.indexOf(c.uppercaseChar())
            if (v < 0) return receivedAtMs
            ms = (ms shl 5) or v.toLong()
        }
        if (ms < MIN_PLAUSIBLE_MS) return receivedAtMs
        return minOf(ms, receivedAtMs)
    }
}
