package app.donghaeng.car.adapter.speech

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pleos STT initialize 시간 초과(B045) — 한 번은 이번만 건너뛰고 다음 녹음 때 다시, 연달아 3번이면 영구 실패 */
class PleosSttInitPolicyTest {

    @Test
    fun `one or two timeouts in a row are not permanent, three are`() {
        assertFalse("차량 부팅 직후 CaaS 콜드 스타트 한 번으로 굳지 않는다", PleosSttInitPolicy.permanentAfter(1))
        assertFalse(PleosSttInitPolicy.permanentAfter(2))
        assertTrue(PleosSttInitPolicy.permanentAfter(PleosSttInitPolicy.MAX_TIMEOUTS))
        assertTrue(PleosSttInitPolicy.permanentAfter(5))
    }
}
