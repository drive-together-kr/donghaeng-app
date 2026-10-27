package app.donghaeng.car.adapter.location

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import android.content.Context
import app.donghaeng.car.core.common.DhLog
import app.donghaeng.car.core.common.LocationPermissions
import app.donghaeng.car.domain.model.Fix
import app.donghaeng.protocol.LocSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext

/**
 * 1순위(선택): Pleos FusedLocation 1.1.3 (T21, docs/05 §3.2). Playground 상 "준비 중"이라 **기능적으로 의존하지 않는다**(CLAUDE.md 규칙 5):
 * [isAvailable] 이 `pleos.car.permission.FUSED_LOCATION` 승인 && `isSupported()` 일 때만 체인 맨 앞에 서고, 아니면 Navi → Android → Fake.
 *
 * SDK (javap 확인): `FusedLocation(context).initialize()/release()/isSupported()`,
 * `registerFusedLocationCallback(l)/unregisterFusedLocationCallback(l)`, `getLastKnownLocation(l)`,
 * `FusedLocationListener { onChanged(LocationData(latitude, longitude, altitude, heading, confidence, timestampMillis)); onError(Exception) }`.
 * `confidence` 는 미터 정확도가 아니라서 [Fix.accuracy] 는 null 로 둔다.
 */
class PleosFusedLocationAdapter(context: Context) : LocationSource {

    private val appContext = context.applicationContext
    private val lock = Any()

    @Volatile
    private var fused: FusedLocation? = null

    override val source: LocSource = LocSource.FUSED

    private fun ensure(): FusedLocation? = synchronized(lock) {
        fused ?: try {
            FusedLocation(appContext).also { it.initialize(); fused = it }
        } catch (e: Exception) {
            DhLog.w("FusedLocation 초기화 실패 → 미지원으로 간주: ${e.javaClass.simpleName}")
            null
        }
    }

    /** 서비스 없음·미지원·권한 없음이면 false — 체인은 다음 순위로 넘어간다 */
    fun isSupported(): Boolean = try {
        ensure()?.isSupported().also { DhLog.i("FusedLocation isSupported=$it") } ?: false
    } catch (e: Exception) {
        DhLog.w("FusedLocation isSupported 실패 → false: ${e.javaClass.simpleName}")
        false
    }

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        if (!LocationPermissions.fusedGranted(appContext)) {
            DhLog.i("FusedLocation: FUSED_LOCATION 권한 미승인 → 건너뜀")
            return@withContext false
        }
        isSupported()
    }

    override fun locations(): Flow<Fix> = callbackFlow {
        val f = ensure() ?: run { close(); return@callbackFlow }
        val listener = object : FusedLocationListener {
            override fun onChanged(data: LocationData?) {
                if (data != null) trySend(data.toFix())
            }

            override fun onError(e: Exception) {
                DhLog.w("FusedLocation onError ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        try {
            f.registerFusedLocationCallback(listener)
            DhLog.i("FusedLocation: callback registered")
            runCatching { f.getLastKnownLocation(listener) }
        } catch (e: Exception) {
            DhLog.w("FusedLocation register 실패", e)
            close(e)
            return@callbackFlow
        }
        awaitClose {
            runCatching { f.unregisterFusedLocationCallback(listener) }
            DhLog.i("FusedLocation: callback unregistered")
        }
    }

    fun release() {
        synchronized(lock) {
            runCatching { fused?.release() }.onFailure { DhLog.w("FusedLocation release 실패", it) }
            fused = null
        }
    }

    private fun LocationData.toFix() = Fix(
        lat = latitude,
        lon = longitude,
        heading = heading.takeIf { !it.isNaN() && it >= 0f },
        accuracy = null,
        ts = if (timestampMillis > 0) timestampMillis else System.currentTimeMillis(),
        source = LocSource.FUSED,
    )
}
