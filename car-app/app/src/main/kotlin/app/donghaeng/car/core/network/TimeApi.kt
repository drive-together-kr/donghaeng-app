package app.donghaeng.car.core.network

import kotlinx.serialization.Serializable
import retrofit2.http.GET

/** `GET /v1/time` 응답 (docs/03 §0) — 서버 시각 epoch ms */
@Serializable
data class ServerTimeResponse(val serverTimeMs: Long)

/**
 * 서버 시각 조회 (T31 `ClockSync`). 인증 없음.
 * 차량 시계는 서버와 다를 수 있어(에뮬레이터 실측: 호스트보다 1.2~1.9 s 늦음, 표시 시간대도 다름) 메시지 지연(ms)·신선도 판정은 이 오프셋으로 보정한다.
 */
interface TimeApi {
    @GET("v1/time")
    suspend fun time(): ServerTimeResponse
}
