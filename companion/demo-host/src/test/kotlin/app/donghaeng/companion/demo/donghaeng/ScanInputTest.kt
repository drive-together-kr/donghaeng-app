package app.donghaeng.companion.demo.donghaeng

import app.donghaeng.companion.demo.DemoQrKind
import app.donghaeng.companion.demo.QrPathRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** D027·D039 — 차 화면의 코드만 넣어도 등록, D081 — 같은 QR 이 프레임마다 다시 처리되지 않게 */
class ScanInputTest {

    private val base = "https://1-2-3-4.sslip.io/"

    @Test
    fun `차 화면 코드(하이픈·공백·소문자)는 서버 등록 QR 주소가 되고 경로 규칙상 PAIR`() {
        val payload = ManualQrInput.toQrPayload(" kq7-4mz ", base)
        assertEquals("https://1-2-3-4.sslip.io/pair?c=KQ74MZ", payload)
        assertEquals(DemoQrKind.PAIR, QrPathRule.classify(payload!!, base))
        assertEquals("https://1-2-3-4.sslip.io/pair?c=KQ74MZ", ManualQrInput.toQrPayload("KQ7 4MZ", base))
    }

    @Test
    fun `초대 토큰은 그룹 합류 주소, 주소는 그대로`() {
        val join = ManualQrInput.toQrPayload("inv_AbC123xyz", base)
        assertEquals("https://1-2-3-4.sslip.io/j/inv_AbC123xyz", join)
        assertEquals(DemoQrKind.JOIN, QrPathRule.classify(join!!, base))
        assertEquals("http://10.0.2.2:8080/j/inv_x", ManualQrInput.toQrPayload("http://10.0.2.2:8080/j/inv_x", base))
    }

    @Test
    fun `형식이 아니면 null`() {
        assertNull(ManualQrInput.toQrPayload("", base))
        assertNull(ManualQrInput.toQrPayload("hello world", base))
        assertNull(ManualQrInput.toQrPayload("KQ7-4M", base)) // 5자
        assertNull(ManualQrInput.toQrPayload("KQ7-4MZ9", base)) // 7자
    }

    @Test
    fun `같은 QR 은 보이는 동안 다시 처리하지 않고, 화면에서 사라진 뒤 다시 비추면 처리한다`() {
        var now = 0L
        val gate = QrScanGate(quietMs = 2_500, clock = { now })
        assertTrue(gate.accept("A"))
        now += 30
        assertFalse("다음 프레임의 같은 값", gate.accept("A"))
        now += 2_000
        assertFalse("계속 보이면 창이 늘어난다", gate.accept("A"))
        now += 2_400
        assertFalse(gate.accept("A"))
        now += 3_000
        assertTrue("2.5 s 넘게 안 보였다가 다시", gate.accept("A"))
        assertTrue("다른 값은 바로", gate.accept("B"))
    }

    @Test
    fun `시트를 닫은 순간부터 같은 QR 은 조용한 창 동안 무시 - 닫자마자 다시 뜨지 않는다`() {
        var now = 0L
        val gate = QrScanGate(quietMs = 2_500, clock = { now })
        assertTrue(gate.accept("A"))
        now += 60_000 // 시트가 열려 있던 동안은 화면이 콜백을 무시한다
        gate.markHandled("A")
        now += 40
        assertFalse(gate.accept("A"))
    }
}
