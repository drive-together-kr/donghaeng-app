package app.donghaeng.car.domain.capability

import app.donghaeng.car.BuildConfig
import app.donghaeng.car.adapter.vehicle.VehicleAdapter
import app.donghaeng.car.core.common.DhLog
import app.donghaeng.car.core.common.TextMasking
import app.donghaeng.car.domain.model.VehicleCapabilities
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 앱 시작 시 한 번 실행하는 차량 SDK 실측 결과 (docs/09 §3 체크리스트, T11).
 *
 * @param vehicleIdMasked CarInfo 차량 ID 마스킹(앞 4 + 뒤 2). null = 읽지 못함
 * @param vehicleIdLength 원문 길이(VIN 17 / Fake 12 구분용). 0 = 없음
 */
data class CapabilityReport(
    val vehicleIdMasked: String?,
    val vehicleIdLength: Int,
    val capabilities: VehicleCapabilities,
    val adapter: String = BuildConfig.ADAPTER,
)

/**
 * [VehicleAdapter.vehicleId] 와 [VehicleAdapter.capabilities] 를 **한 번만** 호출해 결과를 보관하고,
 * 모든 항목을 `DH_CAP` 태그로 로그에 남긴다 (`adb logcat -s DH_CAP`). 진단 화면이 [report] 를 표로 보여 준다.
 *
 * Application.onCreate 에서 [runOnce] 를 부른다. SDK 바인딩·콜백 대기가 있으므로 메인 스레드를 막지 않는다.
 */
@Singleton
class CapabilityReporter @Inject constructor(
    private val vehicleAdapter: VehicleAdapter,
) {
    private val started = AtomicBoolean(false)
    private val _report = MutableStateFlow<CapabilityReport?>(null)

    /** null = 아직 점검 전(진행 중) */
    val report: StateFlow<CapabilityReport?> = _report.asStateFlow()

    /** 두 번째 호출부터는 무시한다. */
    fun runOnce(dispatcher: CoroutineDispatcher = Dispatchers.Default) {
        if (!started.compareAndSet(false, true)) return
        CoroutineScope(SupervisorJob() + dispatcher).launch { run() }
    }

    /** 직접 호출용(테스트). 어댑터가 던져도 앱은 죽지 않는다(규칙 4). */
    suspend fun run(): CapabilityReport {
        val vehicleId = runCatching { vehicleAdapter.vehicleId() }
            .onFailure { DhLog.w("vehicleId() 예외", it) }
            .getOrNull()
        DhLog.i(
            if (vehicleId == null) "vehicle id: null adapter=${BuildConfig.ADAPTER}"
            else "vehicle id: len=${vehicleId.length} masked=${TextMasking.maskVehicleId(vehicleId)} adapter=${BuildConfig.ADAPTER}",
        )
        val caps = runCatching { vehicleAdapter.capabilities() }
            .onFailure { DhLog.w("capabilities() 예외", it) }
            .getOrElse { VehicleCapabilities.allUnknown("capabilities() 예외: ${it.javaClass.simpleName}") }
        DhLog.cap("adapter=${BuildConfig.ADAPTER} entries=${caps.entries.size} raw=${caps.raw}")
        caps.entries.forEach { DhLog.cap(it.format()) }
        val report = CapabilityReport(
            vehicleIdMasked = vehicleId?.let { TextMasking.maskVehicleId(it) },
            vehicleIdLength = vehicleId?.length ?: 0,
            capabilities = caps,
        )
        _report.value = report
        return report
    }
}
