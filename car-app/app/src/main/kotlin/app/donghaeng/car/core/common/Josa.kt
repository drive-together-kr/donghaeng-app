package app.donghaeng.car.core.common

/**
 * 한국어 조사 선택 — 차량 앱 화면 문구용(서버 `app.donghaeng.event.Josa` 와 **같은 규칙**, docs/05 §5).
 *
 * 판정 기준은 단어의 마지막 의미 있는 글자다(끝의 공백·따옴표·괄호·마침표는 무시).
 * - 한글: 종성(받침)이 있으면 받침 있음. "아빠 차" → 와, "엄마 차량" → 과.
 * - 숫자: 한자어로 읽는 소리 — 0 영·1 일·3 삼·6 육·7 칠·8 팔 은 받침 있음, 2 이·4 사·5 오·9 구 는 없음. "EV6" → 과, "2호차" 는 한글 끝.
 * - 영문·기호·빈 문자열: 받침 없음으로 본다(발음 추정은 하지 않는다). "DemoCarB" → 와 — 서버 음성 문구("DemoCarB가")와 맞춘다.
 *
 * 서버와 차 앱이 같은 닉네임에 다른 조사를 붙이지 않도록 규칙을 바꿀 때는 둘을 함께 바꾼다.
 */
object Josa {

    /** `과` / `와` */
    fun gwaWa(word: String): String = pick(word, "과", "와")

    /** 단어 끝(공백·괄호 등 제외)에 받침이 있는가 */
    fun hasBatchim(word: String): Boolean = lastMeaningfulChar(word)?.let { hasBatchim(it) } ?: false

    private fun pick(word: String, withBatchim: String, withoutBatchim: String): String =
        if (hasBatchim(word)) withBatchim else withoutBatchim

    private fun hasBatchim(c: Char): Boolean = when {
        c.code in HANGUL_BASE..HANGUL_LAST -> (c.code - HANGUL_BASE) % 28 != 0
        c in '0'..'9' -> c in DIGITS_WITH_BATCHIM
        else -> false
    }

    private fun lastMeaningfulChar(word: String): Char? = word.trimEnd().trimEnd(*IGNORED_TAIL).lastOrNull()

    private const val HANGUL_BASE = 0xAC00
    private const val HANGUL_LAST = 0xD7A3
    private const val DIGITS_WITH_BATCHIM = "013678"
    private val IGNORED_TAIL = charArrayOf(')', ']', '}', '"', '\'', '”', '’', '」', '』', '.', '!', '?', ' ')
}
