package app.donghaeng.car.service

import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
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
    fun `마이크 없음 또는 API 29 — 기존(T23)과 같다`() {
        assertEquals(listOf(loc, sync), DriveSessionService.typeCandidates(fineGranted = true, micGranted = false, sdk = 34))
        assertEquals(listOf(sync), DriveSessionService.typeCandidates(fineGranted = false, micGranted = false, sdk = 34))
        assertEquals(listOf(loc, sync), DriveSessionService.typeCandidates(fineGranted = true, micGranted = true, sdk = 29))
    }
}
