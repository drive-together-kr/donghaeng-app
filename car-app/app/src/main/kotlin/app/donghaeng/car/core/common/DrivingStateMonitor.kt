package app.donghaeng.car.core.common

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * 주행 중 UX 제한 감시 (docs/05 §4).
 *
 * TODO(T29): `android.car.Car` → `CarUxRestrictionsManager` 의 `isRequiresDistractionOptimization` 을
 *   callbackFlow 로 감싸 주행 중에는 입력 버튼·스크롤·QR 화면을 비활성화한다.
 *   - Car 가 없는 환경(fake flavor / 일반 AVD)에서는 항상 false.
 *   - 제안(경로 따라가기·휴게소)은 주행 중 30초 후 자동 거절, 정차 시 다시 노출.
 *
 * 지금은 항상 "주행 중 아님" 을 돌려주는 자리표시자다.
 */
interface DrivingStateMonitor {
    /** true = 주행 중(운전 방해 최소화 필요) */
    fun isDriving(): Flow<Boolean>
}

/** T29 전까지 쓰는 기본 구현 — 항상 정차 상태. */
class AlwaysParkedDrivingStateMonitor : DrivingStateMonitor {
    override fun isDriving(): Flow<Boolean> = flowOf(false)
}
