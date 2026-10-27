package app.donghaeng.identity

import app.donghaeng.identity.jwt.JwtIssuer
import app.donghaeng.identity.repo.UserRecord
import app.donghaeng.identity.repo.UserRepository
import app.donghaeng.identity.repo.VehicleRepository
import app.donghaeng.protocol.rest.ExchangeRequest
import app.donghaeng.protocol.rest.LoginRequest
import app.donghaeng.protocol.rest.RefreshRequest
import app.donghaeng.protocol.rest.TokenResponse
import app.donghaeng.protocol.rest.UserDto
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 사용자 인증 (docs/03 §1.1): dev 로그인, 공식 앱 토큰 교환, refresh 회전.
 */
@Service
class AuthService(
    private val users: UserRepository,
    private val vehicles: VehicleRepository,
    private val verifiers: List<ExternalTokenVerifier>,
    private val jwt: JwtIssuer,
    private val refreshTokens: RefreshTokenService,
) {
    /** MVP 개발용 간편 로그인 — `provider = dev` 만. `provider_sub` 는 소문자 이메일. */
    @Transactional
    fun login(req: LoginRequest): TokenResponse {
        if (req.provider.trim().lowercase() != Providers.DEV) {
            throw IdentityErrors.validation("provider: 간편 로그인은 dev 만 지원해요. 공식 앱 계정은 /auth/exchange 를 쓰세요")
        }
        val email = req.email.trim().lowercase()
        if (email.isEmpty() || email.length > MAX_EMAIL_LENGTH || !email.contains('@')) {
            throw IdentityErrors.validation("email: 이메일 형식이 아니에요")
        }
        val displayName = normalizeDisplayName(req.displayName)
        val user = users.upsert(Providers.DEV, email, displayName)
        log.info("dev login userId={}", user.userId)
        return tokensFor(user)
    }

    /** 호스트 공식 앱 OAuth 토큰 → 동행 JWT. 검증은 provider 별 [ExternalTokenVerifier] 로만. */
    @Transactional
    fun exchange(req: ExchangeRequest): TokenResponse {
        val provider = req.provider.trim().lowercase()
        val verifier = verifiers.firstOrNull { provider in it.providers }
            ?: throw IdentityErrors.validation("provider: 알 수 없는 제공자예요")
        if (req.accessToken.isBlank()) throw IdentityErrors.invalidToken()
        val identity = verifier.verify(provider, req.accessToken)
        val user = users.upsert(identity.provider, identity.sub, normalizeDisplayName(identity.displayName))
        log.info("token exchanged provider={} userId={}", provider, user.userId)
        return tokensFor(user)
    }

    /**
     * refresh 회전. 주체가 사용자(`usr_`)면 사용자 토큰, 차량(`veh_`)이면 차량 토큰을 `accessToken` 에 담고 `user` 는 소유자.
     * 해제된 차량의 토큰은 거부한다.
     */
    @Transactional
    fun refresh(req: RefreshRequest): TokenResponse {
        val rotation = refreshTokens.rotate(req.refreshToken)
        val subject = rotation.subject
        return when {
            subject.startsWith(USER_PREFIX) -> {
                val user = users.findById(subject) ?: throw IdentityErrors.invalidToken()
                TokenResponse(jwt.userToken(user.userId), rotation.refreshToken, UserDto(user.userId, user.displayName))
            }
            subject.startsWith(VEHICLE_PREFIX) -> {
                val vehicle = vehicles.findById(subject)?.takeIf { it.active } ?: throw IdentityErrors.invalidToken()
                val owner = users.findById(vehicle.ownerId) ?: throw IdentityErrors.invalidToken()
                TokenResponse(jwt.vehicleToken(vehicle.vehicleId, vehicle.ownerId), rotation.refreshToken, UserDto(owner.userId, owner.displayName))
            }
            else -> throw IdentityErrors.invalidToken()
        }
    }

    private fun tokensFor(user: UserRecord) = TokenResponse(
        accessToken = jwt.userToken(user.userId),
        refreshToken = refreshTokens.issue(user.userId),
        user = UserDto(user.userId, user.displayName),
    )

    private fun normalizeDisplayName(raw: String): String {
        val name = raw.trim()
        if (name.isEmpty()) throw IdentityErrors.validation("displayName: 표시 이름이 비어 있어요")
        if (name.length > MAX_DISPLAY_NAME_LENGTH) throw IdentityErrors.validation("displayName: ${MAX_DISPLAY_NAME_LENGTH}자 이하여야 해요")
        return name
    }

    companion object {
        private val log = LoggerFactory.getLogger(AuthService::class.java)
        const val USER_PREFIX = "usr_"
        const val VEHICLE_PREFIX = "veh_"
        const val MAX_EMAIL_LENGTH = 254
        const val MAX_DISPLAY_NAME_LENGTH = 40
    }
}
