package app.donghaeng.identity.jwt

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Date
import java.util.UUID

/**
 * 동행 JWT 발급 (docs/06 §5, docs/03 §1).
 *
 * - RS256, `iss = donghaeng`, 헤더 `kid`, `typ = JWT`
 * - 사용자 토큰: `sub = usr_…`, `typ = user`, 1시간
 * - 차량 토큰: `sub = veh_…`, `typ = vehicle`, `owner = usr_…`, 24시간 (EMQX 는 `sub == username(vid)` 를 검증)
 * - `jti` 는 랜덤 UUID (추후 폐기 목록용)
 */
@Component
class JwtIssuer(
    private val keys: JwtKeyProvider,
    private val clock: Clock,
) {
    private val signer = RSASSASigner(keys.privateKey)

    fun userToken(userId: String, now: Instant = clock.instant()): String =
        sign(
            JWTClaimsSet.Builder()
                .subject(userId)
                .claim(CLAIM_TYP, TYP_USER),
            now,
            USER_TOKEN_TTL,
        )

    fun vehicleToken(vehicleId: String, ownerId: String, now: Instant = clock.instant()): String =
        sign(
            JWTClaimsSet.Builder()
                .subject(vehicleId)
                .claim(CLAIM_TYP, TYP_VEHICLE)
                .claim(CLAIM_OWNER, ownerId),
            now,
            VEHICLE_TOKEN_TTL,
        )

    private fun sign(builder: JWTClaimsSet.Builder, now: Instant, ttl: Duration): String {
        val claims = builder
            .issuer(ISSUER)
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plus(ttl)))
            .jwtID(UUID.randomUUID().toString())
            .build()
        val header = JWSHeader.Builder(JWSAlgorithm.RS256)
            .keyID(keys.kid)
            .type(JOSEObjectType.JWT)
            .build()
        return SignedJWT(header, claims).apply { sign(signer) }.serialize()
    }

    companion object {
        const val ISSUER = "donghaeng"
        const val CLAIM_TYP = "typ"
        const val CLAIM_OWNER = "owner"
        const val TYP_USER = "user"
        const val TYP_VEHICLE = "vehicle"
        val USER_TOKEN_TTL: Duration = Duration.ofHours(1)
        val VEHICLE_TOKEN_TTL: Duration = Duration.ofHours(24)
    }
}
