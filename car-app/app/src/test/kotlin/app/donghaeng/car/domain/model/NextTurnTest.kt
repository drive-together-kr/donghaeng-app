package app.donghaeng.car.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** TBT 첫 항목 → 다음 회전 결과 (docs/05 §5.2·docs/10 R16) — 자리표시 항목(TURN_UNKNOWN·0 m 에 문구 없음)은 실제 회전이 아니라 모름 */
class NextTurnTest {

    @Test
    fun `a real TBT item becomes Ahead with the distance rounded to metres`() {
        assertEquals(NextTurnResult.Ahead(NextTurn(80, "TURN_LEFT", "기업지원허브 방면")), nextTurnOf("TURN_LEFT", 80.4, "기업지원허브 방면"))
        assertEquals("문구가 없어도 거리가 있으면 실제 회전", NextTurnResult.Ahead(NextTurn(250, "TURN_RIGHT", "")), nextTurnOf("TURN_RIGHT", 250.0, ""))
        assertEquals("지금 회전 중(0 m)이어도 문구가 있으면 실제 회전", NextTurnResult.Ahead(NextTurn(0, "TURN_RIGHT", "창해로")), nextTurnOf("TURN_RIGHT", 0.0, "창해로"))
        assertEquals(
            "종류 코드만 모르는 실제 안내 항목(거리·문구 있음)은 회전으로 본다",
            NextTurnResult.Ahead(NextTurn(150, NextTurn.KIND_UNKNOWN, "강릉항 방면")),
            nextTurnOf(NextTurn.KIND_UNKNOWN, 150.0, "강릉항 방면"),
        )
    }

    @Test
    fun `placeholder items and impossible distances are unknown, not a 0 m turn`() {
        assertEquals("리뷰 재현: [TBTInfo(TURN_UNKNOWN, 0.0, \"\")]", NextTurnResult.Unknown, nextTurnOf(NextTurn.KIND_UNKNOWN, 0.0, ""))
        assertEquals(NextTurnResult.Unknown, nextTurnOf(NextTurn.KIND_UNKNOWN, 0.0, "경로 탐색 중"))
        assertEquals(NextTurnResult.Unknown, nextTurnOf(NextTurn.KIND_UNKNOWN, 320.0, " "))
        assertEquals("종류와 상관없이 0 m·문구 없음", NextTurnResult.Unknown, nextTurnOf("TURN_STRAIGHT", 0.2, ""))
        assertEquals(NextTurnResult.Unknown, nextTurnOf("TURN_LEFT", Double.NaN, "방면"))
        assertEquals(NextTurnResult.Unknown, nextTurnOf("TURN_LEFT", -1.0, "방면"))
    }
}
