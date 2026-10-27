package app.donghaeng.car.feature.pairing

import org.junit.Assert.assertEquals
import org.junit.Test

class PairingFormatTest {

    @Test
    fun `6자리 userCode 는 3자리 뒤에 하이픈`() {
        assertEquals("KQ7-4MZ", PairingFormat.formatUserCode("KQ74MZ"))
    }

    @Test
    fun `이미 하이픈이 있거나 소문자여도 같은 결과`() {
        assertEquals("KQ7-4MZ", PairingFormat.formatUserCode("KQ7-4MZ"))
        assertEquals("KQ7-4MZ", PairingFormat.formatUserCode(" kq7 4mz "))
    }

    @Test
    fun `6자리가 아니면 영숫자만 대문자로 돌려준다`() {
        assertEquals("KQ74M", PairingFormat.formatUserCode("KQ74M"))
        assertEquals("ABCDEFGH", PairingFormat.formatUserCode("abcd-efgh"))
        assertEquals("", PairingFormat.formatUserCode(""))
    }

    @Test
    fun `남은 시간은 m_ss 이고 음수는 0_00`() {
        assertEquals("9:58", PairingFormat.formatRemaining(598_000L))
        assertEquals("1:01", PairingFormat.formatRemaining(61_000L))
        assertEquals("0:01", PairingFormat.formatRemaining(500L))     // 올림 — 0초 전까지는 1초로 보인다
        assertEquals("0:00", PairingFormat.formatRemaining(0L))
        assertEquals("0:00", PairingFormat.formatRemaining(-5_000L))
        assertEquals("10:00", PairingFormat.formatRemaining(600_000L))
    }
}
