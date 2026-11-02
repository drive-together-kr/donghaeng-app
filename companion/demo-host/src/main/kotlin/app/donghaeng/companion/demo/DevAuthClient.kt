package app.donghaeng.companion.demo

import android.util.Log
import app.donghaeng.protocol.DhJson
import app.donghaeng.protocol.rest.ApiHeaders
import app.donghaeng.protocol.rest.ApiPaths
import app.donghaeng.protocol.rest.ErrorEnvelope
import app.donghaeng.protocol.rest.LoginRequest
import app.donghaeng.protocol.rest.TokenResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 호스트 앱(공식 앱) 쪽의 로그인 — 데모에서는 서버의 dev 간편 로그인 `POST /v1/auth/login {provider:"dev", email, displayName}` 다(docs/03 §1.1).
 * 동행 모듈의 일이 아니라 호스트가 직접 부른다(모듈은 자체 로그인이 없다, CLAUDE.md 규칙 13). 응답의 accessToken 이 모듈 [app.donghaeng.companion.sdk.TokenProvider] 로
 * 넘어가고, 모듈이 `POST /v1/auth/exchange` 로 동행 JWT 와 교환한다. 실제 공식 앱이라면 이 자리가 자기 OAuth 2.0 로그인이다.
 *
 * [devLoginKey]: 클라우드 서버(T32)가 요구하는 dev 로그인 접속 키(`X-Dev-Login-Key`, docs/03 §1.1). 비어 있으면 헤더를 보내지 않는다(로컬 서버).
 */
class DevAuthClient(
    publicBaseUrl: String,
    private val devLoginKey: String = "",
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build(),
) {

    /**
     * 사용자에게 그대로 보여 줄 수 있는 한국어 문구를 담는다.
     * @param status 서버가 응답한 HTTP 상태(2xx 아님). 연결 실패·응답 해석 실패면 null(연결 실패는 [cause] 가 [IOException])
     */
    class DevAuthException(message: String, cause: Throwable? = null, val status: Int? = null) : Exception(message, cause) {
        /**
         * 잠시 뒤 다시 하면 될 실패인지 — 연결 실패·타임아웃, 서버 일시 오류(5xx — 재배포 중 Caddy 502 등), 요청 제한(429).
         * 나머지(403 접속 키·400 형식·404 경로 없음·본문 해석 실패)는 다시 해도 같다(RD-3).
         */
        val transient: Boolean
            get() = cause is IOException || (status != null && (status >= 500 || status == 429))
    }

    private val baseUrl = publicBaseUrl.trim().trimEnd('/')

    /**
     * dev 로그인. 성공하면 토큰 쌍(메모리에만 보관할 것), 실패하면 [DevAuthException].
     * 토큰·이메일은 로그에 남기지 않는다(CLAUDE.md 규칙 8).
     */
    suspend fun login(email: String, displayName: String): TokenResponse = withContext(Dispatchers.IO) {
        val body = DhJson.json.encodeToString(LoginRequest.serializer(), LoginRequest(provider = "dev", email = email, displayName = displayName))
        val request = Request.Builder()
            .url(baseUrl + ApiPaths.AUTH_LOGIN)
            .header("Accept", "application/json")
            .apply { if (devLoginKey.isNotBlank()) header(ApiHeaders.DEV_LOGIN_KEY, devLoginKey.trim()) }
            .post(body.toRequestBody(JSON))
            .build()
        val (code, text) = try {
            client.newCall(request).execute().use { response -> response.code to response.body?.string().orEmpty() }
        } catch (e: IOException) {
            Log.w(DemoApplication.TAG, "dev login network failure base=$baseUrl cause=${e.javaClass.simpleName}")
            throw DevAuthException("서버에 연결할 수 없어요 ($baseUrl). 서버와 네트워크를 확인해 주세요", e)
        }
        if (code !in 200..299) {
            val envelope = runCatching { DhJson.json.decodeFromString(ErrorEnvelope.serializer(), text) }.getOrNull()?.error
            Log.w(DemoApplication.TAG, "dev login failed status=$code code=${envelope?.code} trace=${envelope?.traceId}")
            throw DevAuthException(
                when {
                    code == 404 -> "서버에 dev 로그인(/v1/auth/login)이 아직 없어요. 서버(T10)가 배포되면 다시 시도해 주세요"
                    code == 403 && devLoginKey.isBlank() -> "이 서버는 dev 로그인 접속 키가 필요해요. -PdhDevLoginKey=<키> 로 다시 빌드해 주세요 (infra/cloud/README.md §3)"
                    envelope != null -> "${envelope.message} [${envelope.code}]"
                    else -> "로그인에 실패했어요 (HTTP $code)"
                },
                status = code,
            )
        }
        val tokens = try {
            DhJson.json.decodeFromString(TokenResponse.serializer(), text)
        } catch (e: Exception) {
            Log.w(DemoApplication.TAG, "dev login bad body len=${text.length} cause=${e.javaClass.simpleName}")
            throw DevAuthException("로그인 응답을 읽을 수 없어요", e)
        }
        Log.i(DemoApplication.TAG, "dev login ok user=${tokens.user.userId}")
        tokens
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
