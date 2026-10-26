package app.donghaeng

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

/**
 * 동행 서버 진입점.
 * 모듈러 모놀리스 — 패키지(identity, group, presence, ...)가 미래의 마이크로서비스 경계다 (docs/06 §2).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
class DonghaengServerApplication

fun main(args: Array<String>) {
    runApplication<DonghaengServerApplication>(*args)
}
