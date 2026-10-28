package app.donghaeng.identity.web

import app.donghaeng.identity.AuthService
import app.donghaeng.protocol.rest.ApiHeaders
import app.donghaeng.protocol.rest.ApiPaths
import app.donghaeng.protocol.rest.ExchangeRequest
import app.donghaeng.protocol.rest.LoginRequest
import app.donghaeng.protocol.rest.RefreshRequest
import app.donghaeng.protocol.rest.TokenResponse
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

/** docs/03 §1.1 사용자 인증 — 모두 인증 없이 호출 (SecurityConfig.PUBLIC_PATHS). 본문은 protocol 의 DTO 그대로. */
@RestController
class AuthController(private val auth: AuthService) {

    /** MVP 개발용 간편 로그인 (`provider = dev`) → 200 사용자 토큰 쌍 / 403 FORBIDDEN(서버에 dev 로그인 키가 있는데 헤더가 없거나 다름) */
    @PostMapping(ApiPaths.AUTH_LOGIN)
    fun login(
        @RequestHeader(name = ApiHeaders.DEV_LOGIN_KEY, required = false) devLoginKey: String?,
        @RequestBody req: LoginRequest,
    ): TokenResponse = auth.login(req, devLoginKey)

    /** 호스트 공식 앱 OAuth 토큰 교환 → 200 / 401 INVALID_TOKEN / 501 PROVIDER_NOT_SUPPORTED */
    @PostMapping(ApiPaths.AUTH_EXCHANGE)
    fun exchange(@RequestBody req: ExchangeRequest): TokenResponse = auth.exchange(req)

    /** refresh 회전 → 200 새 토큰 쌍 / 401 INVALID_TOKEN */
    @PostMapping(ApiPaths.AUTH_REFRESH)
    fun refresh(@RequestBody req: RefreshRequest): TokenResponse = auth.refresh(req)
}
