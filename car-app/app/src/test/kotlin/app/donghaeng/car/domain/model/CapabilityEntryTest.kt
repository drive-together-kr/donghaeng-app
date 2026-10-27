package app.donghaeng.car.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** DH_CAP 로그 한 줄 형식과 unknown/false 구분 (T11). */
class CapabilityEntryTest {

    @Test
    fun `format 은 도메인 지원 상세 출처 순서`() {
        val entry = CapabilityEntry(CapabilityDomain.FUEL, supported = true, detail = "accessType=READ", source = CapabilitySource.PLEOS_SDK)
        assertEquals("Fuel supported=true detail=accessType=READ source=PLEOS_SDK", entry.format())
    }

    @Test
    fun `supported null 은 unknown 으로 찍힌다`() {
        val entry = CapabilityEntry(CapabilityDomain.SPEED, supported = null, detail = "T22 확인 예정", source = CapabilitySource.UNCHECKED)
        assertEquals("unknown", entry.supportedText)
        assertEquals("Speed supported=unknown detail=T22 확인 예정 source=UNCHECKED", entry.format())
        assertEquals("false", CapabilityEntry(CapabilityDomain.HAZARD, false, "-", CapabilitySource.FAKE).supportedText)
    }

    @Test
    fun `VehicleCapabilities 는 명시적 true 만 지원으로 본다`() {
        val caps = VehicleCapabilities(
            entries = listOf(
                CapabilityEntry(CapabilityDomain.FUEL, true, "accessType=READ", CapabilitySource.PLEOS_SDK),
                CapabilityEntry(CapabilityDomain.EV_BATTERY, null, "timeout", CapabilitySource.PLEOS_SDK),
                CapabilityEntry(CapabilityDomain.BRAKE, false, "accessType=NONE", CapabilitySource.PLEOS_SDK),
            ),
        )
        assertTrue(caps.fuelPercent)
        assertFalse(caps.evStateOfCharge)   // unknown → 숨김
        assertFalse(caps.brakePedal)
        assertFalse(caps.speed)             // 항목 자체가 없음
        assertNull(caps.entry(CapabilityDomain.SPEED))
        assertEquals(CapabilityDomain.EV_BATTERY, caps.entry(CapabilityDomain.EV_BATTERY)?.domain)
    }

    @Test
    fun `allUnknown 은 모든 도메인을 같은 사유의 unknown 으로 채운다`() {
        val caps = VehicleCapabilities.allUnknown("SDK initialize 실패", CapabilitySource.PLEOS_SDK, raw = mapOf("adapter" to "pleos"))
        assertEquals(CapabilityDomain.entries.size, caps.entries.size)
        assertTrue(caps.entries.all { it.supported == null && it.detail == "SDK initialize 실패" && it.source == CapabilitySource.PLEOS_SDK })
        assertFalse(caps.vehicleId)
        assertEquals("pleos", caps.raw["adapter"])
    }
}
