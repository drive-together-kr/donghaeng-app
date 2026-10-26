package app.donghaeng.common.web

import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * JWKS 엔드포인트. EMQX 가 JWT 공개키를 받아가는 URL (docs/06 §6).
 * T03 에서는 빈 키 목록을 돌려주고, T10(identity)에서 실제 RSA 키로 교체한다.
 */
@RestController
class WellKnownController {

    @GetMapping("/.well-known/jwks.json", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun jwks(): Map<String, List<Any>> = mapOf("keys" to emptyList())
}
