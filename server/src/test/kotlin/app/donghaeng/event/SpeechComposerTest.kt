package app.donghaeng.event

import kotlin.test.Test
import kotlin.test.assertEquals

/** docs/06 §4 템플릿 — 조사가 닉네임 받침에 맞게 붙는다 */
class SpeechComposerTest {

    private val speech = SpeechComposer()

    @Test
    fun `합류·이탈 문구`() {
        assertEquals("아빠 차가 그룹에 들어왔어요.", speech.memberJoined("아빠 차"))
        assertEquals("정민이 그룹에 들어왔어요.", speech.memberJoined("정민"))
        assertEquals("아빠 차가 그룹에서 나갔어요.", speech.memberLeft("아빠 차"))
        assertEquals("아빠 차가 그룹에서 나갔어요. 이제 엄마 차량이 선두예요.", speech.memberLeft("아빠 차", "엄마 차량"))
        assertEquals("이제 삼촌 차가 선두예요.", speech.leaderChanged("삼촌 차"))
    }

    @Test
    fun `차량 이벤트 문구 — 조사와 EV 구분`() {
        val c = SpeechComposer()
        assertEquals("아빠 차 연료가 12% 남았어요.", c.lowEnergy("아빠 차", app.donghaeng.protocol.EnergyKind.FUEL, 12))
        assertEquals("엄마 차 배터리가 9% 남았어요.", c.lowEnergy("엄마 차", app.donghaeng.protocol.EnergyKind.EV, 9))
        assertEquals("앞쪽 아빠 차가 급제동했어요. 주의하세요.", c.hardBrake("아빠 차"))
        assertEquals("앞쪽 삼촌이 급제동했어요. 주의하세요.", c.hardBrake("삼촌"))
        assertEquals("아빠 차가 덕평휴게소에 도착했어요.", c.waypointArrived("아빠 차", "덕평휴게소"))
        assertEquals("삼촌이 먼저 도착했어요.", c.destinationArrived("삼촌"))
        assertEquals("아빠 차가 잠시 멈췄어요.", c.stopped("아빠 차"))
        assertEquals("아빠 차가 다시 출발했어요.", c.resumed("아빠 차"))
        assertEquals("아빠 차 에어백이 터졌어요. 확인해 주세요.", c.airbagDeployed("아빠 차"))
        assertEquals("삼촌이 비상등을 켰어요.", c.hazardOn("삼촌"))
    }

    @Test
    fun `동승자 메시지와 종료 문구`() {
        assertEquals("아빠 차에서 온 메시지. 앞에 휴게소 들르자", speech.passengerMessage("아빠 차", "앞에 휴게소 들르자"))
        assertEquals("방장이 그룹을 종료했어요.", speech.groupEnded(SpeechComposer.REASON_LEADER_ENDED))
        assertEquals("그룹이 종료됐어요.", speech.groupEnded(SpeechComposer.REASON_ALL_LEFT))
        assertEquals("그룹이 종료됐어요.", speech.groupEnded("SOMETHING_ELSE"))
    }
}
