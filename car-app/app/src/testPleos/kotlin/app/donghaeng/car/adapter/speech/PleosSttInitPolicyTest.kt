package app.donghaeng.car.adapter.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pleos STT initialize 시간 초과(B045·RB-4) — 한 번은 이번만 건너뛰고 다음 녹음 때 다시, 사이클마다 한 번 세어 연달아 3번이면 영구 실패 */
class PleosSttInitPolicyTest {

    @Test
    fun `one or two timeouts in a row are not permanent, three are`() {
        assertFalse("차량 부팅 직후 CaaS 콜드 스타트 한 번으로 굳지 않는다", PleosSttInitPolicy.permanentAfter(1))
        assertFalse(PleosSttInitPolicy.permanentAfter(2))
        assertTrue(PleosSttInitPolicy.permanentAfter(PleosSttInitPolicy.MAX_TIMEOUTS))
        assertTrue(PleosSttInitPolicy.permanentAfter(5))
    }

    @Test
    fun `a prepare timeout makes the listen of the same recording skip a second 3 s init and counts once (RB-4)`() {
        val t = PleosSttCycleTimeouts()
        // 차량 부팅 직후 첫 무전 — 녹음 중 prepare 가 3 s 시간 초과
        t.beginCycle()
        assertTrue(t.mayStartInListen())
        assertFalse(t.onTimeout())
        assertEquals(1, t.consecutiveTimeouts)
        // '보낼까요?' 뒤 listen — 새 초기화(또 3 s)를 시작하지 않고 바로 다음 단계로
        t.beginListen()
        assertFalse("같은 녹음의 listen 은 다시 3 s 를 기다리지 않는다", t.mayStartInListen())
        // 혹시 같은 사이클에서 또 시간 초과가 나도 한 번만 센다
        assertFalse(t.onTimeout())
        assertEquals("사이클마다 한 번", 1, t.consecutiveTimeouts)
        t.endCycle()

        // 다음 녹음 — 다시 시도한다
        t.beginCycle()
        assertTrue(t.mayStartInListen())
        assertFalse(t.onTimeout())
        t.beginListen(); t.endCycle()
        assertEquals(2, t.consecutiveTimeouts)
        t.beginCycle()
        assertTrue("세 번째 녹음에서야 영구 실패(예전: 녹음 1.5회 만에)", t.onTimeout())
    }

    @Test
    fun `a success resets the count and a listen without prepare is its own cycle (RB-4)`() {
        val t = PleosSttCycleTimeouts()
        t.beginCycle(); t.onTimeout(); t.beginListen(); t.endCycle()
        t.beginCycle(); t.onSuccess(); t.beginListen(); t.endCycle()
        assertEquals(0, t.consecutiveTimeouts)
        // prepare 없이 듣는 경로 제안 질문 — 지난 녹음의 시간 초과를 끌고 오지 않는다
        t.beginCycle(); t.onTimeout(); t.beginListen(); t.endCycle()
        t.beginListen()
        assertTrue("prepare 없는 듣기는 새 사이클 — 초기화를 시도한다", t.mayStartInListen())
        assertFalse(t.onTimeout())
        assertEquals(2, t.consecutiveTimeouts)
        t.endCycle()
        // 사이클이 닫힌 뒤 늦게 끝난 prepare 의 시간 초과도 한 번으로 센다 — 세 사이클 연달아면 영구
        assertTrue(t.onTimeout())
        assertEquals(PleosSttInitPolicy.MAX_TIMEOUTS, t.consecutiveTimeouts)
    }
}
