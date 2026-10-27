package app.donghaeng.identity.jwt

import app.donghaeng.common.config.DhProperties
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.RSAKey
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

/**
 * RS256 서명 키 (docs/06 §1 "키는 환경 변수/파일, kid 회전 지원").
 *
 * - `DH_JWT_PRIVATE_KEY_PEM`(PKCS#8 `-----BEGIN PRIVATE KEY-----`)이 있으면 그 키를 쓴다. 한 줄로 넣은 `\n` 이스케이프도 받는다.
 * - 비어 있으면 2048비트 키를 기동 시 생성한다(개발 전용, WARN) — 재시작마다 바뀌어 이전 토큰이 모두 무효가 된다.
 * - `kid` = 공개키(X.509 SubjectPublicKeyInfo) SHA-256 의 앞 16 hex. 키를 바꾸면 kid 도 바뀌어 JWKS 에서 구분된다.
 */
@Component
class JwtKeyProvider(privateKeyPem: String) {

    @Autowired
    constructor(props: DhProperties) : this(props.jwt.privateKeyPem)

    // kotlin-spring(all-open) 이 @Component 를 open 으로 만들므로 init 에서 채우는 속성은 final 로 못 박는다
    final val privateKey: RSAPrivateKey
    final val publicKey: RSAPublicKey
    final val kid: String
    final val generated: Boolean

    /** 서명·검증용 Nimbus 키(개인키 포함). JWKS 에 내보낼 때는 [publicJwk] 만 쓴다. */
    final val rsaKey: RSAKey

    /** `/.well-known/jwks.json` 에 내보내는 공개 부분 (kty RSA, use sig, alg RS256, kid, n, e) */
    final val publicJwk: RSAKey

    init {
        val pem = privateKeyPem.trim()
        if (pem.isEmpty()) {
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(KEY_BITS) }.generateKeyPair()
            privateKey = pair.private as RSAPrivateKey
            publicKey = pair.public as RSAPublicKey
            generated = true
        } else {
            privateKey = parsePkcs8(pem)
            publicKey = derivePublic(privateKey)
            generated = false
        }
        kid = keyId(publicKey)
        rsaKey = RSAKey.Builder(publicKey)
            .privateKey(privateKey)
            .keyUse(KeyUse.SIGNATURE)
            .algorithm(JWSAlgorithm.RS256)
            .keyID(kid)
            .build()
        publicJwk = rsaKey.toPublicJWK()
        if (generated) {
            log.warn("DH_JWT_PRIVATE_KEY_PEM 이 비어 있어 개발용 RSA 키를 생성했어요. 재시작하면 기존 토큰이 모두 무효가 돼요. kid={}", kid)
        } else {
            log.info("JWT 서명 키 로드 완료 kid={} bits={}", kid, publicKey.modulus.bitLength())
        }
    }

    /** 공개키만 담은 JWK Set JSON 문자열 (개인키 성분 d/p/q 없음) */
    fun jwkSetJson(): String = JWKSet(publicJwk).toString(true)

    private fun parsePkcs8(pem: String): RSAPrivateKey {
        require(!pem.contains("BEGIN RSA PRIVATE KEY")) {
            "DH_JWT_PRIVATE_KEY_PEM 은 PKCS#8(-----BEGIN PRIVATE KEY-----) 형식이어야 해요. " +
                "PKCS#1 키는 `openssl pkcs8 -topk8 -nocrypt` 로 변환해 주세요."
        }
        val body = pem
            .replace("\\n", "\n")
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .filterNot { it.isWhitespace() }
        val der = try {
            Base64.getDecoder().decode(body)
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException("DH_JWT_PRIVATE_KEY_PEM 의 Base64 본문을 읽을 수 없어요 (length=${body.length}).", e)
        }
        val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
        return key as? RSAPrivateKey ?: throw IllegalStateException("DH_JWT_PRIVATE_KEY_PEM 은 RSA 키여야 해요.")
    }

    private fun derivePublic(privateKey: RSAPrivateKey): RSAPublicKey {
        val crt = privateKey as? RSAPrivateCrtKey
            ?: throw IllegalStateException("RSA 개인키에서 공개 지수를 읽을 수 없어요 (CRT 형식이 아님).")
        val spec = RSAPublicKeySpec(crt.modulus, crt.publicExponent)
        return KeyFactory.getInstance("RSA").generatePublic(spec) as RSAPublicKey
    }

    companion object {
        private val log = LoggerFactory.getLogger(JwtKeyProvider::class.java)
        const val KEY_BITS = 2048
        const val KID_HEX_LENGTH = 16

        fun keyId(publicKey: RSAPublicKey): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(publicKey.encoded)
            return digest.joinToString("") { "%02x".format(it) }.substring(0, KID_HEX_LENGTH)
        }
    }
}
