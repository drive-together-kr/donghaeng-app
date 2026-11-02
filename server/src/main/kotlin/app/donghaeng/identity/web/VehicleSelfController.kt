package app.donghaeng.identity.web

import app.donghaeng.identity.MyVehicleService
import app.donghaeng.protocol.rest.ApiPaths
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.RestController

/**
 * docs/03 §1.3 `DELETE /v1/vehicles/me` (계약 2) — 차량 JWT 로 **자기** 페어링을 해제한다(차량 설정 '연결 해제').
 * 폰의 `DELETE /me/vehicles/{id}` 와 같은 서비스 경로(활성 그룹에서 MEMBER_LEFT reason `UNPAIRED`, refresh 전부 폐기, 커밋 뒤 `TOKEN_REVOKED`).
 * 이미 해제됐거나 토큰 발급 뒤 소유권이 넘어간 차도 204(멱등). 토큰이 만료·무효면 401 — 차량 앱은 204·401 을 모두 성공으로 본다.
 */
@RestController
class VehicleSelfController(private val myVehicles: MyVehicleService) {

    @DeleteMapping(ApiPaths.VEHICLES_ME)
    fun unpairSelf(@AuthenticationPrincipal jwt: Jwt): ResponseEntity<Void> {
        val (vehicleId, ownerId) = jwt.requireVehicleClaims()
        myVehicles.unpairSelf(vehicleId, ownerId)
        return ResponseEntity.noContent().build()
    }
}
