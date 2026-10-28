package app.donghaeng.voice.web

import app.donghaeng.group.Caller
import app.donghaeng.identity.jwt.JwtIssuer
import app.donghaeng.voice.VoiceErrors
import org.springframework.security.oauth2.jwt.Jwt

/*
 * JWT → [Caller](group 의 `GroupAccessApi` 계약 타입). 규칙은 group 웹 계층의 `group.web.Callers` 와 같지만 그 내부 함수를 가져다 쓰지 않는다
 * (docs/06 §2 — 다른 모듈의 web·repo 를 import 하지 않는다, `ModuleBoundaryTest`). `typ` 이 맞지 않으면 403 `FORBIDDEN`.
 */

internal fun Jwt.toCaller(): Caller = when (getClaimAsString(JwtIssuer.CLAIM_TYP)) {
    JwtIssuer.TYP_USER -> Caller.User(subject)
    JwtIssuer.TYP_VEHICLE -> Caller.Vehicle(subject, getClaimAsString(JwtIssuer.CLAIM_OWNER))
    else -> throw VoiceErrors.wrongPrincipal("사용자 또는 차량 토큰이 필요해요.")
}

internal fun Jwt.requireVehicle(): Caller.Vehicle =
    toCaller() as? Caller.Vehicle ?: throw VoiceErrors.wrongPrincipal("차량 토큰이 필요해요.")
