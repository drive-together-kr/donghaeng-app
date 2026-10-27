package app.donghaeng.event

/**
 * 한국어 조사 선택 (docs/06 §4 "조사(이/가, 을/를) 처리 유틸 포함(받침 판별)").
 *
 * 마지막 글자가 한글이면 종성 유무로, 숫자면 읽는 소리(영·일·삼·육·칠·팔 = 받침 있음)로 판정한다.
 * 그 외(영문·기호)는 받침 없음으로 본다. 끝의 공백·따옴표·괄호는 무시한다.
 */
object Josa {

    /** `이` / `가` */
    fun iGa(word: String): String = pick(word, "이", "가")

    /** `은` / `는` */
    fun eunNeun(word: String): String = pick(word, "은", "는")

    /** `을` / `를` */
    fun eulReul(word: String): String = pick(word, "을", "를")

    /** `과` / `와` */
    fun gwaWa(word: String): String = pick(word, "과", "와")

    /** `으로` / `로` — ㄹ 받침은 `로` */
    fun euroRo(word: String): String {
        val last = lastMeaningfulChar(word) ?: return "로"
        if (!hasBatchim(last)) return "로"
        return if (isHangul(last) && (last.code - HANGUL_BASE) % 28 == RIEUL) "로" else "으로"
    }

    /** 단어 뒤에 조사를 붙인 문자열 */
    fun attach(word: String, withBatchim: String, withoutBatchim: String): String = word + pick(word, withBatchim, withoutBatchim)

    fun hasBatchim(word: String): Boolean = lastMeaningfulChar(word)?.let { hasBatchim(it) } ?: false

    private fun pick(word: String, withBatchim: String, withoutBatchim: String): String =
        if (hasBatchim(word)) withBatchim else withoutBatchim

    private fun hasBatchim(c: Char): Boolean = when {
        isHangul(c) -> (c.code - HANGUL_BASE) % 28 != 0
        c in '0'..'9' -> c in DIGITS_WITH_BATCHIM
        else -> false
    }

    private fun isHangul(c: Char): Boolean = c.code in HANGUL_BASE..HANGUL_LAST

    private fun lastMeaningfulChar(word: String): Char? =
        word.trimEnd().trimEnd(*IGNORED_TAIL).lastOrNull()

    private const val HANGUL_BASE = 0xAC00
    private const val HANGUL_LAST = 0xD7A3
    private const val RIEUL = 8
    private const val DIGITS_WITH_BATCHIM = "013678"
    private val IGNORED_TAIL = charArrayOf(')', ']', '}', '"', '\'', '”', '’', '」', '』', '.', '!', '?', ' ')
}
