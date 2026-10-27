package app.donghaeng.common.web

import app.donghaeng.common.security.SecurityConfig
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

/** 웹 계층 슬라이스 테스트: JWKS 플레이스홀더, 보안 기본 동작, QR 웹 폴백 페이지. DB 불필요. */
@WebMvcTest(controllers = [WellKnownController::class, LandingController::class])
@Import(SecurityConfig::class)
class WebLayerTest {

    @Autowired
    lateinit var mvc: MockMvc

    @Test
    fun `jwks placeholder returns empty key set`() {
        mvc.get("/.well-known/jwks.json").andExpect {
            status { isOk() }
            content { json("""{"keys":[]}""", strict = true) }
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
            jsonPath("$.error.code") { value("UNAUTHORIZED") }
            jsonPath("$.error.traceId") { isNotEmpty() }
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
