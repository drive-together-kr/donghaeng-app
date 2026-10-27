package app.donghaeng.identity.jwt

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder

/**
 * 들어오는 Bearer JWT 검증기. 서명은 [JwtKeyProvider] 의 공개키(RS256)로, 발급자(`iss = donghaeng`)와 만료(`exp`, 60초 허용 오차)를 검사한다.
 * `common/security/SecurityConfig` 의 `oauth2ResourceServer { jwt }` 와 identity 의 `DevTokenVerifier` 가 같은 빈을 쓴다.
 */
@Configuration
class JwtDecoderConfig {

    @Bean
    fun jwtDecoder(keys: JwtKeyProvider): JwtDecoder =
        NimbusJwtDecoder.withPublicKey(keys.publicKey)
            .signatureAlgorithm(SignatureAlgorithm.RS256)
            .build()
            .apply { setJwtValidator(JwtValidators.createDefaultWithIssuer(JwtIssuer.ISSUER)) }
}
