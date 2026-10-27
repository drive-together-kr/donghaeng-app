package app.donghaeng.car.adapter.uxr

import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import app.donghaeng.car.core.common.DhLog
import app.donghaeng.car.core.common.DrivingStateMonitor
import app.donghaeng.car.core.common.DrivingStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.update

/**
 * AAOS `CarUxRestrictionsManager` 기반 주행 제한 감시 (T29, docs/05 §4, docs/10 R11).
 *
 * - `isRequiresDistractionOptimization` 이 true 면 주행 중으로 본다(속도 > 0 에서 시스템이 세운다 — Pleos AVD 실측: Virtual Console 속도 55 → true).
 * - 구독자가 여럿이어도 Car 연결·리스너는 하나([shareIn]); 마지막 구독자가 떠나면 5초 뒤 해제. 재구독 시 즉시 현재값(replay 1).
 * - Car API 가 없거나(자동차 아님) 연결 실패면 false 한 번 내고 끝낸다 — 크래시 없음.
 * - 앱 자체가 주행 중에 보이려면 매니페스트 `distractionOptimized=true` 메타데이터가 필요하다(없으면 AAOS 가 "주행 중에는 사용할 수 없습니다" 로 가린다).
 */
class CarUxDrivingStateMonitor(context: Context) : DrivingStateMonitor {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _status = MutableStateFlow(DrivingStatus(source = "CarUxRestrictionsManager"))
    override val status: StateFlow<DrivingStatus> = _status

    private val shared: Flow<Boolean> = callbackFlow {
        if (!available()) {
            _status.update { it.copy(requiresDistractionOptimization = false, lastError = "Car API 없음 → 항상 정차") }
            trySend(false)
            close()
            return@callbackFlow
        }
        val car = try {
            Car.createCar(appContext)
        } catch (e: Exception) {
            null
        }
        val manager = car?.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
        if (car == null || manager == null) {
            DhLog.w("uxr: CarUxRestrictionsManager 연결 실패 → 항상 정차")
            _status.update { it.copy(requiresDistractionOptimization = false, lastError = "CarUxRestrictionsManager 연결 실패") }
            runCatching { car?.disconnect() }
            trySend(false)
            close()
            return@callbackFlow
        }
        fun apply(r: CarUxRestrictions?) {
            val driving = r?.isRequiresDistractionOptimization == true
            _status.update { it.copy(requiresDistractionOptimization = driving, activeRestrictions = r?.activeRestrictions, updates = it.updates + 1, lastError = null) }
            DhLog.i("uxr: requiresDO=$driving restrictions=0x${Integer.toHexString(r?.activeRestrictions ?: 0)}")
            trySend(driving)
        }
        try {
            apply(manager.currentCarUxRestrictions)
            manager.registerListener { r -> apply(r) }
            DhLog.i("uxr: listener registered")
        } catch (e: Exception) {
            DhLog.w("uxr: 등록 실패 ${e.javaClass.simpleName}: ${e.message?.take(80)} → 항상 정차")
            _status.update { it.copy(requiresDistractionOptimization = false, lastError = "${e.javaClass.simpleName}") }
            runCatching { car.disconnect() }
            trySend(false)
            close()
            return@callbackFlow
        }
        awaitClose {
            runCatching { manager.unregisterListener() }
            runCatching { car.disconnect() }
            DhLog.i("uxr: listener unregistered")
        }
    }.distinctUntilChanged().shareIn(scope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    override fun isDriving(): Flow<Boolean> = shared

    private fun available(): Boolean =
        appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE) &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            runCatching { Class.forName("android.car.Car") }.isSuccess
}
