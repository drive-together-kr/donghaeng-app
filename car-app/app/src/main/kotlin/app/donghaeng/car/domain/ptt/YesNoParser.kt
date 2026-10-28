package app.donghaeng.car.domain.ptt

/** 무전 "보낼까요?" 에 대한 판정 */
enum class YesNo { YES, NO, NONE }

/**
 * 인식 문장 → 네/아니 판정 (순수, F16). 인식기(Pleos STT·Android SpeechRecognizer·Vosk 단어 문법)가 돌려준 문장 한 줄을 본다.
 *
 * 1. 문장부호(`.,!?~…"'`)를 지우고 띄어쓰기로 나눈다. Vosk 의 `[unk]`(문법 밖 소리)는 버린다. 남은 게 없으면 [YesNo.NONE].
 * 2. **아니 우선**: 띄어쓰기를 지운 문장에 [NO_PATTERNS](아니·아니요·취소·싫어·하지마·보내지마·안보내·안돼·그만)가 하나라도 있으면 [YesNo.NO].
 *    "네 아니요"처럼 섞이면 보내지 않는 쪽 — 원치 않는 녹음이 나가는 것보다 한 번 더 누르는 편이 낫다.
 * 3. **네는 전부 네일 때만**: 모든 낱말이 [YES_WORDS] 의 이어 붙임으로 나뉘면(끝의 `요`·`용` 은 떼고 본다 — "그래요", "보내줘요", "네네", "네보내")
 *    [YesNo.YES]. 하나라도 모르는 낱말이 있으면 [YesNo.NONE] — 자유 문장 인식기가 "어 그러니까 다음에"를 "어"(네)로 보내지 않게.
 * 4. "내"는 STT 가 "네"를 자주 이렇게 적어(동음) 네 낱말에 넣었다. 문장 전체가 네 낱말일 때만 효력이 있어 "내 차"는 NONE.
 *
 * 인식 원문은 로그에 남기지 않는다(규칙 8) — 호출 쪽은 판정만 남긴다.
 */
object YesNoParser {

    /** 예 낱말(사용자 지시: 네·예·응·어·그래·보내·보내줘) + 흔한 변형 */
    val YES_WORDS: List<String> = listOf(
        "네", "넵", "넹", "내", "예", "옙", "응", "웅", "어", "그래", "보내", "보내줘", "보내주세", "좋아", "오케이", "ok", "okay", "yes",
    )

    /** 아니 표현(사용자 지시: 아니·아니요·아니오·취소·싫어·하지마) + 부정형 — 띄어쓰기를 지운 문장에서 찾는다 */
    val NO_PATTERNS: List<String> = listOf(
        "아니", "아뇨", "아냐", "취소", "싫어", "싫다", "하지마", "하지말", "보내지마", "보내지말", "안보내", "안돼", "안되", "그만", "no",
    )

    /** Vosk 단어 문법(그대로 JSON 배열로 넘긴다) — 예/아니 단어 + `[unk]` */
    val VOSK_GRAMMAR_WORDS: List<String> = listOf(
        "네", "예", "응", "어", "그래", "보내", "보내줘", "아니", "아니요", "아니오", "취소", "싫어", "하지마", "[unk]",
    )

    private val PUNCTUATION = Regex("""[.,!?~…"'“”‘’·:;()\[\]{}]""")
    private val SPACES = Regex("""\s+""")
    private const val UNK = "[unk]"

    fun parse(text: String?): YesNo {
        if (text.isNullOrBlank()) return YesNo.NONE
        val tokens = text.lowercase().trim().split(SPACES).filter { it.isNotEmpty() && it != UNK }
            .map { it.replace(PUNCTUATION, "") }
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return YesNo.NONE
        val compact = tokens.joinToString("")
        if (NO_PATTERNS.any { compact.contains(it) }) return YesNo.NO
        return if (tokens.all { isYesToken(it) }) YesNo.YES else YesNo.NONE
    }

    /** 여러 후보(인식기 N-best) 중 첫 판정 — 아니가 하나라도 있으면 아니, 없으면 첫 네, 둘 다 없으면 NONE */
    fun parseCandidates(candidates: List<String>): YesNo {
        val verdicts = candidates.map { parse(it) }
        return when {
            YesNo.NO in verdicts -> YesNo.NO
            YesNo.YES in verdicts -> YesNo.YES
            else -> YesNo.NONE
        }
    }

    private fun isYesToken(token: String): Boolean {
        val stem = token.removeSuffix("요").removeSuffix("용").ifEmpty { token }
        return segmentable(stem) || segmentable(token)
    }

    /** [word] 를 [YES_WORDS] 의 이어 붙임으로 완전히 나눌 수 있는가(DP) */
    private fun segmentable(word: String): Boolean {
        if (word.isEmpty()) return false
        val ok = BooleanArray(word.length + 1)
        ok[0] = true
        for (end in 1..word.length) {
            for (start in 0 until end) {
                if (ok[start] && word.substring(start, end) in YES_SET) {
                    ok[end] = true
                    break
                }
            }
        }
        return ok[word.length]
    }

    private val YES_SET: Set<String> = YES_WORDS.toSet()
}
