package app.donghaeng.common.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/** 시간 의존 코드(토큰 만료, 디바이스 코드 만료, 폴링 제한)가 주입받는 시계. 테스트에서는 고정 시계로 바꿔 끼운다. */
@Configuration
class ClockConfig {

    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
