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
import ai.pleos.playground.navi.data.TBTInfo
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
import app.donghaeng.car.domain.model.NextTurn
import app.donghaeng.car.domain.model.NextTurnResult
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt

/**
 * Pleos NaviHelper 3.0.5 어댑터 (T20, docs/05 §3.1). SDK 타입은 이 파일 밖으로 나가지 않는다.
 *
 * - [events]: 구독마다 `NaviHelperEventListener` 를 등록하고 `awaitClose` 에서 **같은 리스너로** 해제한다(CLAUDE.md 규칙 3).
 *   내비가 안내 중이 아니면 위치 콜백이 안 올 수 있어 구독 중에는 1초마다 `getCurrentLocationInfo()` 를 요청한다(응답은 콜백으로 온다).
 *   구독 시작 시 `getRouteStateInfo()`·`getDestinationInfo()` 도 한 번 요청해 현재 상태를 받아 온다.
 * - `onDestinationChanged()` 는 인자가 없다 → `getDestinationInfo()` 를 다시 불러 `onDestinationInfo` 로 받는다.
 * - SDK `DrivingInfo`(목적지·경유지별 남은 시간/거리)와 `TripInfo`(남은 거리·ETA)는 둘 다 도메인 [NaviEvent.TripInfo] 로 보낸다.
 * - [nextTurn]: `getTBTInfo()` → `onTBTInfo` 한 번(요청마다 리스너를 붙였다 떼는 callbackFlow). 알림 카드 TBT 게이트만 쓴다(docs/05 §5.2).
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

    /** TBT 원값 첫 응답을 info 로 한 번 남겼는지(거리 단위 확인용) */
    private val tbtRawLogged = AtomicBoolean(false)

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

    /**
     * 다음 회전 안내 — `getTBTInfo()` 를 부르고 `onTBTInfo(List<TBTInfo>)` 를 최대 [timeoutMs] 기다린다(첫 항목 = 다음 회전).
     * 빈 목록 → [NextTurnResult.NoGuidance], 응답 없음·실패·이상한 거리 → [NextTurnResult.Unknown](카드는 바로 뜬다).
     * `TBTInfo.distance`(Double)는 단위가 문서·샘플에 없다 → **미터로 가정**([TBT_DISTANCE_TO_M]). 첫 응답의 원값은 info 로 한 번,
     * 그 뒤는 debug 로 남겨 기기에서 단위를 확인한다(같은 때의 `onTripInfo distance`(m)·내비 화면 TBT 숫자와 비교).
     */
    override suspend fun nextTurn(timeoutMs: Long): NextTurnResult {
        val h = ensureInitialized() ?: return NextTurnResult.Unknown
        val list = try {
            // getTBTInfo 는 바인더 호출 — 다른 요청처럼 IO 에서
            withContext(Dispatchers.IO) { withTimeoutOrNull(timeoutMs) { tbtResponses(h).first() } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DhLog.d("navi nextTurn 실패 ${e.javaClass.simpleName}")
            return NextTurnResult.Unknown
        }
        if (list == null) {
            DhLog.d("navi nextTurn 응답 없음(${timeoutMs}ms)")
            return NextTurnResult.Unknown
        }
        val first = list.firstOrNull() ?: return NextTurnResult.NoGuidance
        val raw = first.distance
        if (tbtRawLogged.compareAndSet(false, true)) DhLog.i("navi tbt 첫 응답 n=${list.size} type=${first.type.name} rawDistance=$raw (단위 미터로 가정 — 확인용)")
        else DhLog.d("navi tbt n=${list.size} type=${first.type.name} rawDistance=$raw")
        if (raw.isNaN() || raw < 0) return NextTurnResult.Unknown
        return NextTurnResult.Ahead(NextTurn(distanceM = (raw * TBT_DISTANCE_TO_M).roundToInt(), kind = first.type.name, description = first.description))
    }

    /**
     * `onTBTInfo` 응답 스트림 — 구독하면 리스너를 붙이고 `getTBTInfo()` 를 요청, 취소되면 `awaitClose` 에서 **같은 리스너로** 뗀다(CLAUDE.md 규칙 3).
     * NaviHelper 의 리스너 목록은 앱 안의 CopyOnWriteArrayList 라 붙였다 떼는 비용이 작다(바인더 호출은 `getTBTInfo` 한 번).
     * 앱 전체 구독([events])의 리스너도 같은 응답을 받지만 그쪽은 `onTBTInfo` 를 쓰지 않는다.
     */
    private fun tbtResponses(h: NaviHelper): Flow<List<TBTInfo>> = callbackFlow {
        val listener = object : NaviHelperEventListener {
            override fun onTBTInfo(tbtInfoList: List<TBTInfo>) = guard("onTBTInfo") { trySend(tbtInfoList.toList()) }
        }
        h.addListener(listener)
        try {
            h.getTBTInfo()
        } catch (e: Exception) {
            close(e)
        }
        awaitClose { runCatching { h.removeListener(listener) }.onFailure { DhLog.w("navi: TBT removeListener 실패", it) } }
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

        /** `TBTInfo.distance` → 미터 배율. 문서에 단위가 없어 미터(1.0)로 가정 — 기기 로그(`navi tbt 첫 응답 … rawDistance=`)로 확인 후 바꾼다 */
        const val TBT_DISTANCE_TO_M = 1.0
    }
}
