package app.donghaeng.car.adapter.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Pleos AVD 실측: CarInfo VIN 이 따옴표 포함 `"invalid"`(9자) 로 온다 → 없는 것으로 본다. */
class VehicleIdNormalizerTest {

    @Test
    fun `따옴표와 공백을 벗긴다`() {
        assertEquals("KMHXX00XXXX000001", VehicleIdNormalizer.normalize("\"KMHXX00XXXX000001\""))
        assertEquals("KMHXX00XXXX000001", VehicleIdNormalizer.normalize("  KMHXX00XXXX000001 \n"))
    }

    @Test
    fun `자리표시자는 null`() {
        assertNull(VehicleIdNormalizer.normalize("\"invalid\""))
        assertNull(VehicleIdNormalizer.normalize("invalid"))
        assertNull(VehicleIdNormalizer.normalize("UNKNOWN"))
        assertNull(VehicleIdNormalizer.normalize("null"))
        assertNull(VehicleIdNormalizer.normalize(""))
        assertNull(VehicleIdNormalizer.normalize("\"\""))
        assertNull(VehicleIdNormalizer.normalize(null))
    }

    @Test
    fun `shape 는 길이와 종류만 말하고 원문을 담지 않는다`() {
        assertEquals("none", VehicleIdNormalizer.shape(null))
        assertEquals("placeholder(len=9)", VehicleIdNormalizer.shape("\"invalid\""))
        val shape = VehicleIdNormalizer.shape("\"KMHXX00XXXX000001\"")
        assertEquals("ok(len=17)", shape)
        assertFalse(shape.contains("KMH"))
    }
}
