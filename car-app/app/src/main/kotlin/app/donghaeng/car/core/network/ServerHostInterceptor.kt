package app.donghaeng.car.core.network

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

/**
 * 요청마다 scheme·host·port 를 지금 서버 주소([ServerConfig.publicBaseUrl])로 바꾼다 (T32 숨은 메뉴).
 * Retrofit 의 base URL 은 빌드 기본값([routedBaseUrl]) 그대로 두고 **그 주소로 가는 요청만** 바꾼다 — 경로·쿼리는 그대로.
 * Retrofit·API 인터페이스를 다시 만들지 않아도 되고, 저장 즉시 다음 요청부터 새 서버로 간다.
 */
class ServerHostInterceptor(
    private val serverConfig: ServerConfig,
    routedBaseUrl: String,
) : Interceptor {

    private val routed: HttpUrl? = routedBaseUrl.toHttpUrlOrNull()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val target = serverConfig.publicBaseUrl.toHttpUrlOrNull()
        val rewritten = rewrite(request.url, routed, target) ?: return chain.proceed(request)
        return chain.proceed(request.newBuilder().url(rewritten).build())
    }

    companion object {
        /** [url] 이 [from] 과 같은 origin 이고 [to] 가 다르면 origin 만 바꾼 URL, 아니면 null(그대로) */
        fun rewrite(url: HttpUrl, from: HttpUrl?, to: HttpUrl?): HttpUrl? {
            if (from == null || to == null) return null
            if (!sameOrigin(url, from) || sameOrigin(url, to)) return null
            return url.newBuilder().scheme(to.scheme).host(to.host).port(to.port).build()
        }

        private fun sameOrigin(a: HttpUrl, b: HttpUrl): Boolean = a.scheme == b.scheme && a.host == b.host && a.port == b.port
    }
}
