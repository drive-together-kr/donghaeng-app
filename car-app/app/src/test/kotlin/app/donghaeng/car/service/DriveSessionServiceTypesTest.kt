package app.donghaeng.car.service

import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 포그라운드 서비스 타입 후보 — F16 무전: 마이크 권한이 있으면 microphone 을 더하고, 막히면 줄여 가며 다시 시도 */
class DriveSessionServiceTypesTest {

    private val loc = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
    private val sync = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
    private val mic = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE

    @Test
    fun `위치·마이크 둘 다 — location+microphone → location → dataSync`() {
        assertEquals(listOf(loc or mic, loc, sync), DriveSessionService.typeCandidates(fineGranted = true, micGranted = true, sdk = 34))
        assertEquals("location|microphone", DriveSessionService.typeName(loc or mic))
    }

    @Test
    fun `위치 없음·마이크 있음 — dataSync+microphone → dataSync`() {
        assertEquals(listOf(sync or mic, sync), DriveSessionService.typeCandidates(fineGranted = false, micGranted = true, sdk = 34))
    }

    @Test
    fun `붙은 타입이 권한보다 모자라면 다시 시작 — 백그라운드에서 시작돼 microphone·location 이 빠진 경우`() {
        // 백그라운드 시작(폰 합류·START_STICKY)으로 dataSync 만 붙었다 → 화면이 앞에 오면 올린다
        assertTrue(DriveSessionService.needsUpgrade(sync, fineGranted = true, micGranted = true, sdk = 34))
        assertTrue(DriveSessionService.needsUpgrade(loc, fineGranted = true, micGranted = true, sdk = 34))
        assertFalse(DriveSessionService.needsUpgrade(loc or mic, fineGranted = true, micGranted = true, sdk = 34))
        assertFalse("권한이 없으면 지금 타입으로 충분", DriveSessionService.needsUpgrade(loc, fineGranted = true, micGranted = false, sdk = 34))
        assertTrue("포그라운드가 못 된 서비스", DriveSessionService.needsUpgrade(null, fineGranted = false, micGranted = false, sdk = 34))
        assertFalse("API 29 미만은 타입이 없다", DriveSessionService.needsUpgrade(DriveSessionService.LEGACY_TYPE, fineGranted = true, micGranted = true, sdk = 28))
        assertFalse("API 29 는 microphone 타입 없음 — location 이면 충분", DriveSessionService.needsUpgrade(loc, fineGranted = true, micGranted = true, sdk = 29))
    }

    @Test
    fun `마이크 없음 또는 API 29 — 기존(T23)과 같다`() {
        assertEquals(listOf(loc, sync), DriveSessionService.typeCandidates(fineGranted = true, micGranted = false, sdk = 34))
        assertEquals(listOf(sync), DriveSessionService.typeCandidates(fineGranted = false, micGranted = false, sdk = 34))
        assertEquals(listOf(loc, sync), DriveSessionService.typeCandidates(fineGranted = true, micGranted = true, sdk = 29))
    }
}
