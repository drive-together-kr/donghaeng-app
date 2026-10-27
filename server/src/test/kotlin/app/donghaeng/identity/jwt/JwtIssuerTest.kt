package app.donghaeng.identity.jwt

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jwt.SignedJWT
import org.springframework.security.oauth2.jwt.JwtException
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JwtIssuerTest {

    private val keys = JwtKeyProvider("") // 개발용 생성 키
    private val now: Instant = Instant.now().truncatedTo(ChronoUnit.SECONDS)
    private val issuer = JwtIssuer(keys, Clock.fixed(now, ZoneOffset.UTC))
    private val decoder = JwtDecoderConfig().jwtDecoder(keys)

    @Test
    fun `user token has RS256 kid header and the docs-06 claims`() {
        val token = issuer.userToken("usr_01TEST")
        val jwt = SignedJWT.parse(token)
        assertEquals(JWSAlgorithm.RS256, jwt.header.algorithm)
        assertEquals(keys.kid, jwt.header.keyID)
        assertEquals("JWT", jwt.header.type.type)

        val claims = jwt.jwtClaimsSet
        assertEquals("donghaeng", claims.issuer)
        assertEquals("usr_01TEST", claims.subject)
        assertEquals("user", claims.getStringClaim("typ"))
        assertNull(claims.getStringClaim("owner"))
        assertEquals(now, claims.issueTime.toInstant())
        assertEquals(now.plus(Duration.ofHours(1)), claims.expirationTime.toInstant())
        assertNotNull(claims.jwtid)
    }

    @Test
    fun `vehicle token carries owner and lives 24h`() {
        val token = issuer.vehicleToken("veh_01TEST", "usr_01OWNER")
        val claims = SignedJWT.parse(token).jwtClaimsSet
        assertEquals("veh_01TEST", claims.subject)
        assertEquals("vehicle", claims.getStringClaim("typ"))
        assertEquals("usr_01OWNER", claims.getStringClaim("owner"))
        assertEquals(now.plus(Duration.ofHours(24)), claims.expirationTime.toInstant())
    }

    @Test
    fun `tokens round-trip through the JwtDecoder built from the same key`() {
        val user = decoder.decode(issuer.userToken("usr_01TEST"))
        assertEquals("usr_01TEST", user.subject)
        assertEquals("user", user.getClaimAsString("typ"))
        // Jwt.getIssuer() 는 URL 변환을 시도하므로 문자열 클레임으로 읽는다 (JwtIssuerValidator 는 문자열 비교)
        assertEquals("donghaeng", user.getClaimAsString("iss"))

        val vehicle = decoder.decode(issuer.vehicleToken("veh_01TEST", "usr_01OWNER"))
        assertEquals("veh_01TEST", vehicle.subject)
        assertEquals("vehicle", vehicle.getClaimAsString("typ"))
        assertEquals("usr_01OWNER", vehicle.getClaimAsString("owner"))
    }

    @Test
    fun `decoder rejects tokens signed by another key, expired or tampered`() {
        val otherIssuer = JwtIssuer(JwtKeyProvider(""), Clock.fixed(now, ZoneOffset.UTC))
        assertFailsWith<JwtException> { decoder.decode(otherIssuer.userToken("usr_01TEST")) }

        val expired = issuer.userToken("usr_01TEST", now = now.minus(Duration.ofHours(2)))
        assertFailsWith<JwtException> { decoder.decode(expired) }

        val token = issuer.userToken("usr_01TEST")
        val parts = token.split('.')
        val tampered = parts[0] + "." + Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"iss":"donghaeng","sub":"usr_EVIL","typ":"user","exp":${now.epochSecond + 3600}}""".toByteArray()) + "." + parts[2]
        assertFailsWith<JwtException> { decoder.decode(tampered) }
    }

    @Test
    fun `jwk set exposes exactly one public RSA signing key`() {
        val set = JWKSet.parse(keys.jwkSetJson())
        assertEquals(1, set.keys.size)
        val key = set.keys.single()
        assertEquals("RSA", key.keyType.value)
        assertEquals(KeyUse.SIGNATURE, key.keyUse)
        assertEquals(JWSAlgorithm.RS256, key.algorithm)
        assertEquals(keys.kid, key.keyID)
        assertFalse(key.isPrivate, "JWKS must not contain private members")
        assertFalse(keys.jwkSetJson().contains("\"d\""))
        assertEquals(keys.publicKey.modulus, key.toRSAKey().modulus.decodeToBigInteger())
    }

    @Test
    fun `PKCS8 PEM with escaped newlines loads the same key and a stable kid`() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val b64 = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(pair.private.encoded)
        val pem = "-----BEGIN PRIVATE KEY-----\n$b64\n-----END PRIVATE KEY-----\n"
        val escaped = pem.replace("\n", "\\n") // .env 한 줄 표기

        val fromPem = JwtKeyProvider(pem)
        val fromEscaped = JwtKeyProvider(escaped)
        assertFalse(fromPem.generated)
        assertEquals((pair.public as RSAPublicKey).modulus, fromPem.publicKey.modulus)
        assertEquals(fromPem.kid, fromEscaped.kid)
        assertEquals(JwtKeyProvider.keyId(pair.public as RSAPublicKey), fromPem.kid)
        assertEquals(JwtKeyProvider.KID_HEX_LENGTH, fromPem.kid.length)

        // 다른 키는 다른 kid
        assertNotEquals(keys.kid, fromPem.kid)
        assertTrue(keys.generated)
    }

    @Test
    fun `PKCS1 PEM is rejected with a hint`() {
        val e = assertFailsWith<IllegalArgumentException> {
            JwtKeyProvider("-----BEGIN RSA PRIVATE KEY-----\nAAAA\n-----END RSA PRIVATE KEY-----")
        }
        assertTrue(e.message!!.contains("PKCS#8"))
    }
}
