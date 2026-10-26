package app.donghaeng.common.security

import app.donghaeng.common.api.ApiError
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.provisioning.InMemoryUserDetailsManager
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain

/**
 * 보안 기본 설정 (T03 스켈레톤). JWT 리소스 서버 설정은 T10(identity)에서 추가한다.
 *
 * - 무상태(stateless), CSRF 비활성, 기본 로그인(httpBasic/formLogin) 비활성
 * - 공개 경로: 헬스체크, JWKS, QR 웹 폴백(/pair, /j/{token}), 인증·페어링 API, /error
 * - 그 외는 인증 필요 → 미인증 시 401 + docs/03 오류 형식
 */
@Configuration
@EnableWebSecurity
class SecurityConfig(private val objectMapper: ObjectMapper) {

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .logout { it.disable() }
            .exceptionHandling { it.authenticationEntryPoint(unauthorizedEntryPoint()) }
            .authorizeHttpRequests {
                it.requestMatchers(*PUBLIC_PATHS).permitAll()
                    .anyRequest().authenticated()
            }
        return http.build()
    }

    /** 비어 있는 사용자 저장소 — Spring Boot 가 임시 비밀번호를 생성·출력하지 않게 한다. */
    @Bean
    fun userDetailsService(): UserDetailsService = InMemoryUserDetailsManager()

    private fun unauthorizedEntryPoint() = AuthenticationEntryPoint { _, response, _ ->
        response.status = HttpStatus.UNAUTHORIZED.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        objectMapper.writeValue(response.writer, ApiError.of("UNAUTHORIZED", "인증이 필요해요."))
    }

    companion object {
        val PUBLIC_PATHS = arrayOf(
            "/actuator/health",
            "/actuator/health/**",
            "/actuator/info",
            "/.well-known/**",
            "/pair",
            "/j/**",
            "/v1/auth/**",
            "/v1/device/code",
            "/v1/device/token",
            "/error",
        )
    }
}
