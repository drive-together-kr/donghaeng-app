package app.donghaeng.car.adapter.navi

import android.content.Context
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
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.roundToInt

/**
 * Fake 내비 어댑터 — GPX 경로를 일정 속도로 재생한다 (T20/T30).
 *
 * - 구독 직후 `NavigationStatus(FREE_DRIVE)` → 1 Hz `CurrentLocation`(경로를 [speedMps] 로 따라감, heading 은 다음 점 방향)
 * - 목적지가 있으면 10틱마다 `TripInfo`(경로 잔여 거리·시간)·`DrivingInfo`, 경유지 [ARRIVE_RADIUS_M] 안에 들어오면 `WaypointArrived`,
 *   경로 끝에 닿으면 `DestinationArrived` + `RouteStateInfo(ENDED)` 를 한 번 보내고 마지막 점에 머문다.
 * - `requestRoute` / `requestRouteWithWaypoints` / `addWaypoint` 는 실제 내비처럼 해당 이벤트(DestinationChanged, WaypointChanged, RouteStateInfo)를 즉시 발행한다.
 * - 경로가 없으면(점 2개 미만) 예전처럼 기준점 주변을 조금씩 이동하는 드리프트 시나리오로 동작한다.
 * - [holdUntilRouted] 면 목적지가 설정될 때까지(`requestRoute*`) 첫 점에 머문다 — 후속차(T30 시연: 합류 → "선두 목적지까지 안내" 수락 → 출발).
 *
 * GPX 는 `assets/gpx/lead.gpx`(선두) · `assets/gpx/follow.gpx`(후속) — 직선 보간한 **가상 경로**이며 실제 도로·주소와 무관하다.
 *
 * @param tickMillis   위치 이벤트 간격(기본 1 Hz)
 * @param route        재생할 경로(위경도). 비어 있으면 드리프트 시나리오
 * @param speedMps     재생 속도(m/s). 기본 60 km/h
 * @param destination  처음부터 설정돼 있는 목적지(`currentDestination()` 응답). 시연에서 "내비에서 불러오기" 용
 * @param holdUntilRouted 목적지가 생길 때까지 출발하지 않는다(후속차 역할)
 */
class FakeNaviAdapter(
    private val tickMillis: Long = 1_000L,
    route: List<LatLon> = emptyList(),
    private val speedMps: Double = DEMO_SPEED_MPS,
    destination: Place? = null,
    private val startLat: Double = DEMO_START_LAT,
    private val startLon: Double = DEMO_START_LON,
    private val holdUntilRouted: Boolean = false,
) : NaviAdapter {

    /** 틱당 이동 거리로 재표본화한 경로. 2점 미만이면 빈 목록 */
    private val track: List<LatLon> =
        if (route.size >= 2) GpxRoute.resample(route, speedMps * tickMillis / 1000.0) else emptyList()

    /** track[i] 까지의 누적 거리(m) */
    private val cumulativeM: DoubleArray = DoubleArray(track.size).also { cum ->
        for (i in 1 until track.size) cum[i] = cum[i - 1] + GpxRoute.distanceM(track[i - 1], track[i])
    }

    private val commandEvents = MutableSharedFlow<NaviEvent>(extraBufferCapacity = 16)
    private val lock = Mutex()

    @Volatile
    private var destination: Place? = destination

    @Volatile
    private var waypoints: List<Place> = emptyList()

    /** 아직 도착 이벤트를 안 보낸 경유지 */
    @Volatile
    private var pendingWaypoints: List<Place> = emptyList()

    /** 현재 재생 위치 인덱스(테스트·진단용) */
    @Volatile
    var position: Int = 0
        private set

    val routeLengthM: Int get() = cumulativeM.lastOrNull()?.roundToInt() ?: 0

    override fun events(): Flow<NaviEvent> = merge(scenario(), commandEvents)

    private fun scenario(): Flow<NaviEvent> = flow {
        emit(NaviEvent.NavigationStatus(status = "FREE_DRIVE", isGuiding = false))
        if (track.isEmpty()) {
            drift()
        } else {
            playback()
        }
    }

    private suspend fun FlowCollector<NaviEvent>.playback() {
        var i = 0
        var arrivedSent = false
        var lastHeading = 0f
        while (true) {
            position = i
            val here = track[i]
            val next = track.getOrNull(i + 1)
            val heading = if (next != null && next != here) GpxRoute.bearingDeg(here, next).also { lastHeading = it } else lastHeading
            emit(NaviEvent.CurrentLocation(Fix(lat = here.lat, lon = here.lon, heading = heading, accuracy = 5f, ts = System.currentTimeMillis(), source = LocSource.NAVI)))

            // 경유지 도착
            val arrived = pendingWaypoints.firstOrNull { GpxRoute.distanceM(here, LatLon(it.lat, it.lon)) <= ARRIVE_RADIUS_M }
            if (arrived != null) {
                val index = waypoints.indexOf(arrived).takeIf { it >= 0 }
                lock.withLock { pendingWaypoints = pendingWaypoints - arrived }
                DhLog.i("FakeNavi waypoint arrived ${arrived.toLogString()}")
                emit(NaviEvent.WaypointArrived(index = index, waypoint = arrived, name = arrived.name))
            }

            val dest = destination
            if (dest != null) {
                val remainM = (cumulativeM.last() - cumulativeM[i]).roundToInt()
                if (i % TRIP_EVERY_TICKS == 0 || i == track.lastIndex) {
                    emit(NaviEvent.TripInfo(remainDistanceM = remainM, remainTimeS = (remainM / speedMps).roundToInt(), destinationArrivalTs = null))
                    emit(NaviEvent.DrivingInfo(speedKph = if (i < track.lastIndex) (speedMps * 3.6).toFloat() else 0f, roadName = null))
                }
                if (i == track.lastIndex && !arrivedSent) {
                    arrivedSent = true
                    DhLog.i("FakeNavi destination arrived ${dest.toLogString()}")
                    emit(NaviEvent.DestinationArrived(destination = dest, name = dest.name))
                    emit(NaviEvent.RouteStateInfo(RouteState.ENDED))
                }
            } else {
                arrivedSent = false
            }
            val holding = holdUntilRouted && destination == null
            if (i < track.lastIndex && !holding) i++
            delay(tickMillis)
        }
    }

    /** 경로 없음 — 기준점에서 북동쪽으로 틱당 약 1.5 m 씩 이동하는 척한다 */
    private suspend fun FlowCollector<NaviEvent>.drift() {
        var tick = 0
        while (true) {
            position = tick
            val fix = Fix(lat = startLat + tick * 0.00001, lon = startLon + tick * 0.00001, heading = 45f, accuracy = 5f, ts = System.currentTimeMillis(), source = LocSource.NAVI)
            emit(NaviEvent.CurrentLocation(fix))
            if (tick % TRIP_EVERY_TICKS == 0 && destination != null) {
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
            pendingWaypoints = emptyList()
        }
        DhLog.i("FakeNavi requestRoute ${dest.toLogString()}")
        commandEvents.emit(NaviEvent.DestinationChanged(dest))
        commandEvents.emit(NaviEvent.RouteStateInfo(RouteState.STARTED))
    }

    override suspend fun requestRouteWithWaypoints(dest: Place, waypoints: List<Place>) {
        lock.withLock {
            destination = dest
            this.waypoints = waypoints
            pendingWaypoints = waypoints
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
            pendingWaypoints = pendingWaypoints + place
            waypoints
        }
        DhLog.i("FakeNavi addWaypoint ${place.toLogString()} index=$index total=${updated.size}")
        commandEvents.emit(NaviEvent.WaypointChanged(updated))
    }

    override suspend fun currentDestination(): Place? = destination

    override suspend fun showNavigation() {
        DhLog.i("FakeNavi showNavigation (내비 화면 없음 — 로그만)")
    }

    companion object {
        /** 데모 시작점(임의 지점) — 실제 장소·주소와 무관 */
        const val DEMO_START_LAT = 36.3500
        const val DEMO_START_LON = 127.3800

        /** 기본 재생 속도 60 km/h */
        const val DEMO_SPEED_MPS = 16.7
        const val ARRIVE_RADIUS_M = 60.0
        const val TRIP_EVERY_TICKS = 10

        const val ASSET_LEAD = "gpx/lead.gpx"
        const val ASSET_FOLLOW = "gpx/follow.gpx"

        /** 시연용 GPX(lead/follow) 의 공통 종점 — 가상 경로의 끝, 실제 장소·주소와 무관 */
        val DEMO_DESTINATION = Place(lat = 37.2800, lon = 127.5400, name = "여주 방면(시연)", poiId = null, address = null)

        /**
         * assets 의 GPX 로 만든다. 파일이 없거나 깨지면 드리프트 시나리오로 폴백(크래시 없음).
         * 후속차는 `destination = null, holdUntilRouted = true` — 합류 뒤 선두 목적지 안내를 수락하면 출발한다(T30).
         */
        fun fromAsset(
            context: Context,
            asset: String,
            tickMillis: Long = 1_000L,
            destination: Place? = DEMO_DESTINATION,
            holdUntilRouted: Boolean = false,
        ): FakeNaviAdapter {
            val points = GpxRoute.loadAsset(context, asset)
            val first = points.firstOrNull()
            return FakeNaviAdapter(
                tickMillis = tickMillis,
                route = points,
                destination = destination,
                startLat = first?.lat ?: DEMO_START_LAT,
                startLon = first?.lon ?: DEMO_START_LON,
                holdUntilRouted = holdUntilRouted,
            ).also { DhLog.i("FakeNavi asset=$asset points=${points.size} track=${it.track.size} lengthM=${it.routeLengthM} hold=$holdUntilRouted") }
        }
    }
}
