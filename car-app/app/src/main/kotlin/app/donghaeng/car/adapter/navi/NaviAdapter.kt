package app.donghaeng.car.adapter.navi

import app.donghaeng.car.domain.model.NaviEvent
import app.donghaeng.car.domain.model.NextTurnResult
import app.donghaeng.car.domain.model.Place
import app.donghaeng.car.domain.model.WaypointIndex
import kotlinx.coroutines.flow.Flow

/**
 * 내비게이션 어댑터 (docs/05 §3). NaviHelper 3.0.x 를 감싼다.
 *
 * 구현: `PleosNaviAdapter`(pleos flavor, T20) / [FakeNaviAdapter](GPX 재생은 T20·T30, 지금은 고정 시나리오).
 * SDK 타입(RouteInfo, RequestWaypointInfo, NaviHelperEventListener …)은 이 패키지 밖으로 새지 않는다.
 */
interface NaviAdapter {
    /**
     * 내비 이벤트 스트림. 구독 시 `addListener`, 취소 시 **같은 리스너로** `removeListener`.
     * CurrentLocation, TripInfo, DrivingInfo, RouteStateInfo, DestinationChanged, WaypointChanged,
     * WaypointArrived, DestinationArrived, SpeedLimit, NavigationStatus, Error
     */
    fun events(): Flow<NaviEvent>

    /** 목적지까지 경로 요청 (NaviHelper requestRoute) */
    suspend fun requestRoute(dest: Place)

    /** 경유지 포함 경로 요청 (NaviHelper requestRouteWithWaypoints) — addWaypoint 실패 시 폴백(docs/10 R5) */
    suspend fun requestRouteWithWaypoints(dest: Place, waypoints: List<Place>)

    /** 현재 경로에 경유지 추가 (NaviHelper addWaypoint) */
    suspend fun addWaypoint(place: Place, index: WaypointIndex = WaypointIndex.FIRST)

    /** 현재 목적지. 안내 중이 아니면 null */
    suspend fun currentDestination(): Place?

    /** 내비 화면 띄우기 (NaviHelper requestNavigationScreen) */
    suspend fun showNavigation()

    /**
     * 다음 회전 안내(TBT) 한 번 묻기 — NaviHelper `getTBTInfo()` → `onTBTInfo(List<TBTInfo>)` 를 최대 [timeoutMs] 기다린다.
     * 빈 목록 = [NextTurnResult.NoGuidance](안내 중 아님·안내 끝), 응답 없음·실패 = [NextTurnResult.Unknown].
     * 알림 카드가 회전 직전 TBT 를 가리지 않게 미룰지 정할 때만 쓴다(docs/05 §5.2 — 평소엔 묻지 않는다).
     */
    suspend fun nextTurn(timeoutMs: Long): NextTurnResult
}
