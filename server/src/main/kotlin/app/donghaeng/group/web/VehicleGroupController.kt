package app.donghaeng.group.web

import app.donghaeng.group.GroupService
import app.donghaeng.protocol.GroupJoined
import app.donghaeng.protocol.rest.ApiPaths
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * docs/03 §2.3 `GET /v1/vehicles/me/group` (계약 1) — 차량 JWT 전용(`SecurityConfig` 가 ROLE_VEHICLE 로 먼저 막는다).
 * 차량이 지금 들어가 있는 진행 중 그룹이 있으면 200 + `GroupJoined`(cmd `GROUP_JOINED` 본문과 같은 모양: groupId·name·leaderVehicleId·destination·topics),
 * 없으면 204(본문 없음). 해제·소유권 이전된 차량의 옛 토큰도 204(RC-2 — 차가 옛 세션을 지운다, 새 소유자의 그룹은 알려 주지 않는다).
 * 차량 앱은 앱 시작·MQTT 재접속 때 이것으로 로컬 그룹 세션을 서버와 맞춘다.
 */
@RestController
class VehicleGroupController(private val groups: GroupService) {

    @GetMapping(ApiPaths.VEHICLES_ME_GROUP)
    fun currentGroup(@AuthenticationPrincipal jwt: Jwt): ResponseEntity<GroupJoined> {
        val joined = groups.currentGroupOf(jwt.requireVehicle()) ?: return ResponseEntity.noContent().build()
        return ResponseEntity.ok(joined)
    }
}
