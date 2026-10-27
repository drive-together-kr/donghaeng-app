package app.donghaeng.common.web

import app.donghaeng.common.config.ClockConfig
import app.donghaeng.common.config.DhProperties
import app.donghaeng.common.config.HttpJsonConfig
import app.donghaeng.common.security.SecurityConfig
import app.donghaeng.identity.jwt.JwtDecoderConfig
import app.donghaeng.identity.jwt.JwtIssuer
import app.donghaeng.identity.jwt.JwtKeyProvider
import app.donghaeng.identity.web.JwksController
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

/** 웹 계층 슬라이스 테스트: JWKS, JWT 리소스 서버 보안 동작, QR 웹 폴백 페이지. DB·Redis 불필요 (키는 기동 시 생성). */
@WebMvcTest(controllers = [JwksController::class, LandingController::class, TimeController::class])
@Import(SecurityConfig::class, HttpJsonConfig::class, JwtDecoderConfig::class, JwtKeyProvider::class, JwtIssuer::class, ClockConfig::class)
@EnableConfigurationProperties(DhProperties::class)
class WebLayerTest {

    @Autowired
    lateinit var mvc: MockMvc

    @Autowired
    lateinit var keys: JwtKeyProvider

    @Autowired
    lateinit var issuer: JwtIssuer

    @Test
    fun `jwks exposes one RSA signing key without private parts`() {
        mvc.get("/.well-known/jwks.json").andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            jsonPath("$.keys.length()") { value(1) }
            jsonPath("$.keys[0].kty") { value("RSA") }
            jsonPath("$.keys[0].use") { value("sig") }
            jsonPath("$.keys[0].alg") { value("RS256") }
            jsonPath("$.keys[0].kid") { value(keys.kid) }
            jsonPath("$.keys[0].n") { isNotEmpty() }
            jsonPath("$.keys[0].e") { value("AQAB") }
            jsonPath("$.keys[0].d") { doesNotExist() }
            jsonPath("$.keys[0].p") { doesNotExist() }
        }
    }

    @Test
    fun `GET v1 time is public and returns the server clock in epoch ms`() {
        val before = System.currentTimeMillis() - 5_000
        mvc.get("/v1/time").andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            jsonPath("$.serverTimeMs") { value(org.hamcrest.Matchers.greaterThan(before)) }
        }
    }

    @Test
    fun `405 keeps the Allow header and uses the docs-03 error format`() {
        mvc.post("/.well-known/jwks.json").andExpect {
            status { isMethodNotAllowed() }
            header { string("Allow", containsString("GET")) }
            jsonPath("$.error.code") { value("METHOD_NOT_ALLOWED") }
            jsonPath("$.error.traceId") { isNotEmpty() }
        }
    }

    @Test
    fun `unknown v1 path without token is rejected by security`() {
        mvc.get("/v1/nope").andExpect {
            status { isUnauthorized() }
            header { string(HttpHeaders.WWW_AUTHENTICATE, containsString("Bearer")) }
            jsonPath("$.error.code") { value("UNAUTHORIZED") }
            jsonPath("$.error.traceId") { isNotEmpty() }
        }
    }

    @Test
    fun `garbage bearer token is 401 INVALID_TOKEN in the docs-03 error format`() {
        mvc.get("/v1/nope") { header(HttpHeaders.AUTHORIZATION, "Bearer not.a.jwt") }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.error.code") { value("INVALID_TOKEN") }
            jsonPath("$.error.traceId") { isNotEmpty() }
        }
    }

    @Test
    fun `valid user JWT passes security and reaches the 404 handler`() {
        val token = issuer.userToken("usr_01TEST")
        mvc.get("/v1/nope") { header(HttpHeaders.AUTHORIZATION, "Bearer $token") }.andExpect {
            status { isNotFound() }
            jsonPath("$.error.code") { value("NOT_FOUND") }
        }
    }

    @Test
    fun `role rules - vehicle token cannot use user paths and vice versa`() {
        val vehicle = issuer.vehicleToken("veh_01TEST", "usr_01OWNER")
        mvc.get("/v1/me/vehicles") { header(HttpHeaders.AUTHORIZATION, "Bearer $vehicle") }.andExpect {
            status { isForbidden() }
            jsonPath("$.error.code") { value("FORBIDDEN") }
            jsonPath("$.error.traceId") { isNotEmpty() }
        }
        mvc.post("/v1/device/approve") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $vehicle")
            contentType = MediaType.APPLICATION_JSON
            content = """{"userCode":"KQ74MZ","nickname":"x"}"""
        }.andExpect { status { isForbidden() } }

        val user = issuer.userToken("usr_01TEST")
        mvc.post("/v1/groups") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $user")
            contentType = MediaType.APPLICATION_JSON
            content = "{}"
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.error.code") { value("FORBIDDEN") }
        }
    }

    @Test
    @WithMockUser
    fun `unknown v1 path with auth is 404 in the docs-03 error format`() {
        mvc.get("/v1/nope").andExpect {
            status { isNotFound() }
            jsonPath("$.error.code") { value("NOT_FOUND") }
            jsonPath("$.error.message") { isNotEmpty() }
            jsonPath("$.error.traceId") { isNotEmpty() }
        }
    }

    @Test
    fun `landing pages are public and escape user input`() {
        mvc.get("/j/{token}", "inv_<script>x").andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith("text/html") }
            // 재설계 이후 안내 문구는 "공식 앱의 '동행' 메뉴"를 가리킨다(docs/07 §5) — 별도 앱 설치 안내가 아니다
            content { string(containsString("'동행' 메뉴")) }
            content { string(not(containsString("앱을 설치"))) }
            content { string(containsString("&lt;script&gt;")) }
            content { string(not(containsString("<script>"))) }
        }
        mvc.get("/pair") { param("c", "KQ74MZ") }.andExpect {
            status { isOk() }
            content { string(containsString("KQ74MZ")) }
            content { string(containsString("'동행' 메뉴")) }
        }
    }
}
