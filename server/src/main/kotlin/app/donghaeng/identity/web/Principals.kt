package app.donghaeng.identity.web

import app.donghaeng.common.api.ApiException
import app.donghaeng.identity.jwt.JwtIssuer
import app.donghaeng.protocol.rest.ErrorCodes
import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.jwt.Jwt

/**
 * 사용자 토큰의 `sub`(usr_…). `SecurityConfig` 가 `/v1/me/` 아래 경로와 `/v1/device/approve` 를 ROLE_USER 로 이미 막지만
 * 컨트롤러에서 `typ = user` 를 한 번 더 확인한다(차량 토큰으로 사용자 API 호출 방지).
 */
internal fun Jwt.requireUserId(): String {
    if (getClaimAsString(JwtIssuer.CLAIM_TYP) != JwtIssuer.TYP_USER) {
        throw ApiException(HttpStatus.FORBIDDEN, ErrorCodes.FORBIDDEN, "사용자 토큰이 필요해요.")
    }
    return subject
}

/** 차량 토큰의 (`sub` = veh_…, `owner` 클레임 = 발급 시점 소유자). `SecurityConfig` 가 `/v1/vehicles/me` 를 ROLE_VEHICLE 로 먼저 막는다 */
internal fun Jwt.requireVehicleClaims(): Pair<String, String?> {
    if (getClaimAsString(JwtIssuer.CLAIM_TYP) != JwtIssuer.TYP_VEHICLE) {
        throw ApiException(HttpStatus.FORBIDDEN, ErrorCodes.FORBIDDEN, "차량 토큰이 필요해요.")
    }
    return subject to getClaimAsString(JwtIssuer.CLAIM_OWNER)
}
