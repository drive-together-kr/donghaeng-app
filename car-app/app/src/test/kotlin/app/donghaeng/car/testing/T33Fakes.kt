package app.donghaeng.car.testing

import app.donghaeng.car.core.common.TokenStore
import app.donghaeng.car.core.network.IdentityApi
import app.donghaeng.car.domain.pairing.TokenRefresher
import app.donghaeng.protocol.rest.DeviceCodeRequest
import app.donghaeng.protocol.rest.DeviceCodeResponse
import app.donghaeng.protocol.rest.DeviceTokenRequest
import app.donghaeng.protocol.rest.DeviceTokenResponse
import app.donghaeng.protocol.rest.RefreshRequest
import app.donghaeng.protocol.rest.TokenResponse
import app.donghaeng.protocol.rest.UserDto
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response
import java.util.Base64

/** T33 테스트 공용 — refresh 만 대본대로 답하는 IdentityApi */
class FakeIdentityApi : IdentityApi {
    val refreshCalls = mutableListOf<RefreshRequest>()

    /** 기본: 호출되면 실패(대본 없음) */
    var refreshHandler: suspend (RefreshRequest) -> Response<TokenResponse> = { error("refresh 대본 없음") }

    override suspend fun requestDeviceCode(body: DeviceCodeRequest): Response<DeviceCodeResponse> = error("device/code 대본 없음")
    override suspend fun requestDeviceToken(body: DeviceTokenRequest): Response<DeviceTokenResponse> = error("device/token 대본 없음")

    override suspend fun refresh(body: RefreshRequest): Response<TokenResponse> {
        refreshCalls += body
        return refreshHandler(body)
    }

    companion object {
        fun ok(accessToken: String, refreshToken: String): Response<TokenResponse> =
            Response.success(TokenResponse(accessToken, refreshToken, UserDto("usr_B", "소유자")))

        fun unauthorized(): Response<TokenResponse> = Response.error(
            401,
            """{"error":{"code":"INVALID_TOKEN","message":"토큰이 유효하지 않아요.","traceId":"t"}}""".toResponseBody("application/json".toMediaType()),
        )

        /** 동행 서버가 아닌 곳(프록시·캡티브 포털)의 401 — 오류 봉투 없음 */
        fun foreignUnauthorized(): Response<TokenResponse> = Response.error(
            401,
            "<html>401 Authorization Required</html>".toResponseBody("text/html".toMediaType()),
        )
    }
}

/** 서명 없는 테스트용 JWT(header.payload.sig) — exp/iat 는 초 */
fun fakeJwt(expSec: Long, iatSec: Long = expSec - 24 * 3600, jti: String = "j"): String {
    val enc = Base64.getUrlEncoder().withoutPadding()
    val header = enc.encodeToString("""{"alg":"RS256","typ":"JWT"}""".toByteArray())
    val payload = enc.encodeToString("""{"sub":"veh_B","typ":"vehicle","iat":$iatSec,"exp":$expSec,"jti":"$jti"}""".toByteArray())
    return "$header.$payload.sig"
}

/** 테스트용 갱신기(가짜 API·가짜 시계) */
fun testRefresher(tokens: TokenStore, api: IdentityApi = FakeIdentityApi(), clock: () -> Long = { 1_700_000_000_000L }): TokenRefresher =
    TokenRefresher(api, tokens, clock)
