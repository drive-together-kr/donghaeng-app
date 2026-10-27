package app.donghaeng.common.config

import app.donghaeng.protocol.DhJson
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.converter.json.KotlinSerializationJsonHttpMessageConverter

/**
 * REST 본문 직렬화 (CLAUDE.md 규칙 7).
 *
 * protocol 의 `@Serializable` DTO(RestDtos.kt)는 kotlinx-serialization 컨버터가, 그 외(`ApiError`, actuator)는 Jackson 이 맡는다.
 * Spring MVC 기본 순서가 Kotlin 컨버터 → Jackson 이고 Kotlin 컨버터는 `@Serializable` 타입만 받으므로 자연히 나뉜다.
 * `Json` 인스턴스는 car-app·companion/sdk 와 같은 [DhJson.json] (ignoreUnknownKeys, encodeDefaults, explicitNulls=false).
 * Spring Boot 는 같은 클래스의 기본 컨버터 앞에 이 빈을 끼워 넣는다.
 */
@Configuration
class HttpJsonConfig {

    @Bean
    fun kotlinSerializationJsonHttpMessageConverter(): KotlinSerializationJsonHttpMessageConverter =
        KotlinSerializationJsonHttpMessageConverter(DhJson.json)
}
