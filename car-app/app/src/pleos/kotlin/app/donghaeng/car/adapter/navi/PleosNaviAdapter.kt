package app.donghaeng.car.adapter.navi

import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.data.NavigationScreenRequest
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.RouteWithWaypointsRequest
import ai.pleos.playground.navi.data.TripInfo
import ai.pleos.playground.navi.data.WaypointArrivedInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import app.donghaeng.car.core.common.DhLog
import app.donghaeng.car.domain.model.Fix
import app.donghaeng.car.domain.model.NaviEvent
import app.donghaeng.car.domain.model.Place
import app.donghaeng.car.domain.model.RouteState
import app.donghaeng.car.domain.model.WaypointIndex
import app.donghaeng.car.domain.model.toLogString
import app.donghaeng.protocol.LocSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Pleos NaviHelper 3.0.5 어댑터 (T20, docs/05 §3.1). SDK 타입은 이 파일 밖으로 나가지 않는다.
 *
 * - [events]: 구독마다 `NaviHelperEventListener` 를 등록하고 `awaitClose` 에서 **같은 리스너로** 해제한다(CLAUDE.md 규칙 3).
 *   내비가 안내 중이 아니면 위치 콜백이 안 올 수 있어 구독 중에는 1초마다 `getCurrentLocationInfo()` 를 요청한다(응답은 콜백으로 온다).
 *   구독 시작 시 `getRouteStateInfo()`·`getDestinationInfo()` 도 한 번 요청해 현재 상태를 받아 온다.
 * - `onDestinationChanged()` 는 인자가 없다 → `getDestinationInfo()` 를 다시 불러 `onDestinationInfo` 로 받는다.
 * - SDK `DrivingInfo`(목적지·경유지별 남은 시간/거리)와 `TripInfo`(남은 거리·ETA)는 둘 다 도메인 [NaviEvent.TripInfo] 로 보낸다.
 * - 모든 콜백은 try/catch 로 감싸 SDK 쪽 예외가 수집기를 죽이지 않게 하고, 좌표는 마스킹해서만 로그에 남긴다.
 * - 실제로 어떤 콜백이 오는지는 내비 앱 상태에 따라 다르다 → 진단: `adb logcat -s DongHaeng | grep navi`.
 */
class PleosNaviAdapter(context: Context) : NaviAdapter {

    private val appContext = context.applicationContext
    private val lock = Any()

    @Volatile
    private var helper: NaviHelper? = null

    /** 마지막으로 알게 된 목적지·경유지(도착 이벤트에 채워 넣는다) */
    private val lastDestination = AtomicReference<Place?>(null)
    private val lastWaypoints = AtomicReference<List<Place>>(emptyList())

    /** 마지막 제한속도(중복 억제용). Int.MIN_VALUE = 아직 없음 */
    private val lastSpeedLimit = AtomicInteger(Int.MIN_VALUE)

    /** `NaviHelper(context).initialize()` — 실패해도 크래시 없이 null (호출 쪽은 이벤트 없음·요청 무시) */
    fun ensureInitialized(): NaviHelper? {
        synchronized(lock) {
            helper?.let { return it }
            return try {
                NaviHelper(appContext).also {
                    it.initialize()
                    helper = it
                    DhLog.i("navi: NaviHelper initialized")
                }
            } catch (e: Exception) {
                DhLog.w("navi: NaviHelper initialize 실패 — ${e.javaClass.simpleName}: ${e.message}")
                null
            }
        }
    }

    fun release() {
        synchronized(lock) {
            runCatching { helper?.release() }.onFailure { DhLog.w("navi: release 실패", it) }
            helper = null
        }
    }

    // ---------------------------------------------------------------- events

    override fun events(): Flow<NaviEvent> = callbackFlow {
        val h = ensureInitialized()
        if (h == null) {
            trySend(NaviEvent.Error(code = "NOT_AVAILABLE", message = "NaviHelper 초기화 실패"))
            close()
            return@callbackFlow
        }
        val listener = object : NaviHelperEventListener {
            override fun onCurrentLocationInfo(info: CurrentLocationInfo) = guard("onCurrentLocationInfo") {
                val fix = Fix(
                    lat = info.latitude,
                    lon = info.longitude,
                    heading = info.heading.toFloat().takeIf { !it.isNaN() },
                    accuracy = null,
                    ts = System.currentTimeMillis(),
                    source = LocSource.NAVI,
                )
                trySend(NaviEvent.CurrentLocation(fix))
            }

            override fun onTripInfo(info: TripInfo) = guard("onTripInfo") {
                DhLog.d("navi onTripInfo distance=${info.distance} eta=${info.eta}")
                trySend(NaviEvent.TripInfo(remainDistanceM = info.distance, remainTimeS = info.eta, destinationArrivalTs = null))
            }

            override fun onDrivingInfo(info: DrivingInfo) = guard("onDrivingInfo") {
                val d = info.destination
                DhLog.d("navi onDrivingInfo destDistance=${d.distance} destDuration=${d.duration} waypoints=${info.waypoints.size}")
                trySend(NaviEvent.TripInfo(remainDistanceM = d.distance, remainTimeS = d.duration, destinationArrivalTs = null))
            }

            override fun onRouteStateInfo(info: RouteStateInfo) = guard("onRouteStateInfo") {
                DhLog.i("navi onRouteStateInfo routing=${info.isRouting} mode=${info.naviMode} option=${info.routeOption}")
                trySend(NaviEvent.RouteStateInfo(if (info.isRouting) RouteState.STARTED else RouteState.UNKNOWN, detail = info.naviMode.name))
            }

            override fun onRouteStarted(info: RouteStartInfo) = guard("onRouteStarted") {
                DhLog.i("navi onRouteStarted distance=${info.distance} duration=${info.duration}")
                trySend(NaviEvent.RouteStateInfo(RouteState.STARTED, detail = "distance=${info.distance} duration=${info.duration}"))
            }

            override fun onRouteEnded() = guard("onRouteEnded") { trySend(NaviEvent.RouteStateInfo(RouteState.ENDED)) }

            override fun onRouteCancelled() = guard("onRouteCancelled") { trySend(NaviEvent.RouteStateInfo(RouteState.CANCELLED)) }

            override fun onDestinationChanged() = guard("onDestinationChanged") {
                DhLog.i("navi onDestinationChanged → getDestinationInfo")
                h.getDestinationInfo()
            }

            override fun onDestinationInfo(info: DestinationInfo) = guard("onDestinationInfo") {
                val place = info.toPlace()
                lastDestination.set(place)
                DhLog.i("navi onDestinationInfo ${place.toLogString()} remain=${info.remainDistance}m/${info.remainTime}s")
                trySend(NaviEvent.DestinationChanged(place))
            }

            override fun onWaypointChanged(info: WaypointChangedInfo) = guard("onWaypointChanged") {
                DhLog.i("navi onWaypointChanged cmd=${info.cmdType} index=${info.index} → getWaypointInfo")
                h.getWaypointInfo()
            }

            override fun onWayPointInfo(list: List<WaypointInfo>) = guard("onWayPointInfo") {
                val places = list.map { it.toPlace() }
                lastWaypoints.set(places)
                DhLog.i("navi onWayPointInfo count=${places.size}")
                trySend(NaviEvent.WaypointChanged(places))
            }

            override fun onWayPointArrived(info: WaypointArrivedInfo) = guard("onWayPointArrived") {
                val place = lastWaypoints.get().firstOrNull { it.name == info.name }
                DhLog.i("navi onWayPointArrived name=${info.name} index=${info.index}")
                trySend(NaviEvent.WaypointArrived(index = info.index.ordinal, waypoint = place, name = info.name))
            }

            override fun onDestinationArrived(info: DestinationArrivedInfo) = guard("onDestinationArrived") {
                DhLog.i("navi onDestinationArrived name=${info.name}")
                trySend(NaviEvent.DestinationArrived(destination = lastDestination.get(), name = info.name))
            }

            /** AVD 에서 1Hz 로 같은 값(0)이 반복돼 들어온다 → 값이 바뀔 때만 이벤트로 올린다 */
            override fun onCurrentRoadSpeedLimit(speedLimit: Int) = guard("onCurrentRoadSpeedLimit") {
                if (lastSpeedLimit.getAndSet(speedLimit) != speedLimit) {
                    DhLog.i("navi onCurrentRoadSpeedLimit $speedLimit")
                    trySend(NaviEvent.SpeedLimit(speedLimit.takeIf { it > 0 }))
                }
            }

            override fun onNavigationStatus(status: NavigationStatus) = guard("onNavigationStatus") {
                DhLog.i("navi onNavigationStatus $status")
                trySend(NaviEvent.NavigationStatus(status = status.name, isGuiding = status == NavigationStatus.ROUTE_DRIVE))
            }

            override fun onError(errorCode: NaviErrorCode) = guard("onError") {
                DhLog.w("navi onError $errorCode")
                trySend(NaviEvent.Error(code = errorCode.name, message = null))
            }
        }
        try {
            lastSpeedLimit.set(Int.MIN_VALUE)
            h.addListener(listener)
            DhLog.i("navi: listener added")
            // 현재 상태 한 번 당겨 오기
            runCatching { h.getRouteStateInfo() }
            runCatching { h.getDestinationInfo() }
        } catch (e: Exception) {
            DhLog.w("navi: addListener 실패", e)
            close(e)
            return@callbackFlow
        }
        // 안내 중이 아닐 때도 위치를 받기 위해 1Hz 로 요청 (응답은 onCurrentLocationInfo)
        val poller = launch(Dispatchers.IO) {
            while (isActive) {
                runCatching { h.getCurrentLocationInfo() }.onFailure { DhLog.d("navi getCurrentLocationInfo 실패: ${it.javaClass.simpleName}") }
                delay(LOCATION_POLL_MS)
            }
        }
        awaitClose {
            poller.cancel()
            runCatching { h.removeListener(listener) }.onFailure { DhLog.w("navi: removeListener 실패", it) }
            DhLog.i("navi: listener removed")
        }
    }

    // ---------------------------------------------------------------- requests

    override suspend fun requestRoute(dest: Place) = withContext(Dispatchers.IO) {
        val h = ensureInitialized() ?: return@withContext
        DhLog.i("navi requestRoute ${dest.toLogString()}")
        runCatching { h.requestRoute(dest.toRouteInfo()) }.onFailure { DhLog.w("navi requestRoute 실패", it) }
        Unit
    }

    override suspend fun requestRouteWithWaypoints(dest: Place, waypoints: List<Place>) = withContext(Dispatchers.IO) {
        val h = ensureInitialized() ?: return@withContext
        DhLog.i("navi requestRouteWithWaypoints ${dest.toLogString()} waypoints=${waypoints.size}")
        runCatching { h.requestRouteWithWaypoints(RouteWithWaypointsRequest(dest.toRouteInfo(), waypoints.map { it.toRequestWaypoint(WaypointIndex.LAST) })) }
            .onFailure { DhLog.w("navi requestRouteWithWaypoints 실패", it) }
        Unit
    }

    override suspend fun addWaypoint(place: Place, index: WaypointIndex) = withContext(Dispatchers.IO) {
        val h = ensureInitialized() ?: return@withContext
        DhLog.i("navi addWaypoint ${place.toLogString()} index=$index")
        runCatching { h.addWaypoint(place.toRequestWaypoint(index)) }.onFailure { DhLog.w("navi addWaypoint 실패", it) }
        Unit
    }

    /** `getDestinationInfo()` 를 부르고 `onDestinationInfo` 를 최대 [DESTINATION_TIMEOUT_MS] 기다린다. 안내 중이 아니면 보통 응답이 없다 → null */
    override suspend fun currentDestination(): Place? {
        val h = ensureInitialized() ?: return null
        val deferred = CompletableDeferred<Place?>()
        val listener = object : NaviHelperEventListener {
            override fun onDestinationInfo(info: DestinationInfo) {
                deferred.complete(runCatching { info.toPlace() }.getOrNull())
            }
        }
        return try {
            h.addListener(listener)
            h.getDestinationInfo()
            withTimeoutOrNull(DESTINATION_TIMEOUT_MS) { deferred.await() }?.also { lastDestination.set(it) }
        } catch (e: Exception) {
            DhLog.w("navi currentDestination 실패", e)
            null
        } finally {
            runCatching { h.removeListener(listener) }
        }
    }

    override suspend fun showNavigation() = withContext(Dispatchers.IO) {
        val h = ensureInitialized() ?: return@withContext
        runCatching { h.requestNavigationScreen(NavigationScreenRequest.RouteOptionList) }
            .onFailure { DhLog.w("navi requestNavigationScreen 실패", it) }
        Unit
    }

    // ---------------------------------------------------------------- mapping (SDK ↔ 도메인)

    private fun DestinationInfo.toPlace() = Place(lat = latitude, lon = longitude, name = name ?: "", poiId = poiId?.takeIf { it.isNotBlank() }, address = address?.takeIf { it.isNotBlank() })

    private fun WaypointInfo.toPlace() = Place(lat = latitude, lon = longitude, name = name ?: "", poiId = poiId?.takeIf { it.isNotBlank() }, address = address?.takeIf { it.isNotBlank() })

    private fun Place.toRouteInfo() = RouteInfo(
        latitude = lat,
        longitude = lon,
        displayLatitude = null,
        displayLongitude = null,
        poiName = name,
        poiId = poiId ?: "",
        address = address ?: "",
        poiSubId = "0",
        routeOption = RouteOption.RECOMMENDED,
        poiProvider = "",
        isMobileRequest = false,
    )

    private fun Place.toRequestWaypoint(index: WaypointIndex) = RequestWaypointInfo(
        latitude = lat,
        longitude = lon,
        poiName = name,
        waypointIndex = index.toSdk(),
        poiId = poiId ?: "",
        address = address ?: "",
        poiSubId = "0",
        poiProvider = "",
    )

    /** 도메인 FIRST/LAST → SDK FIRST/SECOND (SDK 는 경유지 2개까지: FIRST, SECOND, ALL) */
    private fun WaypointIndex.toSdk(): ai.pleos.playground.navi.constants.WaypointIndex = when (this) {
        WaypointIndex.FIRST -> ai.pleos.playground.navi.constants.WaypointIndex.FIRST
        WaypointIndex.LAST -> ai.pleos.playground.navi.constants.WaypointIndex.SECOND
    }

    private inline fun guard(name: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            DhLog.w("navi $name 처리 실패: ${e.javaClass.simpleName}")
        }
    }

    private companion object {
        const val LOCATION_POLL_MS = 1_000L
        const val DESTINATION_TIMEOUT_MS = 2_000L
    }
}
