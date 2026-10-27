package app.donghaeng.car.domain.model

/** 경유지 삽입 위치 (NaviHelper `WaypointIndex` 대응 — SDK 타입은 어댑터 안에서 변환). */
enum class WaypointIndex { FIRST, LAST }

/** 경로 상태 (NaviHelper onRouteStarted / onRouteEnded / onRouteCancelled 를 하나의 이벤트로 묶음). */
enum class RouteState { STARTED, ENDED, CANCELLED, UNKNOWN }

/**
 * 내비게이션 이벤트 (docs/05 §3 NaviAdapter.events()).
 *
 * 실제로 어떤 이벤트가 오는지는 내비 앱 상태에 따라 다르므로 **모든 필드를 nullable** 로 둔다(docs/05 §3.1).
 * SDK 콜백 이름 ↔ 이벤트 매핑은 각 클래스 주석에 적어 두었다.
 */
sealed class NaviEvent {

    /** onCurrentLocationInfo — 내비가 보정한 현재 위치(위치 소스 2순위, docs/05 §3.2) */
    data class CurrentLocation(val fix: Fix) : NaviEvent()

    /** onTripInfo — 목적지까지 남은 거리·시간, 도착 예상 시각 */
    data class TripInfo(
        val remainDistanceM: Int?,
        val remainTimeS: Int?,
        val destinationArrivalTs: Long?,
    ) : NaviEvent()

    /** onDrivingInfo — 내비 기준 주행 정보 */
    data class DrivingInfo(
        val speedKph: Float?,
        val roadName: String?,
    ) : NaviEvent()

    /** onRouteStateInfo / onRouteStarted / onRouteEnded / onRouteCancelled */
    data class RouteStateInfo(val state: RouteState, val detail: String? = null) : NaviEvent()

    /** onDestinationChanged — null 이면 목적지 해제 */
    data class DestinationChanged(val destination: Place?) : NaviEvent()

    /** onWaypointChanged — 현재 경유지 목록 전체 */
    data class WaypointChanged(val waypoints: List<Place>) : NaviEvent()

    /** onWayPointArrived — SDK 는 이름·주소만 주므로 [waypoint] 는 알고 있던 경유지 목록에서 찾아 채우고, 못 찾으면 null */
    data class WaypointArrived(val index: Int?, val waypoint: Place?, val name: String? = null) : NaviEvent()

    /** onDestinationArrived — [destination] 은 마지막으로 알고 있던 목적지(없으면 null), [name] 은 SDK 가 준 이름 */
    data class DestinationArrived(val destination: Place?, val name: String? = null) : NaviEvent()

    /** onCurrentRoadSpeedLimit — km/h, 모르면 null */
    data class SpeedLimit(val limitKph: Int?) : NaviEvent()

    /** onNavigationStatus — 내비 앱 상태 문자열(SDK 값 그대로, 해석은 도메인에서) */
    data class NavigationStatus(val status: String?, val isGuiding: Boolean?) : NaviEvent()

    /** onError */
    data class Error(val code: String?, val message: String?) : NaviEvent()
}
