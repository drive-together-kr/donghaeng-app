package app.donghaeng.car.adapter.vehicle

import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import app.donghaeng.car.core.common.DhLog
import app.donghaeng.car.domain.model.Gear
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.sample
import kotlin.math.abs

/** Car API 점검 결과(진단 표용) */
data class CarApiProbe(val supported: Boolean?, val detail: String)

/**
 * AAOS 표준 Car API(`android.car`)로 속도·기어를 읽는다 (T22, docs/05 §3.2 "속도: Car API → 실패 시 위치 계산").
 *
 * - `PERF_VEHICLE_SPEED`(m/s → km/h) 는 dangerous 권한 `android.car.permission.CAR_SPEED` 가 런타임 승인돼야 한다(정차 시 1회 요청, 거부 시 Haversine 속도).
 * - `GEAR_SELECTION` 은 normal 권한 `CAR_POWERTRAIN`. Pleos SDK `Gear` 가 실패할 때의 폴백.
 * - `android.car` 는 공유 라이브러리(`uses-library required=false`) — 클래스가 없거나(일반 기기) Car 서비스가 없으면 전부 "미지원" 으로 끝난다. 절대 던지지 않는다.
 */
class CarApiSignals(context: Context) {

    private val appContext = context.applicationContext

    private companion object {
        const val PROBE_ATTEMPTS = 3
        const val PROBE_RETRY_DELAY_MS = 1_500L
        const val SPEED_SAMPLE_MS = 1_000L
    }

    /** 자동차 기기이고 android.car 클래스가 로드되는가 */
    fun isAvailable(): Boolean =
        appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE) &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            runCatching { Class.forName("android.car.Car") }.isSuccess

    fun speedPermissionGranted(): Boolean =
        appContext.checkSelfPermission(Car.PERMISSION_SPEED) == PackageManager.PERMISSION_GRANTED

    suspend fun probeSpeed(): CarApiProbe = probe(VehiclePropertyIds.PERF_VEHICLE_SPEED, "PERF_VEHICLE_SPEED", needsPermission = Car.PERMISSION_SPEED)

    suspend fun probeGear(): CarApiProbe = probe(VehiclePropertyIds.GEAR_SELECTION, "GEAR_SELECTION", needsPermission = Car.PERMISSION_POWERTRAIN)

    /**
     * 속성 존재 여부. 앱 시작 직후에는 Car 서비스가 아직 속성 목록을 안 줄 때가 있어(AVD 실측: 첫 조회 null, 몇 초 뒤 정상) 최대 [PROBE_ATTEMPTS]번 재시도한다.
     */
    private suspend fun probe(propertyId: Int, name: String, needsPermission: String): CarApiProbe {
        if (!isAvailable()) return CarApiProbe(false, "Car API 없음(자동차 기기 아님 또는 android.car 미탑재)")
        val granted = appContext.checkSelfPermission(needsPermission) == PackageManager.PERMISSION_GRANTED
        var last: CarApiProbe? = null
        repeat(PROBE_ATTEMPTS) { attempt ->
            val result = withCar("probe $name") { pm ->
                val config = pm.getCarPropertyConfig(propertyId)
                when {
                    config == null -> CarApiProbe(false, "$name 속성 없음")
                    !granted -> CarApiProbe(null, "$name 있음, ${needsPermission.substringAfterLast('.')} 미승인(정차 시 요청)")
                    else -> CarApiProbe(true, "$name 사용 가능(${needsPermission.substringAfterLast('.')} 승인)")
                }
            } ?: CarApiProbe(null, "Car 서비스 연결 실패")
            if (result.supported != false || attempt == PROBE_ATTEMPTS - 1) return result.let { if (attempt > 0) it.copy(detail = "${it.detail} (${attempt + 1}번째 시도)") else it }
            last = result
            delay(PROBE_RETRY_DELAY_MS)
        }
        return last ?: CarApiProbe(null, "probe 실패")
    }

    /**
     * 차량 속도 km/h. 권한 없음·속성 없음이면 아무것도 내지 않고 끝난다.
     * AVD 의 VHAL 은 SENSOR_RATE_NORMAL 을 요청해도 약 7Hz 로 밀어 넣는다(실측 56초에 410건) → 1초 샘플링으로 줄인다(텔레메트리도 1Hz, 진단 화면 recomposition 도 1Hz).
     */
    @OptIn(FlowPreview::class)
    fun speedKph(): Flow<Float?> = propertyFlow(VehiclePropertyIds.PERF_VEHICLE_SPEED, "PERF_VEHICLE_SPEED") { value ->
        (value as? Float)?.let { abs(it) * 3.6f }
    }.sample(SPEED_SAMPLE_MS)

    /** 기어(GEAR_SELECTION). Pleos SDK Gear 실패 시 폴백 */
    fun gear(): Flow<Gear?> = propertyFlow(VehiclePropertyIds.GEAR_SELECTION, "GEAR_SELECTION") { value ->
        when (value as? Int) {
            null -> null
            VehicleGear.GEAR_PARK -> Gear.P
            VehicleGear.GEAR_REVERSE -> Gear.R
            VehicleGear.GEAR_NEUTRAL -> Gear.N
            VehicleGear.GEAR_DRIVE, VehicleGear.GEAR_FIRST, VehicleGear.GEAR_SECOND, VehicleGear.GEAR_THIRD, VehicleGear.GEAR_FOURTH,
            VehicleGear.GEAR_FIFTH, VehicleGear.GEAR_SIXTH, VehicleGear.GEAR_SEVENTH, VehicleGear.GEAR_EIGHTH, VehicleGear.GEAR_NINTH -> Gear.D
            else -> Gear.UNKNOWN
        }
    }

    private fun <T> propertyFlow(propertyId: Int, name: String, map: (Any?) -> T?): Flow<T?> = callbackFlow {
        if (!isAvailable()) {
            DhLog.i("car api: $name — Car API 없음")
            close()
            return@callbackFlow
        }
        val car = try {
            Car.createCar(appContext)
        } catch (e: Exception) {
            DhLog.w("car api: createCar 실패 ${e.javaClass.simpleName}")
            null
        }
        val pm = car?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
        if (car == null || pm == null) {
            DhLog.w("car api: $name — Car 서비스 연결 실패")
            runCatching { car?.disconnect() }
            close()
            return@callbackFlow
        }
        val callback = object : CarPropertyManager.CarPropertyEventCallback {
            override fun onChangeEvent(value: CarPropertyValue<*>) {
                if (value.propertyId == propertyId) trySend(map(value.value))
            }

            override fun onErrorEvent(propId: Int, zone: Int) {
                DhLog.w("car api: $name onErrorEvent prop=$propId zone=$zone")
            }
        }
        try {
            if (pm.getCarPropertyConfig(propertyId) == null) {
                DhLog.i("car api: $name 속성 없음")
                car.disconnect()
                close()
                return@callbackFlow
            }
            pm.registerCallback(callback, propertyId, CarPropertyManager.SENSOR_RATE_NORMAL)
            DhLog.i("car api: $name registered")
        } catch (e: SecurityException) {
            DhLog.w("car api: $name 권한 없음 — ${e.message?.take(80)}")
            runCatching { car.disconnect() }
            close()
            return@callbackFlow
        } catch (e: Exception) {
            DhLog.w("car api: $name 등록 실패 ${e.javaClass.simpleName}")
            runCatching { car.disconnect() }
            close(e)
            return@callbackFlow
        }
        awaitClose {
            runCatching { pm.unregisterCallback(callback, propertyId) }
            runCatching { car.disconnect() }
            DhLog.i("car api: $name unregistered")
        }
    }

    private fun <R> withCar(what: String, block: (CarPropertyManager) -> R): R? {
        var car: Car? = null
        return try {
            car = Car.createCar(appContext)
            val pm = car?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager ?: return null
            block(pm)
        } catch (e: Exception) {
            DhLog.w("car api: $what 실패 ${e.javaClass.simpleName}: ${e.message?.take(80)}")
            null
        } finally {
            runCatching { car?.disconnect() }
        }
    }
}
