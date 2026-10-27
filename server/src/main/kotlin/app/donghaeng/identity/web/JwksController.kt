package app.donghaeng.identity.web

import app.donghaeng.identity.jwt.JwtKeyProvider
import app.donghaeng.protocol.rest.ApiPaths
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * `GET /.well-known/jwks.json` — 서명 공개키 (docs/06 §5·§6). EMQX 가 30초마다 받아가 차량 JWT 를 검증하고,
 * 클라이언트·테스트도 같은 키로 토큰 서명을 확인할 수 있다. 공개 부분(kty, use, alg, kid, n, e)만 내보낸다.
 */
@RestController
class JwksController(keys: JwtKeyProvider) {

    // 키는 기동 중 바뀌지 않으므로 한 번만 직렬화한다
    private val body: String = keys.jwkSetJson()

    @GetMapping(ApiPaths.JWKS, produces = [MediaType.APPLICATION_JSON_VALUE])
    fun jwks(): String = body
}
