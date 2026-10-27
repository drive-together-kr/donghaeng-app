package app.donghaeng.common.web

import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock

/**
 * `GET /v1/time` — 서버 시각(epoch ms), 인증 없음 (docs/03 §0, T31).
 * 차량 앱 `ClockSync` 가 왕복시간이 가장 짧은 표본으로 시계 오프셋을 잡아 메시지 지연(ms)·음성 신선도를 서버 시계 기준으로 계산한다.
 */
@RestController
class TimeController(private val clock: Clock) {

    data class ServerTimeResponse(val serverTimeMs: Long)

    @GetMapping("/v1/time", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun time(): ServerTimeResponse = ServerTimeResponse(clock.millis())
}
