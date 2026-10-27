package app.donghaeng.car.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoordinateMaskingTest {

    @Test
    fun `소수 첫째 자리까지만 남기고 가린다`() {
        assertEquals("37.1**", CoordinateMasking.mask(37.123456))
        assertEquals("127.6**", CoordinateMasking.mask(127.654321))
    }

    @Test
    fun `반올림하지 않고 버린다`() {
        assertEquals("36.9**", CoordinateMasking.mask(36.98))
        assertEquals("36.0**", CoordinateMasking.mask(36.0))
        assertEquals("36.0**", CoordinateMasking.mask(36.09999))
    }

    @Test
    fun `음수 좌표도 부호를 유지한다`() {
        assertEquals("-33.8**", CoordinateMasking.mask(-33.8688))
        assertEquals("-0.1**", CoordinateMasking.mask(-0.15))
    }

    @Test
    fun `null NaN 무한대는 물음표`() {
        assertEquals("?", CoordinateMasking.mask(null))
        assertEquals("?", CoordinateMasking.mask(Double.NaN))
        assertEquals("?", CoordinateMasking.mask(Double.POSITIVE_INFINITY))
    }

    @Test
    fun `위경도 쌍 형식`() {
        assertEquals("(37.1**, 127.6**)", CoordinateMasking.mask(37.123456, 127.654321))
    }

    @Test
    fun `마스킹 결과에 원문 좌표가 남지 않는다`() {
        val masked = CoordinateMasking.mask(37.5665, 126.9780)
        assertFalse(masked.contains("37.5665"))
        assertFalse(masked.contains("126.978"))
        assertTrue(masked.contains("**"))
    }

    @Test
    fun `문자열 안의 좌표처럼 보이는 숫자를 가린다`() {
        val raw = "CurrentLocationInfo(latitude=37.566535, longitude=126.977969, speed=42.0)"
        val masked = CoordinateMasking.maskInText(raw)
        assertEquals("CurrentLocationInfo(latitude=37.5**, longitude=126.9**, speed=42.0)", masked)
        assertFalse(masked.contains("566535"))
    }

    @Test
    fun `소수 한 자리 숫자는 좌표로 보지 않는다`() {
        assertEquals("fuel=62 speed=42.5", CoordinateMasking.maskInText("fuel=62 speed=42.5"))
    }

    @Test
    fun `버전 퍼센트 속도처럼 좌표가 아닌 숫자는 바꾸지 않는다`() {
        val raw = "sdk probe: Vehicle 2.3.13 OK — service v2.3.22 fuel=62.5% speed=42.0 ts=1730000000.1234"
        assertEquals(raw, CoordinateMasking.maskInText(raw))
        assertEquals("FusedLocation 1.1.3 OK", CoordinateMasking.maskInText("FusedLocation 1.1.3 OK"))
    }

    @Test
    fun `문자열 안의 음수 좌표도 가린다`() {
        assertEquals("lat=-33.8**, lon=151.2**", CoordinateMasking.maskInText("lat=-33.8688, lon=151.2093"))
    }
}

class TextMaskingTest {

    @Test
    fun `닉네임은 첫 글자만`() {
        assertEquals("아**", TextMasking.maskNickname("아빠차"))
        assertEquals("?", TextMasking.maskNickname(""))
        assertEquals("?", TextMasking.maskNickname(null))
    }

    @Test
    fun `한 글자 닉네임은 전부 가린다`() {
        assertEquals("*", TextMasking.maskNickname("A"))
        assertEquals("*", TextMasking.maskNickname("아"))
        assertFalse(TextMasking.maskNickname("A").contains("A"))
    }

    @Test
    fun `토큰은 앞 4자리와 길이만`() {
        val token = "eyJhbGciOiJIUzI1NiJ9.payload.signature"
        val masked = TextMasking.maskToken(token)
        assertEquals("eyJh…(len=${token.length})", masked)
        assertFalse(masked.contains("payload"))
    }
}
