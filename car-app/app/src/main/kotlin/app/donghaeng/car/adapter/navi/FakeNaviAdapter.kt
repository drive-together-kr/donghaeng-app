package app.donghaeng.car.adapter.navi

import app.donghaeng.car.core.common.DhLog
import app.donghaeng.car.domain.model.Fix
import app.donghaeng.car.domain.model.NaviEvent
import app.donghaeng.car.domain.model.Place
import app.donghaeng.car.domain.model.RouteState
import app.donghaeng.car.domain.model.WaypointIndex
import app.donghaeng.car.domain.model.toLogString
import app.donghaeng.protocol.LocSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Fake 내비 어댑터 — 고정 시나리오로 [NaviEvent] 몇 개를 흘린다.
 *
 * - 구독 직후: NavigationStatus(안내 중 아님) → 1 Hz CurrentLocation(기준점 주변을 조금씩 이동) → 10초마다 TripInfo/DrivingInfo
 * - requestRoute / requestRouteWithWaypoints / addWaypoint 호출 시 해당 이벤트(RouteStateInfo, DestinationChanged, WaypointChanged)를 즉시 발행
 *
 * TODO(T20/T30): GPX 파일 재생(선두/후속 2개)으로 교체. 좌표는 데모용 임의 지점이며 실제 주소와 무관하다.
 *
 * @param tickMillis 위치 이벤트 간격(기본 1 Hz)
 */
class FakeNaviAdapter(
    private val tickMillis: Long = 1_000L,
    private val startLat: Double = DEMO_START_LAT,
    private val startLon: Double = DEMO_START_LON,
) : NaviAdapter {

    private val commandEvents = MutableSharedFlow<NaviEvent>(extraBufferCapacity = 16)
    private val lock = Mutex()
    private var destination: Place? = null
    private var waypoints: List<Place> = emptyList()

    override fun events(): Flow<NaviEvent> = merge(scenario(), commandEvents)

    private fun scenario(): Flow<NaviEvent> = flow {
        emit(NaviEvent.NavigationStatus(status = "IDLE", isGuiding = false))
        var tick = 0
        while (true) {
            // 북동쪽으로 틱당 약 1.1 m 씩 이동하는 척한다
            val fix = Fix(
                lat = startLat + tick * 0.00001,
                lon = startLon + tick * 0.00001,
                heading = 45f,
                accuracy = 5f,
                ts = System.currentTimeMillis(),
                source = LocSource.NAVI,
            )
            emit(NaviEvent.CurrentLocation(fix))
            if (tick % 10 == 0 && destination != null) {
                val remainM = (12_000 - tick * 15).coerceAtLeast(0)
                emit(NaviEvent.TripInfo(remainDistanceM = remainM, remainTimeS = remainM / 17, destinationArrivalTs = null))
                emit(NaviEvent.DrivingInfo(speedKph = 60f, roadName = null))
            }
            tick++
            delay(tickMillis)
        }
    }

    override suspend fun requestRoute(dest: Place) {
        lock.withLock {
            destination = dest
            waypoints = emptyList()
        }
        DhLog.i("FakeNavi requestRoute ${dest.toLogString()}")
        commandEvents.emit(NaviEvent.DestinationChanged(dest))
        commandEvents.emit(NaviEvent.RouteStateInfo(RouteState.STARTED))
    }

    override suspend fun requestRouteWithWaypoints(dest: Place, waypoints: List<Place>) {
        lock.withLock {
            destination = dest
            this.waypoints = waypoints
        }
        DhLog.i("FakeNavi requestRouteWithWaypoints ${dest.toLogString()} waypoints=${waypoints.size}")
        commandEvents.emit(NaviEvent.DestinationChanged(dest))
        commandEvents.emit(NaviEvent.WaypointChanged(waypoints))
        commandEvents.emit(NaviEvent.RouteStateInfo(RouteState.STARTED))
    }

    override suspend fun addWaypoint(place: Place, index: WaypointIndex) {
        val updated = lock.withLock {
            waypoints = when (index) {
                WaypointIndex.FIRST -> listOf(place) + waypoints
                WaypointIndex.LAST -> waypoints + place
            }
            waypoints
        }
        DhLog.i("FakeNavi addWaypoint ${place.toLogString()} index=$index total=${updated.size}")
        commandEvents.emit(NaviEvent.WaypointChanged(updated))
    }

    override suspend fun currentDestination(): Place? = lock.withLock { destination }

    override suspend fun showNavigation() {
        DhLog.i("FakeNavi showNavigation (내비 화면 없음 — 로그만)")
    }

    companion object {
        /** 데모 시작점(임의 지점) — 실제 장소·주소와 무관 */
        const val DEMO_START_LAT = 36.3500
        const val DEMO_START_LON = 127.3800
    }
}
