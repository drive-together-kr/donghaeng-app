package app.donghaeng.common.config

import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

/** 주기 작업(presence 2s 스냅샷, insight 10s 규칙) 활성화 (docs/06 §3) */
@Configuration
@EnableScheduling
class SchedulingConfig
