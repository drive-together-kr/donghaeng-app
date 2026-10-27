package app.donghaeng.group.web

import app.donghaeng.group.Caller
import app.donghaeng.group.GroupErrors
import app.donghaeng.identity.jwt.JwtIssuer
import org.springframework.security.oauth2.jwt.Jwt

/*
 * JWT → [Caller]. `SecurityConfig` 가 역할(ROLE_USER/ROLE_VEHICLE)로 경로를 먼저 막지만, 컨트롤러에서 `typ` 을 한 번 더 본다.
 */

internal fun Jwt.toCaller(): Caller = when (getClaimAsString(JwtIssuer.CLAIM_TYP)) {
    JwtIssuer.TYP_USER -> Caller.User(subject)
    JwtIssuer.TYP_VEHICLE -> Caller.Vehicle(subject, getClaimAsString(JwtIssuer.CLAIM_OWNER))
    else -> throw GroupErrors.wrongPrincipal("사용자 또는 차량 토큰이 필요해요.")
}

internal fun Jwt.requireUser(): Caller.User =
    toCaller() as? Caller.User ?: throw GroupErrors.wrongPrincipal("사용자 토큰이 필요해요.")

internal fun Jwt.requireVehicle(): Caller.Vehicle =
    toCaller() as? Caller.Vehicle ?: throw GroupErrors.wrongPrincipal("차량 토큰이 필요해요.")
