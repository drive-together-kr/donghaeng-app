package app.donghaeng.car.domain.pairing

import app.donghaeng.car.core.common.DhLog
import app.donghaeng.car.core.common.JwtClaims
import app.donghaeng.car.core.common.StoredCredentials
import app.donghaeng.car.core.common.TextMasking
import app.donghaeng.car.core.common.TokenClearReason
import app.donghaeng.car.core.common.TokenStore
import app.donghaeng.car.core.common.TokenStoreState
import app.donghaeng.car.core.network.ApiResult
import app.donghaeng.car.core.network.IdentityApi
import app.donghaeng.car.core.network.RefreshClient
import app.donghaeng.car.core.network.safeApiCall
import app.donghaeng.protocol.rest.RefreshRequest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** 갱신을 부른 곳 — 로그·진단용 */
enum class RefreshTrigger { STARTUP, PERIODIC, HTTP_401, MQTT_AUTH, MANUAL }

/** 갱신 시도 결과 */
sealed interface RefreshOutcome {
    /** 새 토큰(또는 다른 호출이 방금 바꿔 둔 최신 토큰) */
    data class Refreshed(val accessToken: String, val rotatedNow: Boolean) : RefreshOutcome {
        override fun toString(): String = "Refreshed(token=${TextMasking.maskToken(accessToken)}, rotatedNow=$rotatedNow)"
    }

    /** 만료까지 여유가 있다(선제 갱신 불필요) */
    data object NotNeeded : RefreshOutcome

    /** 페어링돼 있지 않다 */
    data object NotPaired : RefreshOutcome

    /** 방금 갱신했는데도 같은 토큰이 거부됐다 — 갱신을 반복하지 않는다(서버·브로커 쪽 문제) */
    data object Throttled : RefreshOutcome

    /** 서버가 refresh 토큰을 거부(401) → 자격 증명을 [TokenClearReason.REFRESH_REJECTED] 로 지웠다 */
    data object Rejected : RefreshOutcome

    /** 네트워크·5xx 등 — 토큰은 그대로 두고 다음 기회에 다시 */
    data class Failed(val summary: String) : RefreshOutcome
}

val RefreshOutcome.accessTokenOrNull: String? get() = (this as? RefreshOutcome.Refreshed)?.accessToken

/** 진단 화면용 상태(토큰 원문 없음) */
data class TokenRefreshState(
    val running: Boolean = false,
    /** 현재 차량 토큰의 exp(epoch ms). 못 읽으면 null */
    val accessExpiresAtMs: Long? = null,
    val lastAttemptAtMs: Long? = null,
    val lastSuccessAtMs: Long? = null,
    /** 마지막 결과 한 줄(예: `OK MQTT_AUTH`, `FAILED network IOException`, `REJECTED HTTP_401`) */
    val lastResult: String? = null,
    val refreshes: Int = 0,
    val failures: Int = 0,
)

/**
 * 차량 토큰 갱신기 (T33, docs/03 §1.1 `POST /auth/refresh`). 차량 access token 은 24h 라 그대로 두면 REST 401·MQTT 인증 거부로
 * '연결됨' 인데 아무것도 안 되는 좀비 상태가 된다(docs/10 R13 조사).
 *
 * - **선제 갱신**: 앱 시작 직후와 [CHECK_INTERVAL_MS](30분)마다 JWT `exp` 를 보고(서명 검증 없음, [JwtClaims]) 남은 시간이 [REFRESH_AHEAD_MS](2시간) 이하면 갱신.
 * - **거부 후 갱신**: REST 401(OkHttp Authenticator) · MQTT CONNACK 인증 거부([refreshAfterRejection]).
 * - **single-flight**: [Mutex] 하나. 기다리는 동안 다른 호출이 이미 회전했으면(거부된 토큰 ≠ 현재 토큰) 서버를 다시 부르지 않고 최신 토큰을 돌려준다.
 * - 회전된 토큰 쌍은 [TokenStore.updateTokens] 로 **한 번에**, 보낸 refresh 토큰이 아직 저장돼 있을 때만 저장한다(갱신 도중 연결 해제가 되살아나지 않게).
 * - refresh 401 → [TokenStore.clear] ([TokenClearReason.REFRESH_REJECTED]) → 홈 '폰에서 다시 연결해 주세요'. 네트워크 실패는 토큰을 건드리지 않는다.
 */
@Singleton
class TokenRefresher internal constructor(
    private val api: IdentityApi,
    private val tokenStore: TokenStore,
    private val clock: () -> Long,
) {
    @Inject
    constructor(@RefreshClient api: IdentityApi, tokenStore: TokenStore) : this(api, tokenStore, System::currentTimeMillis)

    private val mutex = Mutex()
    private val started = AtomicBoolean(false)

    @Volatile
    private var loopScope: CoroutineScope? = null

    private val _state = MutableStateFlow(TokenRefreshState())
    val state: StateFlow<TokenRefreshState> = _state.asStateFlow()

    /** 한 번만(Application.onCreate). 저장소를 읽은 뒤 바로 한 번, 이후 30분마다 만료를 본다 */
    fun start(dispatcher: CoroutineDispatcher = Dispatchers.Default) {
        if (!started.compareAndSet(false, true)) return
        val handler = CoroutineExceptionHandler { _, e -> DhLog.e("token refresher loop failed", e) }
        val scope = CoroutineScope(SupervisorJob() + dispatcher + handler).also { loopScope = it }
        _state.update { it.copy(running = true) }
        scope.launch {
            runCatching { tokenStore.load() }
            var trigger = RefreshTrigger.STARTUP
            while (isActive) {
                val outcome = refreshIfExpiring(trigger)
                if (outcome !is RefreshOutcome.NotNeeded && outcome !is RefreshOutcome.NotPaired) DhLog.i("token refresh check trigger=$trigger → $outcome")
                trigger = RefreshTrigger.PERIODIC
                delay(CHECK_INTERVAL_MS)
            }
        }
        DhLog.i("token refresher started (ahead=${REFRESH_AHEAD_MS / 60_000}m, every ${CHECK_INTERVAL_MS / 60_000}m)")
    }

    /** 주기 점검을 멈춘다(테스트 정리용 — 앱은 프로세스가 사는 동안 계속 돈다) */
    internal fun stop() {
        loopScope?.cancel()
        loopScope = null
        _state.update { it.copy(running = false) }
    }

    /** 남은 시간이 [REFRESH_AHEAD_MS] 이하일 때만 갱신한다. exp 를 읽을 수 없는 토큰은 선제 갱신하지 않는다(거부되면 그때) */
    suspend fun refreshIfExpiring(trigger: RefreshTrigger = RefreshTrigger.PERIODIC): RefreshOutcome {
        val creds = current() ?: return RefreshOutcome.NotPaired
        val exp = JwtClaims.expiresAtMs(creds.vehicleAccessToken)
        _state.update { it.copy(accessExpiresAtMs = exp) }
        if (exp == null || exp - clock() > REFRESH_AHEAD_MS) return RefreshOutcome.NotNeeded
        return refresh(trigger, staleAccessToken = creds.vehicleAccessToken, afterRejection = false)
    }

    /**
     * 서버가 [rejectedAccessToken] 을 거부했다(REST 401 / MQTT 인증 실패). null 이면 현재 토큰이 거부된 것으로 본다.
     * 동시에 여러 번 불려도 서버 갱신은 한 번이다.
     */
    suspend fun refreshAfterRejection(rejectedAccessToken: String?, trigger: RefreshTrigger): RefreshOutcome =
        refresh(trigger, staleAccessToken = rejectedAccessToken, afterRejection = true)

    private suspend fun refresh(trigger: RefreshTrigger, staleAccessToken: String?, afterRejection: Boolean): RefreshOutcome = mutex.withLock {
        val creds = current() ?: return RefreshOutcome.NotPaired
        // single-flight: 기다리는 사이 다른 호출이 이미 바꿨다
        if (staleAccessToken != null && creds.vehicleAccessToken != staleAccessToken) {
            return RefreshOutcome.Refreshed(creds.vehicleAccessToken, rotatedNow = false)
        }
        val now = clock()
        val lastSuccess = _state.value.lastSuccessAtMs
        if (afterRejection && lastSuccess != null && now - lastSuccess < MIN_REJECTION_INTERVAL_MS) {
            // 방금 받은 토큰이 또 거부됐다 — 회전을 반복하지 않는다(브로커 JWKS 미반영 등). 다음 기회에
            DhLog.w("token refresh throttled trigger=$trigger (last success ${(now - lastSuccess) / 1000}s ago)")
            return RefreshOutcome.Throttled
        }
        val lastAttempt = _state.value.lastAttemptAtMs
        if (afterRejection && trigger == RefreshTrigger.MQTT_AUTH && lastAttempt != null && now - lastAttempt < MIN_MQTT_RETRY_INTERVAL_MS) {
            return RefreshOutcome.Throttled
        }
        _state.update { it.copy(lastAttemptAtMs = now) }
        DhLog.i("token refresh start trigger=$trigger vehicleId=${TextMasking.maskVehicleId(creds.vehicleId)}")
        when (val result = safeApiCall { api.refresh(RefreshRequest(creds.refreshToken)) }) {
            is ApiResult.Success -> {
                val body = result.value
                val updated = tokenStore.updateTokens(expectedRefreshToken = creds.refreshToken, accessToken = body.accessToken, refreshToken = body.refreshToken)
                if (updated == null) {
                    DhLog.w("token refresh result discarded — credentials changed meanwhile (disconnect/re-pair)")
                    _state.update { it.copy(lastResult = "DISCARDED $trigger", failures = it.failures + 1) }
                    return RefreshOutcome.Failed("credentials changed")
                }
                val exp = JwtClaims.expiresAtMs(updated.vehicleAccessToken)
                _state.update { it.copy(lastSuccessAtMs = clock(), accessExpiresAtMs = exp, lastResult = "OK $trigger", refreshes = it.refreshes + 1) }
                DhLog.i("token refreshed trigger=$trigger exp=${exp?.let { (it - clock()) / 60_000 }?.let { "${it}m" } ?: "?"} token=${TextMasking.maskToken(updated.vehicleAccessToken)}")
                RefreshOutcome.Refreshed(updated.vehicleAccessToken, rotatedNow = true)
            }
            is ApiResult.HttpFailure.Unauthorized -> {
                _state.update { it.copy(lastResult = "REJECTED $trigger", failures = it.failures + 1) }
                DhLog.w("token refresh rejected (401 ${result.code ?: ""}) trigger=$trigger → clearing credentials")
                // 보낸 refresh 가 아직 저장돼 있을 때만 지운다(그 사이 재페어링했으면 새 자격 증명은 건드리지 않는다)
                if (current()?.refreshToken == creds.refreshToken) tokenStore.clear(TokenClearReason.REFRESH_REJECTED)
                RefreshOutcome.Rejected
            }
            is ApiResult.Failure -> {
                _state.update { it.copy(lastResult = "FAILED ${result.summary}", failures = it.failures + 1) }
                DhLog.w("token refresh failed trigger=$trigger ${result.summary} — 토큰 유지, 다음에 다시")
                RefreshOutcome.Failed(result.summary)
            }
        }
    }

    private fun current(): StoredCredentials? = (tokenStore.state.value as? TokenStoreState.Present)?.credentials

    companion object {
        /** 만료 이 시간 전부터 선제 갱신 */
        const val REFRESH_AHEAD_MS = 2 * 60 * 60 * 1000L

        /** 선제 갱신 점검 주기 */
        const val CHECK_INTERVAL_MS = 30 * 60 * 1000L

        /** 성공 직후 같은 토큰이 다시 거부되면 이 시간 동안은 회전하지 않는다 */
        const val MIN_REJECTION_INTERVAL_MS = 60_000L

        /** MQTT 인증 실패발 갱신 시도 간격(재연결 백오프마다 서버를 두드리지 않게) */
        const val MIN_MQTT_RETRY_INTERVAL_MS = 30_000L
    }
}
