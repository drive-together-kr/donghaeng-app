package app.donghaeng.companion.demo.donghaeng

/**
 * 카메라 없이 QR 을 대신하는 직접 입력(D027·D039). 차 페어링 화면은 "또는 공식 앱에 이 코드를 입력하세요 KQ7-4MZ" 를 보여 주므로
 * 그 **코드**만 넣어도 등록되게 한다. 입력을 서버가 만드는 QR 과 같은 URL 로 바꿔 기존 경로 규칙(QrPathRule)·모듈 검증을 그대로 탄다
 * (모듈 공개 API 는 그대로 — URL 은 서버 공개 주소 + 경로, CLAUDE.md 규칙 12).
 *
 * - 차 화면 코드 6자(하이픈·공백·소문자 허용, 예 `kq7-4mz`) → `{base}/pair?c=KQ74MZ` (차량 등록)
 * - 초대 토큰 `inv_…` → `{base}/j/inv_…` (그룹 합류)
 * - `http://`·`https://` 로 시작하면 QR 문자열 그대로(초대 URL·verifyUri 붙여넣기)
 * - 그 밖 → null (형식 안내)
 */
object ManualQrInput {
    private val CODE = Regex("^[A-Z0-9]{6}$")
    private val INVITE = Regex("^inv_[A-Za-z0-9]+$")

    fun toQrPayload(input: String, publicBaseUrl: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) return trimmed
        val base = publicBaseUrl.trim().trimEnd('/')
        if (INVITE.matches(trimmed)) return "$base/j/$trimmed"
        val code = trimmed.filterNot { it == '-' || it.isWhitespace() }.uppercase()
        if (CODE.matches(code)) return "$base/pair?c=$code"
        return null
    }
}

/**
 * 카메라 분석기는 프레임마다 같은 QR 로 콜백을 부른다(D081). 시트를 닫거나 등록을 마친 뒤 같은 QR 이 아직 화면에 있으면 곧바로 다시 뜨고,
 * 동행 QR 이 아니면 토스트가 쌓인다. 그래서 **같은 값은 마지막으로 본 뒤 [quietMs] 동안 계속 무시**한다(보이는 동안은 창이 늘어난다) —
 * 폰을 치웠다가 다시 비추면 다시 처리된다. 직접 입력의 [적용] 은 이 문을 거치지 않는다(사용자의 명시적 동작).
 */
class QrScanGate(
    private val quietMs: Long = QUIET_MS,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private var last: String? = null
    private var lastSeenAt = 0L

    /** 처리해야 하면 true. 같은 값이 조용한 창 안이면 false(창을 늘린다) */
    fun accept(payload: String): Boolean {
        val now = clock()
        if (payload == last && now - lastSeenAt < quietMs) {
            lastSeenAt = now
            return false
        }
        last = payload
        lastSeenAt = now
        return true
    }

    /** 시트·대화상자를 닫거나 처리를 끝낸 순간 — 그때부터 같은 값을 [quietMs] 동안 무시 */
    fun markHandled(payload: String) {
        last = payload
        lastSeenAt = clock()
    }

    companion object {
        const val QUIET_MS = 2_500L
    }
}
