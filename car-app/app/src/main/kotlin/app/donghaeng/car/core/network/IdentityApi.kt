package app.donghaeng.car.core.network

import app.donghaeng.protocol.rest.ApiPaths
import app.donghaeng.protocol.rest.DeviceCodeRequest
import app.donghaeng.protocol.rest.DeviceCodeResponse
import app.donghaeng.protocol.rest.DeviceTokenRequest
import app.donghaeng.protocol.rest.DeviceTokenResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * identity 모듈 REST (docs/03 §1.2 Device Authorization Grant). DTO 는 `protocol/` 의 것만 쓴다(CLAUDE.md 규칙 7).
 *
 * 경로는 [ApiPaths] 상수(앞에 `/` 가 있어 base URL 의 호스트 루트에 붙는다: `http://10.0.2.2:8080` + `/v1/device/code`).
 * 응답은 `Response<T>` 로 받아 [safeApiCall] 이 428/429/410/404 를 [ApiResult.HttpFailure] 로 나눈다.
 *
 * TODO(T17): 그룹 생성·조회(`ApiPaths.GROUPS`, 차량 JWT `Authorization: Bearer`) 는 별도 GroupApi 로.
 */
interface IdentityApi {

    /** 인증 없음. 200 [DeviceCodeResponse] */
    @POST(ApiPaths.DEVICE_CODE)
    suspend fun requestDeviceCode(@Body body: DeviceCodeRequest): Response<DeviceCodeResponse>

    /** 폴링. 200 [DeviceTokenResponse] | 428 AUTHORIZATION_PENDING | 429 SLOW_DOWN | 410 CODE_EXPIRED | 404 CODE_NOT_FOUND */
    @POST(ApiPaths.DEVICE_TOKEN)
    suspend fun requestDeviceToken(@Body body: DeviceTokenRequest): Response<DeviceTokenResponse>
}
