package app.donghaeng.car.adapter.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import app.donghaeng.car.core.common.DhLog
import app.donghaeng.car.core.common.LocationPermissions
import app.donghaeng.car.domain.model.Fix
import app.donghaeng.protocol.LocSource
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * 3순위: Android `LocationManager` (AAOS 표준, docs/05 §3.2).
 *
 * - `ACCESS_FINE_LOCATION` 이 승인돼 있고 GPS 또는 NETWORK provider 가 켜져 있을 때만 가용.
 * - 구독 시 30초 이내의 마지막 위치를 먼저 흘리고 `requestLocationUpdates`(1초, 0 m) 를 건다. 해제 시 같은 리스너로 `removeUpdates`.
 * - 정차 중에도 GPS 는 보통 1Hz 로 들어오지만 AVD 는 `adb emu geo fix` 를 보낼 때만 오므로 stale 판정을 15초로 넉넉히 둔다.
 */
class AndroidLocationSource(
    context: Context,
    private val minIntervalMs: Long = 1_000L,
) : LocationSource {
    private val appContext = context.applicationContext
    private val manager: LocationManager? = appContext.getSystemService(LocationManager::class.java)

    override val source: LocSource = LocSource.ANDROID
    override val staleTimeoutMs: Long = 15_000L

    override suspend fun isAvailable(): Boolean {
        if (manager == null) return false
        if (!LocationPermissions.fineGranted(appContext)) {
            DhLog.i("location: Android provider — ACCESS_FINE_LOCATION 미승인")
            return false
        }
        return pickProvider() != null
    }

    private fun pickProvider(): String? = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        .firstOrNull { p -> runCatching { manager?.isProviderEnabled(p) == true }.getOrDefault(false) }

    @SuppressLint("MissingPermission")   // isAvailable() 에서 확인하고, 여기서도 SecurityException 을 잡는다
    override fun locations(): Flow<Fix> = callbackFlow {
        val lm = manager ?: run { close(); return@callbackFlow }
        val provider = pickProvider() ?: run { close(); return@callbackFlow }
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                trySend(location.toFix())
            }

            override fun onProviderDisabled(provider: String) {
                DhLog.i("location: Android provider $provider disabled")
            }

            override fun onProviderEnabled(provider: String) = Unit

            @Deprecated("API 29 미만 호환용")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        try {
            lm.getLastKnownLocation(provider)
                ?.takeIf { System.currentTimeMillis() - it.time < LAST_KNOWN_MAX_AGE_MS }
                ?.let { trySend(it.toFix()) }
            lm.requestLocationUpdates(provider, minIntervalMs, 0f, listener, Looper.getMainLooper())
            DhLog.i("location: Android requestLocationUpdates provider=$provider")
        } catch (e: SecurityException) {
            DhLog.w("location: Android provider 권한 없음", e)
            close(e)
            return@callbackFlow
        }
        awaitClose {
            runCatching { lm.removeUpdates(listener) }
            DhLog.i("location: Android removeUpdates")
        }
    }

    private fun Location.toFix() = Fix(
        lat = latitude,
        lon = longitude,
        heading = if (hasBearing()) bearing else null,
        accuracy = if (hasAccuracy()) accuracy else null,
        ts = if (time > 0) time else System.currentTimeMillis(),
        source = LocSource.ANDROID,
    )

    private companion object {
        const val LAST_KNOWN_MAX_AGE_MS = 30_000L
    }
}
