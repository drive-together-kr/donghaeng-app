package app.donghaeng.companion.demo

import android.util.Log
import app.donghaeng.protocol.DhJson
import app.donghaeng.protocol.rest.TokenResponse
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.util.Base64
import kotlin.coroutines.cancellation.CancellationException

/**
 * 호스트 앱(공식 앱)의 계정 토큰 보관 + 만료 전 갱신(D023). 동행 모듈의 [app.donghaeng.companion.sdk.TokenProvider] 가 이것을 부른다.
 *
 * 모듈은 동행 JWT 를 교환할 때(캐시 없음·만료 30 s 전·401)만 호스트 토큰을 묻는다. dev 로그인 토큰은 1시간짜리라(서버 USER_TOKEN_TTL)
 * 그대로 두면 로그인 약 2시간 뒤 두 번째 교환이 만료 토큰으로 401 → 모든 동행 호출이 `Unauthorized` 로 멈춘다.
 * 그래서 물을 때마다 JWT `exp`(서명 검증 없이 payload 만)를 보고 [renewBeforeMs] 안으로 들어왔으면 [reissue](데모: 같은 이메일로 dev 로그인 재발급 —
 * 실제 공식 앱이면 자기 OAuth refresh)로 바꾼다. 갱신 실패:
 * - 지금 토큰이 아직 유효하면 그것을 돌려준다(다음 교환 때 다시 시도)
 * - 만료됐고 일시 오류(연결 실패·타임아웃, 서버 5xx — 재배포 중 Caddy 502 등, 429 요청 제한)면 [IOException] 을 던진다
 *   → 모듈이 `Api("NETWORK")`(일시 오류)로 바꿔 관찰이 백오프로 다시 시도한다(RD-3 — 예전에는 5xx·429 도 '거부'로 보아 다시 로그인 안내에서 멈췄다)
 * - 만료됐고 서버가 거부했으면(403·400 등) 빈 문자열 → 모듈이 `Unauthorized`(다시 로그인 안내)
 *
 * 토큰은 메모리에만 둔다. 로그에는 남은 시간(초)만(규칙 8).
 *
 * @param reissue 새 토큰 쌍을 받는다. 실패는 예외 — 일시 오류는 [IOException], cause 가 IOException, 또는 [DevAuthClient.DevAuthException.transient]
 */
class HostTokenCache(
    initial: TokenResponse,
    private val reissue: suspend () -> TokenResponse,
    private val clock: () -> Long = System::currentTimeMillis,
    private val renewBeforeMs: Long = RENEW_BEFORE_MS,
) {
    private val mutex = Mutex()

    @Volatile
    private var tokens: TokenResponse? = initial

    /** 로그아웃 — 이후 [accessToken] 은 빈 문자열(모듈 → Unauthorized). 진행 중인 갱신 결과도 버린다 */
    fun clear() {
        tokens = null
    }

    suspend fun accessToken(): String = mutex.withLock {
        val held = tokens ?: return@withLock ""
        val expMs = JwtExp.expiresAtMs(held.accessToken) ?: return@withLock held.accessToken // 형식을 모르면 그대로(서버가 판단)
        val now = clock()
        if (now < expMs - renewBeforeMs) return@withLock held.accessToken
        val fresh = try {
            reissue()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val transient = isTransient(e)
            val status = (e as? DevAuthClient.DevAuthException)?.status
            Log.w(TAG, "host token renew failed transient=$transient status=$status leftS=${(expMs - now) / 1000} cause=${e.javaClass.simpleName}")
            return@withLock when {
                now < expMs -> held.accessToken
                transient -> throw IOException("host token renew failed", e)
                else -> ""
            }
        }
        if (tokens !== held) return@withLock tokens?.accessToken.orEmpty() // 그 사이 로그아웃·다른 로그인
        tokens = fresh
        Log.i(TAG, "host token renewed ttlS=${JwtExp.expiresAtMs(fresh.accessToken)?.let { (it - clock()) / 1000 }}")
        fresh.accessToken
    }

    companion object {
        private const val TAG = DemoApplication.TAG

        /** 다시 받기 실패가 일시 오류인지(→ IOException 으로 모듈에 넘겨 백오프 재시도) */
        internal fun isTransient(e: Exception): Boolean =
            e is IOException || e.cause is IOException || (e is DevAuthClient.DevAuthException && e.transient)

        /** 만료 2분 전부터 갱신 — 모듈의 교환 여유(30 s)와 서버 시계 허용(60 s)보다 크게 */
        const val RENEW_BEFORE_MS = 2 * 60 * 1000L
    }
}

/** JWT `exp`(초) → epoch ms. 서명 검증 없이 payload 만 읽는다(검증은 서버 몫). JWT 가 아니면 null */
object JwtExp {
    fun expiresAtMs(token: String): Long? {
        val parts = token.split('.')
        if (parts.size < 2) return null
        val payload = runCatching { String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8) }.getOrNull() ?: return null
        val obj = runCatching { DhJson.json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return null
        val expS = runCatching { obj["exp"]?.jsonPrimitive?.longOrNull }.getOrNull() ?: return null
        return expS * 1000L
    }
}
