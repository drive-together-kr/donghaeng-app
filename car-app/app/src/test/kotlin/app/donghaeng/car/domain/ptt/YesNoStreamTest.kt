package app.donghaeng.car.domain.ptt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 스트리밍·구간 인식 판정(B014) — 아니는 부분 결과에서도 바로, 네는 최종 결과(또는 구간 뒤 잠깐 더 들은 뒤)에서만 */
class YesNoStreamTest {

    @Test
    fun `Pleos partial results - NO ends at once, YES only on the final result`() {
        assertNull("첫 부분 결과 '어'로 보내지 않는다", YesNoParser.streamVerdict("어", isFinal = false))
        assertNull(YesNoParser.streamVerdict("네", isFinal = false))
        assertEquals(YesNo.NO, YesNoParser.streamVerdict("어 아니", isFinal = false))
        assertEquals(YesNo.NO, YesNoParser.streamVerdict("어 아니 보내지 마", isFinal = true))
        assertEquals(YesNo.YES, YesNoParser.streamVerdict("어", isFinal = true))
        assertEquals("문장 전체가 네일 때만", YesNo.NONE, YesNoParser.streamVerdict("네 그런데 잠깐만", isFinal = true))
        assertNull(YesNoParser.streamVerdict("", isFinal = false))
        assertEquals(YesNo.NONE, YesNoParser.streamVerdict(null, isFinal = true))
    }

    @Test
    fun `Vosk segments - a YES segment waits a little, a following NO wins`() {
        val s = YesNoSegments(confirmMs = 700)
        assertNull("구간 '어' 가 끝나도 바로 네가 아니다", s.onSegment("어", now = 1_000))
        assertEquals(1_700L, s.confirmAt)
        assertNull(s.onTick(1_500))
        assertEquals("잠깐 멈춘 뒤 '아니' — 아니", YesNo.NO, s.onPartial("아니"))
    }

    @Test
    fun `Vosk segments - YES is confirmed after the wait when nothing else is heard`() {
        val s = YesNoSegments(confirmMs = 700)
        assertNull(s.onPartial("네"))
        assertNull("부분 결과의 네는 대기를 시작하지 않는다", s.confirmAt)
        assertNull(s.onSegment("네", now = 2_000))
        assertNull(s.onPartial(""))
        assertNull(s.onTick(2_699))
        assertEquals(YesNo.YES, s.onTick(2_700))
    }

    @Test
    fun `Vosk segments - other words after a YES segment cancel the wait, the final verdict is the whole sentence`() {
        val s = YesNoSegments(confirmMs = 700)
        assertNull(s.onSegment("어", now = 0))
        assertNull(s.onPartial("그러니까"))
        assertNull("문장이 네가 아니게 되면 대기를 푼다", s.confirmAt)
        assertNull(s.onTick(5_000))
        assertNull(s.onSegment("그러니까 다음에", now = 1_500))
        assertEquals(YesNo.NONE, s.onFinal(""))
        assertEquals(YesNo.YES, YesNoSegments().onFinal("네"))
    }
}
