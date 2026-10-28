package app.donghaeng.car.core.network

import app.donghaeng.car.core.common.TokenStore
import app.donghaeng.car.core.common.TokenStoreState
import app.donghaeng.protocol.rest.ApiPaths
import okhttp3.Interceptor
import okhttp3.Response

/**
 * 차량 JWT 를 `Authorization: Bearer` 로 붙인다 — 인증이 필요한 경로(`/v1/groups/…`, `/v1/me/…`)에만.
 * 토큰은 [TokenStore] 의 현재 상태에서 읽는다(디스크 I/O 없음; 저장소는 앱 시작 때 로드된다). 없으면 헤더 없이 보내 서버가 401 을 주게 둔다.
 * 토큰 만료(24h) 대비: `TokenRefresher` 가 만료 2시간 전에 선제 갱신하고, 그래도 401 이면 [VehicleTokenAuthenticator] 가 한 번 갱신·재시도한다(T33).
 */
class VehicleAuthInterceptor(private val tokenStore: TokenStore) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val path = request.url.encodedPath
        val needsAuth = path.startsWith(ApiPaths.GROUPS) || path.startsWith("${ApiPaths.BASE}/me")
        if (!needsAuth || request.header("Authorization") != null) return chain.proceed(request)
        val token = (tokenStore.state.value as? TokenStoreState.Present)?.credentials?.vehicleAccessToken
            ?: return chain.proceed(request)
        return chain.proceed(request.newBuilder().header("Authorization", "Bearer $token").build())
    }
}
