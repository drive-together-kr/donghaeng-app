package app.donghaeng.car.core.common

import android.Manifest
import app.donghaeng.car.feature.permissions.LocationPermissionViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A-RT2: FusedLocation 권한(Pleos AVD 실측 dangerous)도 같은 다이얼로그로 요청. A051: 새로 승인된 권한이 있을 때만 다시 붙인다 */
class LocationPermissionsTest {

    @Test
    fun `fused location is requested at runtime only when the app declares it and the device defines it as dangerous`() {
        val pleos = LocationPermissions.runtimePermissionList(sdkInt = 34, automotive = true, fusedRequestable = true)
        assertTrue(LocationPermissions.FUSED_LOCATION in pleos)
        assertTrue(LocationPermissions.CAR_SPEED in pleos)
        assertTrue(Manifest.permission.POST_NOTIFICATIONS in pleos)
        assertTrue(Manifest.permission.RECORD_AUDIO in pleos)
        val fake = LocationPermissions.runtimePermissionList(sdkInt = 34, automotive = false, fusedRequestable = false)
        assertFalse("fake flavor(선언 없음)는 요청하지 않는다", LocationPermissions.FUSED_LOCATION in fake)
        assertFalse(LocationPermissions.CAR_SPEED in fake)
        assertFalse("API 33 미만엔 알림 권한 없음", Manifest.permission.POST_NOTIFICATIONS in LocationPermissions.runtimePermissionList(28, false, false))
    }

    @Test
    fun `only newly granted permissions restart the location chain (permanent denials do not)`() {
        val missing = setOf(Manifest.permission.ACCESS_FINE_LOCATION, LocationPermissions.FUSED_LOCATION)
        assertEquals(
            setOf(LocationPermissions.FUSED_LOCATION),
            LocationPermissionViewModel.newlyGranted(missing, mapOf(LocationPermissions.FUSED_LOCATION to true, Manifest.permission.ACCESS_FINE_LOCATION to false, Manifest.permission.RECORD_AUDIO to true)),
        )
        assertTrue("영구 거부 — 다이얼로그 없이 전부 false", LocationPermissionViewModel.newlyGranted(missing, missing.associateWith { false }).isEmpty())
    }
}
