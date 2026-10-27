package app.donghaeng.identity.web

import app.donghaeng.identity.MyVehicleService
import app.donghaeng.protocol.rest.ApiPaths
import app.donghaeng.protocol.rest.MyVehicleDto
import app.donghaeng.protocol.rest.VehiclePatchRequest
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** docs/03 §1.3 내 차 — 사용자 JWT 필요 (SecurityConfig: `/v1/me/` 아래 경로 → ROLE_USER) */
@RestController
@RequestMapping(ApiPaths.ME_VEHICLES)
class MeVehiclesController(private val myVehicles: MyVehicleService) {

    /** 200 `[{ vehicleId, nickname, brand, model, isPrimary }]` — 대표 차량 먼저, 해제된 차량 제외 */
    @GetMapping
    fun list(@AuthenticationPrincipal jwt: Jwt): List<MyVehicleDto> = myVehicles.list(jwt.requireUserId())

    /** `{ nickname?, isPrimary? }` → 200 MyVehicleDto / 404 NOT_FOUND(내 차가 아님) */
    @PatchMapping("/{vehicleId}")
    fun patch(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable vehicleId: String,
        @RequestBody req: VehiclePatchRequest,
    ): MyVehicleDto = myVehicles.patch(jwt.requireUserId(), vehicleId, req)

    /** 페어링 해제 → 204 / 404 */
    @DeleteMapping("/{vehicleId}")
    fun delete(@AuthenticationPrincipal jwt: Jwt, @PathVariable vehicleId: String): ResponseEntity<Void> {
        myVehicles.revoke(jwt.requireUserId(), vehicleId)
        return ResponseEntity.noContent().build()
    }
}
