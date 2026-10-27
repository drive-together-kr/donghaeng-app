package app.donghaeng.group.web

import app.donghaeng.group.GroupService
import app.donghaeng.protocol.rest.ApiPaths
import app.donghaeng.protocol.rest.MyGroupDto
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** docs/03 §2.3 `GET /me/groups?active=true` — 사용자 JWT (SecurityConfig: `/v1/me/` 아래 → ROLE_USER) */
@RestController
class MeGroupsController(private val groups: GroupService) {

    /** `active` 생략 → 전부(ACTIVE 먼저), true → 진행 중만, false → 종료된 것만 */
    @GetMapping(ApiPaths.ME_GROUPS)
    fun list(@AuthenticationPrincipal jwt: Jwt, @RequestParam(required = false) active: Boolean?): List<MyGroupDto> =
        groups.myGroups(jwt.requireUser(), active)
}
