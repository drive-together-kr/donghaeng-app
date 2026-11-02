package app.donghaeng.group

import app.donghaeng.group.repo.GroupRepository
import app.donghaeng.protocol.Place
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * 그룹 경로(목적지·경유지)를 바꾸는 창구 — routesync(T28)가 선두 `ROUTE_CHANGED` 를 받으면 부른다 (C032, docs/03 §5.2·§2.5).
 * 늦게 합류한 차의 `GROUP_JOINED.destination`·초대 미리보기·스냅샷 ARRIVED 판정·합류 직후 경로 재전송([VehicleJoinedGroup])이 이 값을 쓴다.
 */
interface GroupRouteApi {
    /**
     * 그룹 행을 `FOR UPDATE` 로 잡고 그룹이 진행 중이며 [leaderVehicleId] 가 **지금** 방장일 때만 경로를 바꾼다 — 그 사이 방장 위임·종료가 커밋됐으면 false.
     * 값([destination]·[waypoints])은 호출자가 이미 검증·정리했다. 호출자 트랜잭션에 참여한다.
     */
    fun updateRoute(groupId: String, leaderVehicleId: String, destination: Place, waypoints: List<Place>): Boolean
}

@Component
class DefaultGroupRouteApi(
    private val groups: GroupRepository,
    private val clock: Clock,
) : GroupRouteApi {

    @Transactional
    override fun updateRoute(groupId: String, leaderVehicleId: String, destination: Place, waypoints: List<Place>): Boolean {
        val group = groups.findByIdForUpdate(groupId) ?: return false
        if (!group.active || group.leaderVehicleId != leaderVehicleId) {
            log.info("route update ignored groupId={} active={} leaderMatches={}", groupId, group.active, group.leaderVehicleId == leaderVehicleId)
            return false
        }
        return groups.updateRoute(groupId, destination, waypoints, clock.instant()) == 1
    }

    companion object {
        private val log = LoggerFactory.getLogger(DefaultGroupRouteApi::class.java)
    }
}

/**
 * 차량이 그룹에 합류했고 `GROUP_JOINED` cmd 를 **보낸 뒤** group 모듈이 발행하는 Spring 애플리케이션 이벤트(커밋 뒤, 같은 스레드).
 * routesync 가 받아 그룹 경로에 경유지가 있으면 그 차에만 `ROUTE_FOLLOW_PROPOSAL` 을 다시 보낸다(C032 — 선두는 같은 경로를 한 번만 보내므로
 * 늦게 합류한 차는 경유지를 받을 길이 없었다). `GROUP_JOINED` 뒤에 보내야 차량이 세션 없이 제안을 버리지 않는다.
 */
data class VehicleJoinedGroup(
    val groupId: String,
    val vehicleId: String,
    val leaderVehicleId: String,
    val destination: Place,
    val waypoints: List<Place>,
)
