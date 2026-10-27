package app.donghaeng.identity

import app.donghaeng.identity.jwt.JwtIssuer
import app.donghaeng.identity.repo.UserRepository
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.stereotype.Component

/** 계정 제공자 이름 (docs/03 §1.1 `provider`, docs/07 §3 `AccountProvider`) */
object Providers {
    const val DEV = "dev"
    const val BLUELINK = "bluelink"
    const val MYHYUNDAI = "myhyundai"
    const val KIA_CONNECT = "kia_connect"
    const val GENESIS_CONNECTED = "genesis_connected"

    /** 공식 앱 제공자 — 실제 IdP 연동은 OEM 협의 대상(구현 자리) */
    val OEM: Set<String> = setOf(BLUELINK, MYHYUNDAI, KIA_CONNECT, GENESIS_CONNECTED)
}

/** 외부 IdP 가 확인해 준 신원. `provider` + `sub` 가 `app_user (provider, provider_sub)` 로 들어간다. */
data class ExternalIdentity(
    val provider: String,
    val sub: String,
    val displayName: String,
)

/**
 * 호스트 공식 앱의 OAuth 2.0 access token 검증 (docs/06 §5, docs/07 §4.1, CLAUDE.md 규칙 13).
 * `POST /v1/auth/exchange` 는 이 인터페이스로만 토큰을 검증한다. 제공자마다 구현 하나.
 */
interface ExternalTokenVerifier {
    /** 이 구현이 맡는 provider 값들 */
    val providers: Set<String>

    /** 실패는 ApiException(401 INVALID_TOKEN / 501 PROVIDER_NOT_SUPPORTED) 으로 */
    fun verify(provider: String, accessToken: String): ExternalIdentity
}

/**
 * MVP: `dev` 의 access token 은 `/v1/auth/login` 이 발급한 동행 사용자 JWT 자체다 (docs/03 §1.1, docs/07 §4.2).
 * 서명·발급자·만료를 [JwtDecoder] 로 검사하고 `typ = user` 인 dev 사용자여야 한다.
 */
@Component
class DevTokenVerifier(
    private val jwtDecoder: JwtDecoder,
    private val users: UserRepository,
) : ExternalTokenVerifier {

    override val providers: Set<String> = setOf(Providers.DEV)

    override fun verify(provider: String, accessToken: String): ExternalIdentity {
        val jwt = try {
            jwtDecoder.decode(accessToken)
        } catch (e: JwtException) {
            throw IdentityErrors.invalidToken()
        }
        if (jwt.getClaimAsString(JwtIssuer.CLAIM_TYP) != JwtIssuer.TYP_USER) throw IdentityErrors.invalidToken()
        val user = users.findById(jwt.subject) ?: throw IdentityErrors.invalidToken()
        if (user.provider != Providers.DEV) throw IdentityErrors.invalidToken()
        return ExternalIdentity(Providers.DEV, user.providerSub, user.displayName)
    }
}

/** 블루링크·마이현대·기아 커넥트·제네시스 커넥티드 — 구현 자리. 호출되면 501 PROVIDER_NOT_SUPPORTED. */
@Component
class NotSupportedTokenVerifier : ExternalTokenVerifier {

    override val providers: Set<String> = Providers.OEM

    override fun verify(provider: String, accessToken: String): ExternalIdentity =
        throw IdentityErrors.providerNotSupported(provider)
}
