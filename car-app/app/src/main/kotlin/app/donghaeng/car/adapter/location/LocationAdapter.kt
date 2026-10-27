package app.donghaeng.car.adapter.location

import app.donghaeng.car.domain.model.Fix
import kotlinx.coroutines.flow.Flow

/**
 * 위치 어댑터 (docs/05 §3, §3.2 우선순위: FusedLocation → NaviHelper → Android LocationManager → Fake).
 *
 * TODO(T21): 우선순위 체인 `CompositeLocationAdapter` + 연속 위치로 속도 계산(Haversine / Δt, 3샘플 이동평균).
 * 구현: `PleosFusedLocationAdapter`(isSupported() 런타임 확인), `NaviLocationAdapter`(NaviEvent.CurrentLocation),
 *       `AndroidLocationAdapter`, [FakeLocationAdapter].
 */
interface LocationAdapter {
    /** 위치 스트림(목표 1 Hz). 구독 시 리스너 등록, 취소 시 해제. */
    fun locations(): Flow<Fix>
}
