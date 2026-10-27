package app.donghaeng.car.feature.pairing

import app.donghaeng.protocol.rest.ApiPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ZXing 은 순수 JVM 라이브러리라 단위 테스트에서 실제 인코딩을 검증한다. */
class QrCodesTest {

    @Test
    fun `verifyUri 로 만든 QR 은 비어 있지 않은 정사각 행렬`() {
        val uri = ApiPaths.pairUrl("http://10.0.2.2:8080", "KQ74MZ")
        val matrix = QrCodes.encode(uri)
        assertEquals(matrix.width, matrix.height)
        assertTrue("버전1(21모듈)+여백 이상이어야 한다: ${matrix.width}", matrix.width >= 21 + 2)
        val dark = QrCodes.darkModules(matrix)
        assertTrue("어두운 모듈이 있어야 한다", dark > 0)
        assertTrue("전부 검정일 수는 없다", dark < matrix.width * matrix.height)
    }

    @Test
    fun `내용이 다르면 행렬도 다르다`() {
        val a = QrCodes.encode(ApiPaths.pairUrl("http://10.0.2.2:8080", "KQ74MZ"))
        val b = QrCodes.encode(ApiPaths.pairUrl("http://10.0.2.2:8080", "ZZ99AA"))
        assertNotEquals(a, b)
    }

    @Test
    fun `클라우드 주소(https sslip)도 인코딩된다`() {
        val matrix = QrCodes.encode(ApiPaths.pairUrl("https://1-2-3-4.sslip.io", "KQ74MZ"))
        assertTrue(QrCodes.darkModules(matrix) > 0)
    }
}
