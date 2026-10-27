package app.donghaeng.car.adapter.location

import app.donghaeng.car.domain.model.Fix
import app.donghaeng.car.domain.model.NaviEvent
import app.donghaeng.car.domain.navi.NaviEventMonitor
import app.donghaeng.protocol.LocSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map

/**
 * 2순위: NaviHelper `onCurrentLocationInfo` (docs/05 §3.2). 앱 전체의 유일한 내비 구독자인 [NaviEventMonitor] 의 공유 스트림을 쓴다
 * — 어댑터에 리스너를 또 등록하지 않는다. 내비가 위치를 안 주면(AVD 등) 첫 Fix 타임아웃으로 다음 순위로 넘어간다.
 */
class NaviLocationSource(private val monitor: NaviEventMonitor) : LocationSource {
    override val source: LocSource = LocSource.NAVI

    override suspend fun isAvailable(): Boolean = true

    override fun locations(): Flow<Fix> = monitor.events.filterIsInstance<NaviEvent.CurrentLocation>().map { it.fix }
}
