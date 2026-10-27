package app.donghaeng.car.adapter.location

import app.donghaeng.car.domain.model.Fix
import kotlinx.coroutines.flow.Flow

/**
 * 위치 어댑터 (docs/05 §3, §3.2 우선순위: FusedLocation → NaviHelper → Android LocationManager → Fake).
 *
 * 구현(T21): [CompositeLocationAdapter] 가 [LocationSource] 들을 순서대로 시도한다 —
 * `PleosFusedLocationAdapter`(pleos, FUSED_LOCATION 승인 + isSupported 일 때만) → [NaviLocationSource](NaviEvent.CurrentLocation)
 * → [AndroidLocationSource](LocationManager, FINE 권한 필요) → [FakeLocationAdapter](항상). 속도는 `domain.location.SpeedEstimator`.
 */
interface LocationAdapter {
    /** 위치 스트림(목표 1 Hz). 구독 시 리스너 등록, 취소 시 해제. */
    fun locations(): Flow<Fix>
}
