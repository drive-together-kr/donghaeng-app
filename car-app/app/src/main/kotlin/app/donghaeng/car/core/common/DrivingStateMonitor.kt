package app.donghaeng.car.core.common

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

/** 진단용 주행 제한 상태 */
data class DrivingStatus(
    val source: String = "none",
    /** null = 아직 모름 */
    val requiresDistractionOptimization: Boolean? = null,
    /** CarUxRestrictions.activeRestrictions 비트(16진). Car API 없으면 null */
    val activeRestrictions: Int? = null,
    val updates: Int = 0,
    val lastError: String? = null,
    /** 연결·등록 실패 뒤 다시 시도한 횟수(A044) */
    val retries: Int = 0,
)

/**
 * 주행 중 UX 제한 감시 (docs/05 §4, T29).
 *
 * - pleos: `CarUxDrivingStateMonitor` — `android.car.Car` → `CarUxRestrictionsManager` 의 `isRequiresDistractionOptimization` 을 callbackFlow 로.
 * - fake / Car 가 없는 환경: [AlwaysParkedDrivingStateMonitor] — 항상 false.
 * - 화면은 `isDriving()` 이 true 면 입력(버튼·텍스트·QR 스캔)을 비활성화하고 보기만 허용한다. 제안은 30초 뒤 자동 거절(T28).
 */
interface DrivingStateMonitor {
    /** true = 주행 중(운전 방해 최소화 필요) */
    fun isDriving(): Flow<Boolean>

    val status: StateFlow<DrivingStatus>
}

/** Car API 가 없는 환경의 기본 구현 — 항상 정차 상태. */
class AlwaysParkedDrivingStateMonitor : DrivingStateMonitor {
    override fun isDriving(): Flow<Boolean> = flowOf(false)
    override val status: StateFlow<DrivingStatus> = MutableStateFlow(DrivingStatus(source = "always-parked", requiresDistractionOptimization = false))
}
