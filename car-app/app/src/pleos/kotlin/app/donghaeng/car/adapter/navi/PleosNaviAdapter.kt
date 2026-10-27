package app.donghaeng.car.adapter.navi

import ai.pleos.playground.navi.helper.NaviHelper
import android.content.Context
import app.donghaeng.car.core.common.DhLog
import app.donghaeng.car.domain.model.NaviEvent
import app.donghaeng.car.domain.model.Place
import app.donghaeng.car.domain.model.WaypointIndex
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Pleos NaviHelper 3.0.5 어댑터 — **T04 시점에는 골격만** (Hilt 에 바인딩되지 않음).
 *
 * SDK 시그니처(`NaviHelper-3.0.5.aar`, `navi-data-3.0.4.aar` javap):
 * ```
 * NaviHelper(context).initialize() | initialize(onSuccess: () -> Unit, onError: (Throwable) -> Unit) / release()
 * addListener(NaviHelperEventListener) / removeListener(listener)
 * requestRoute(RouteInfo(latitude, longitude, displayLatitude?, displayLongitude?, poiName, poiId, address, poiSubId, routeOption, poiProvider, isMobileRequest))
 * requestRouteWithWaypoints(RouteWithWaypointsRequest)   addWaypoint(RequestWaypointInfo(lat, lon, poiName, waypointIndex, poiId, address, poiSubId, poiProvider))
 * getDestinationInfo() → onDestinationInfo(DestinationInfo)     // onDestinationChanged() 는 인자가 없다 → 받은 뒤 getDestinationInfo() 재조회
 * getCurrentLocationInfo() → onCurrentLocationInfo(CurrentLocationInfo(latitude, longitude, heading, address, routeId, currentRouteIndex))
 * requestNavigationScreen(NavigationScreenRequest)
 * 콜백: onTripInfo(TripInfo(distance, eta, estimatedSocAtDest, energyConsumption, summary)), onDrivingInfo, onRouteStateInfo,
 *       onRouteStarted(RouteStartInfo), onRouteEnded(), onRouteCancelled(), onWaypointChanged(WaypointChangedInfo),
 *       onWayPointArrived(WaypointArrivedInfo), onDestinationArrived(DestinationArrivedInfo), onCurrentRoadSpeedLimit(int),
 *       onNavigationStatus(NavigationStatus), onError(NaviErrorCode)
 * ```
 * 모든 필드는 nullable 로 다룬다(docs/05 §3.1). 좌표는 로그에 마스킹해서만 남긴다.
 *
 * TODO(T20): events() = callbackFlow { val l = object : NaviHelperEventListener {...}; helper.addListener(l); awaitClose { helper.removeListener(l) } }
 * TODO(T20): Place ↔ RouteInfo / RequestWaypointInfo 변환, WaypointIndex ↔ ai.pleos.playground.navi.constants.WaypointIndex 매핑
 */
class PleosNaviAdapter(context: Context) : NaviAdapter {

    private val appContext = context.applicationContext
    private var helper: NaviHelper? = null

    fun initialize(): Boolean = try {
        if (helper == null) helper = NaviHelper(appContext).also { it.initialize() }
        true
    } catch (e: Exception) {
        DhLog.w("PleosNaviAdapter initialize 실패", e)
        helper = null
        false
    }

    fun release() {
        runCatching { helper?.release() }.onFailure { DhLog.w("PleosNaviAdapter release 실패", it) }
        helper = null
    }

    override fun events(): Flow<NaviEvent> = emptyFlow()                                              // TODO(T20)
    override suspend fun requestRoute(dest: Place) = Unit                                             // TODO(T20)
    override suspend fun requestRouteWithWaypoints(dest: Place, waypoints: List<Place>) = Unit         // TODO(T20)
    override suspend fun addWaypoint(place: Place, index: WaypointIndex) = Unit                        // TODO(T20/T28)
    override suspend fun currentDestination(): Place? = null                                           // TODO(T17/T20)
    override suspend fun showNavigation() = Unit                                                       // TODO(T20)
}
