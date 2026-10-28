package app.donghaeng.common.security

import app.donghaeng.common.api.ApiError
import app.donghaeng.protocol.rest.ErrorCodes
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.provisioning.InMemoryUserDetailsManager
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.AccessDeniedHandler

/**
 * 보안 설정 — JWT 리소스 서버 (docs/06 §5).
 *
 * - 무상태(stateless), CSRF 비활성, 기본 로그인(httpBasic/formLogin) 비활성
 * - `Authorization: Bearer <JWT>` → identity 의 `JwtDecoder` 빈(RS256, iss=donghaeng)으로 검증.
 *   클레임 `typ` 이 `user` 면 ROLE_USER, `vehicle` 이면 ROLE_VEHICLE. principal 이름 = `sub`(usr_… / veh_…).
 * - 공개 경로: 헬스체크, JWKS, QR 웹 폴백(/pair, /j/{token}), 로그인·교환·갱신, 디바이스 코드 발급·폴링, /error
 * - `/v1/me/` 아래 전부, `POST /v1/device/approve`, `POST /v1/groups/join` 은 사용자 토큰만, `POST /v1/groups`·`POST /v1/groups/{id}/voice`(F16 무전 업로드) 는 차량 토큰만.
 *   그 외 그룹 경로(조회·나가기·종료·위임·초대·메시지·무전 다운로드)는 인증만 요구하고 `group`·`voice` 모듈이 멤버·방장·역할을 본다.
 *   무전 업로드를 여기서 먼저 막는 이유: 사용자 토큰이면 multipart 본문(최대 512 KB)을 해석하기 전에 403 으로 끝낸다.
 * - 미인증 401 / 토큰 무효 401 `INVALID_TOKEN` / 권한 부족 403 — 모두 docs/03 오류 형식(JSON)
 */
@Configuration
@EnableWebSecurity
class SecurityConfig(private val objectMapper: ObjectMapper) {

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        val entryPoint = unauthorizedEntryPoint()
        val deniedHandler = accessDeniedHandler()
        http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .logout { it.disable() }
            .exceptionHandling { it.authenticationEntryPoint(entryPoint).accessDeniedHandler(deniedHandler) }
            .oauth2ResourceServer { rs ->
                rs.jwt { it.jwtAuthenticationConverter(jwtAuthenticationConverter()) }
                rs.authenticationEntryPoint(entryPoint)
                rs.accessDeniedHandler(deniedHandler)
            }
            .authorizeHttpRequests {
                it.requestMatchers(*PUBLIC_PATHS).permitAll()
                    .requestMatchers("/v1/me/**", "/v1/device/approve", "/v1/groups/join").hasRole(ROLE_USER)
                    .requestMatchers(HttpMethod.POST, "/v1/groups").hasRole(ROLE_VEHICLE)
                    .requestMatchers(HttpMethod.POST, "/v1/groups/*/voice").hasRole(ROLE_VEHICLE)
                    .anyRequest().authenticated()
            }
        return http.build()
    }

    /** 비어 있는 사용자 저장소 — Spring Boot 가 임시 비밀번호를 생성·출력하지 않게 한다. */
    @Bean
    fun userDetailsService(): UserDetailsService = InMemoryUserDetailsManager()

    /** `typ` 클레임 → 역할. 다른 값(또는 없음)이면 역할 없음 → 역할이 필요한 경로는 403. */
    private fun jwtAuthenticationConverter(): JwtAuthenticationConverter =
        JwtAuthenticationConverter().apply {
            setJwtGrantedAuthoritiesConverter { jwt: Jwt ->
                val role = when (jwt.getClaimAsString(CLAIM_TYP)) {
                    TYP_USER -> ROLE_USER
                    TYP_VEHICLE -> ROLE_VEHICLE
                    else -> null
                }
                listOfNotNull<GrantedAuthority>(role?.let { SimpleGrantedAuthority("ROLE_$it") })
            }
        }

    private fun unauthorizedEntryPoint() = AuthenticationEntryPoint { _, response, ex ->
        // Bearer 토큰이 있었지만 무효(서명·만료·발급자)면 INVALID_TOKEN, 아예 없으면 UNAUTHORIZED
        val body = if (ex is OAuth2AuthenticationException) {
            ApiError.of(ErrorCodes.INVALID_TOKEN, "토큰이 유효하지 않아요. 다시 로그인해 주세요.")
        } else {
            ApiError.of(ErrorCodes.UNAUTHORIZED, "인증이 필요해요.")
        }
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
        writeJson(response, HttpStatus.UNAUTHORIZED, body)
    }

    private fun accessDeniedHandler() = AccessDeniedHandler { _, response, _ ->
        writeJson(response, HttpStatus.FORBIDDEN, ApiError.of(ErrorCodes.FORBIDDEN, "권한이 없어요."))
    }

    private fun writeJson(response: HttpServletResponse, status: HttpStatus, body: ApiError) {
        response.status = status.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        objectMapper.writeValue(response.writer, body)
    }

    companion object {
        /** JWT 클레임 이름·값 (identity/jwt/JwtIssuer 와 같은 값) */
        const val CLAIM_TYP = "typ"
        const val TYP_USER = "user"
        const val TYP_VEHICLE = "vehicle"
        const val ROLE_USER = "USER"
        const val ROLE_VEHICLE = "VEHICLE"

        val PUBLIC_PATHS = arrayOf(
            "/actuator/health",
            "/actuator/health/**",
            "/actuator/info",
            "/.well-known/jwks.json",
            "/pair",
            "/v1/time",
            "/j/**",
            "/v1/auth/login",
            "/v1/auth/exchange",
            "/v1/auth/refresh",
            "/v1/device/code",
            "/v1/device/token",
            // EMQX 인증·인가 훅 — 컨테이너 네트워크 안에서만 닿고, ACL 훅은 X-Internal-Token(dh.internal-token)으로 한 번 더 막는다
            "/internal/mqtt/acl",
            "/error",
        )
    }
}
