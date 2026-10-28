package app.donghaeng.car.domain.ptt

import org.junit.Assert.assertEquals
import org.junit.Test

class YesNoParserTest {

    private fun p(text: String?) = YesNoParser.parse(text)

    @Test
    fun `예 낱말 — 사용자 지시 목록과 조사·띄어쓰기·문장부호 변형`() {
        listOf("네", "예", "응", "어", "그래", "보내", "보내줘").forEach { assertEquals(it, YesNo.YES, p(it)) }
        listOf("네, 보내", "네 보내", "네보내", "네네", "응응", "그래요", "보내줘요", "보내주세요", "네!", " 네. ", "예 예", "좋아요", "넵", "내").forEach {
            assertEquals(it, YesNo.YES, p(it))
        }
    }

    @Test
    fun `아니 표현 — 부정형 포함, 섞이면 아니가 이긴다`() {
        listOf("아니", "아니요", "아니오", "취소", "싫어", "하지마", "하지 마", "아니요, 취소", "취소해", "보내지 마", "보내지마", "안 보내", "안돼", "그만", "아냐").forEach {
            assertEquals(it, YesNo.NO, p(it))
        }
        assertEquals("네 아니요 → 보내지 않는 쪽", YesNo.NO, p("네 아니요"))
        assertEquals(YesNo.NO, p("아니 보내"))
    }

    @Test
    fun `모르는 말·빈 값·Vosk unk 는 NONE — 문장 전체가 네일 때만 YES`() {
        listOf(null, "", "   ", "[unk]", "[unk] [unk]", "다음 휴게소에서 쉬었다 가요", "어 그러니까 다음에", "내 차", "네 그런데 잠깐만").forEach {
            assertEquals(it.toString(), YesNo.NONE, p(it))
        }
        assertEquals("unk 는 버리고 남은 낱말로 판정", YesNo.YES, p("[unk] 네"))
    }

    @Test
    fun `N-best 후보 — 아니가 하나라도 있으면 아니, 없으면 첫 네`() {
        assertEquals(YesNo.YES, YesNoParser.parseCandidates(listOf("내 차", "네")))
        assertEquals(YesNo.NO, YesNoParser.parseCandidates(listOf("네", "아니")))
        assertEquals(YesNo.NONE, YesNoParser.parseCandidates(listOf("음", "글쎄")))
        assertEquals(YesNo.NONE, YesNoParser.parseCandidates(emptyList()))
    }

    @Test
    fun `Vosk 문법 단어는 사용자 지시 목록 + unk`() {
        assertEquals(
            listOf("네", "예", "응", "어", "그래", "보내", "보내줘", "아니", "아니요", "아니오", "취소", "싫어", "하지마", "[unk]"),
            YesNoParser.VOSK_GRAMMAR_WORDS,
        )
        // 문법 단어는 모두 파서가 판정할 수 있어야 한다
        YesNoParser.VOSK_GRAMMAR_WORDS.filter { it != "[unk]" }.forEach { assertEquals(it, true, p(it) != YesNo.NONE) }
    }
}
