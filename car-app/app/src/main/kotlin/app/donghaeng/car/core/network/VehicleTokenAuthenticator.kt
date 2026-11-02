package app.donghaeng.car.core.network

import app.donghaeng.car.core.common.DhLog
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/**
 * REST 401 → 차량 토큰을 **한 번** 갱신하고 같은 요청을 새 Bearer 로 다시 보낸다 (T33, OkHttp [Authenticator]).
 *
 * - 제외: `/v1/auth/…`(갱신 자체), `/v1/device/…`(페어링 — 토큰 없음), `DELETE /v1/vehicles/me`(스스로 해제 — 401 은 '이미 해제됨'이라
 *   성공으로 본다. 갱신하면 거부(INVALID_TOKEN)로 자격 증명이 다른 사유로 먼저 지워진다). 우리가 Bearer 를 붙이지 않은 요청도 제외.
 * - `priorResponse` 가 있으면(이미 재시도한 응답) 더 하지 않는다 → 무한 루프 없음.
 * - [refreshBlocking] 은 거부된 토큰을 받아 새 access token(또는 null)을 돌려준다. 실제 구현은 `TokenRefresher` 를 `runBlocking` 으로 부른다:
 *   single-flight(Mutex)라 동시에 여러 요청이 401 을 받아도 서버 갱신은 한 번이고, 갱신 호출은 **이 Authenticator 가 없는 별도 OkHttpClient**
 *   (자기 Dispatcher)로 나가 OkHttp 스레드가 막혀도 교착하지 않는다.
 * - 토큰·헤더 원문은 로그에 남기지 않는다(규칙 8).
 */
class VehicleTokenAuthenticator(
    private val refreshBlocking: (rejectedAccessToken: String) -> String?,
) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        val request = response.request
        val path = request.url.encodedPath
        if (EXCLUDED_PREFIXES.any { path.startsWith(it) }) return null
        if (request.method == "DELETE" && path == VehicleSelfApi.ME) return null
        if (response.priorResponse != null) {
            DhLog.w("http 401 again after token refresh path=$path → give up")
            return null
        }
        val header = request.header(HEADER) ?: return null
        if (!header.startsWith(BEARER)) return null
        val rejected = header.removePrefix(BEARER).trim()
        DhLog.i("http 401 path=$path → refreshing vehicle token")
        val fresh = runCatching { refreshBlocking(rejected) }
            .onFailure { DhLog.w("token refresh from authenticator failed cause=${it.javaClass.simpleName}") }
            .getOrNull()
            ?: return null
        if (fresh == rejected) return null
        return request.newBuilder().header(HEADER, BEARER + fresh).build()
    }

    companion object {
        const val HEADER = "Authorization"
        const val BEARER = "Bearer "
        val EXCLUDED_PREFIXES = listOf("/v1/auth/", "/v1/device/")
    }
}
