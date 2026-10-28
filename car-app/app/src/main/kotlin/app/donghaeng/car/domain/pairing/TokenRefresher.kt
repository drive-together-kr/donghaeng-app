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
import app.donghaeng.protocol.rest.ErrorCodes
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

/** 갱신을 부른 곳 — 로그·진단용. STARTUP·PERIODIC·MANUAL 은 선제 갱신, HTTP_401·MQTT_AUTH 는 서버가 지금 토큰을 거부해서 부른 것 */
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

    /**
     * 서버를 부르지 않았다 — 방금 회전한 토큰이 또 거부됐다(성공 뒤 60 s 안의 재거부, MQTT 발 회전은 1→2→4…→30분 지수 백오프 중)
     * 또는 직전 MQTT 발 시도와 30 s 안이다. 새 토큰도 계속 거부되면 토큰이 아니라 서버·브로커 설정(JWKS 캐시·kid 등) 문제일 수 있다.
     */
    data object Throttled : RefreshOutcome

    /** 서버가 refresh 토큰을 거부(401 `INVALID_TOKEN`)했고 지금 access token 도 못 쓴다 → 자격 증명을 [TokenClearReason.REFRESH_REJECTED] 로 지웠다 */
    data object Rejected : RefreshOutcome

    /**
     * 선제 갱신에서 refresh 가 거부(401 `INVALID_TOKEN`)됐지만 access token 은 [accessExpiresAtMs] 까지 유효하다 → 지우지 않는다.
     * 지금 지우면 그룹 세션까지 사라진다(docs/10 R13 과 같은 결과). '다시 연결 필요' 만 띄우고, 실제 거부(REST 401·MQTT 인증 거부)나 만료 시점에 지운다.
     */
    data class RejectedDeferred(val accessExpiresAtMs: Long) : RefreshOutcome

    /** 네트워크·5xx·`INVALID_TOKEN` 이 아닌 401(프록시·캡티브 포털 등) — 토큰은 그대로 두고 다음 기회에 다시 */
    data class Failed(val summary: String) : RefreshOutcome
}

val RefreshOutcome.accessTokenOrNull: String? get() = (this as? RefreshOutcome.Refreshed)?.accessToken

/** 진단 화면·health 줄용 상태(토큰 원문 없음) */
data class TokenRefreshState(
    val running: Boolean = false,
    /** 현재 차량 토큰의 exp(epoch ms) — 저장소가 바뀔 때마다(재페어링·해제·회전) 다시 읽는다. 못 읽거나 미페어링이면 null */
    val accessExpiresAtMs: Long? = null,
    val lastAttemptAtMs: Long? = null,
    val lastSuccessAtMs: Long? = null,
    /** 마지막 결과 한 줄(예: `OK MQTT_AUTH`, `FAILED network IOException`, `REJECTED HTTP_401`, `REJECTED(deferred) PERIODIC`) */
    val lastResult: String? = null,
    val refreshes: Int = 0,
    val failures: Int = 0,
    /** 선제 갱신이 거부된 시각 — access token 은 아직 유효해 지우지 않고 '다시 연결 필요' 만 보인다. null = 정상 */
    val repairNeededSinceMs: Long? = null,
    /** MQTT 인증 거부로 회전했는데 그 사이 MQTT 접속 성공 없이 이어진 횟수(백오프 단계). 접속에 성공하면 0 */
    val mqttRotationStreak: Int = 0,
    /** 다음 MQTT 발 회전을 허용하는 시각(백오프). null = 제한 없음 */
    val mqttNextRotationAtMs: Long? = null,
)

/**
 * 차량 토큰 갱신기 (T33, docs/03 §1.1 `POST /auth/refresh`). 차량 access token 은 24h 라 그대로 두면 REST 401·MQTT 인증 거부로
 * '연결됨' 인데 아무것도 안 되는 좀비 상태가 된다(docs/10 R13 조사).
 *
 * - **선제 갱신**: 앱 시작 직후와 [CHECK_INTERVAL_MS](30분)마다 JWT `exp` 를 보고(서명 검증 없음, [JwtClaims]) 남은 시간이 [REFRESH_AHEAD_MS](2시간) 이하면 갱신.
 *   네트워크·5xx 로 실패하면 [QUICK_RETRY_MS](20 s) 뒤 한 번 더 — 서버가 회전을 커밋했는데 응답만 잃었을 때 재사용 유예(60 s, docs/03 §1.1) 안에 다시 받는다.
 *   다음 점검은 토큰 만료 시각을 넘기지 않는다(만료 직후 한 번 더 본다).
 * - **거부 후 갱신**: REST 401(OkHttp Authenticator) · MQTT CONNACK 인증 거부([refreshAfterRejection]).
 * - **single-flight**: [Mutex] 하나. 기다리는 동안 다른 호출이 이미 회전했으면(거부된 토큰 ≠ 현재 토큰) 서버를 다시 부르지 않고 최신 토큰을 돌려준다.
 * - 회전된 토큰 쌍은 [TokenStore.updateTokens] 로 **한 번에**, 보낸 refresh 토큰이 아직 저장돼 있을 때만 저장한다(갱신 도중 연결 해제가 되살아나지 않게).
 * - refresh 401 은 본문 `code == INVALID_TOKEN`(동행 서버의 거부)일 때만 자격 증명 문제로 본다. 그 밖의 401(프록시 등)은 [RefreshOutcome.Failed].
 *   - 선제 갱신(STARTUP/PERIODIC/MANUAL)이고 access token 이 아직 유효 → [RefreshOutcome.RejectedDeferred]: 지우지 않고 [TokenRefreshState.repairNeededSinceMs] 만(홈 '다시 연결 필요').
 *   - 실제 거부(HTTP_401/MQTT_AUTH)이거나 access token 도 만료·판독 불가 → [TokenStore.clearIf](보낸 refresh 가 아직 저장돼 있을 때만) [TokenClearReason.REFRESH_REJECTED].
 * - **MQTT 발 회전 백오프**: 회전한 새 토큰도 브로커가 거부하면(토큰과 무관한 브로커 문제) 다음 MQTT 발 회전까지 1→2→4→8→16→30분.
 *   MQTT 접속에 성공하면([onMqttConnected]) 처음부터. 단계는 [TokenRefreshState.mqttRotationStreak] 로 진단 카드·health 줄에 보인다.
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

    /** 선제 갱신에서 거부된 refresh 토큰 — 저장소가 다른 refresh 를 내면(회전·재페어링) '다시 연결 필요' 를 내린다 */
    @Volatile
    private var rejectedRefreshToken: String? = null

    /** MQTT 발 Throttled 로그를 백오프 구간마다 한 번만 남기려고 */
    @Volatile
    private var throttleLoggedFor: Long? = null

    private val _state = MutableStateFlow(TokenRefreshState())
    val state: StateFlow<TokenRefreshState> = _state.asStateFlow()

    /** 한 번만(Application.onCreate). 저장소를 읽은 뒤 바로 한 번, 이후 30분마다(만료 시각을 넘기지 않게) 만료를 본다 */
    fun start(dispatcher: CoroutineDispatcher = Dispatchers.Default) {
        if (!started.compareAndSet(false, true)) return
        val handler = CoroutineExceptionHandler { _, e -> DhLog.e("token refresher loop failed", e) }
        val scope = CoroutineScope(SupervisorJob() + dispatcher + handler).also { loopScope = it }
        _state.update { it.copy(running = true) }
        // 재페어링·해제·회전 때 진단 '토큰 만료'·health expMin·'다시 연결 필요' 를 바로 맞춘다(30분 점검을 기다리지 않는다)
        scope.launch { tokenStore.state.collect { onStoreState(it) } }
        scope.launch {
            runCatching { tokenStore.load() }
            var trigger = RefreshTrigger.STARTUP
            var quickRetried = false
            while (isActive) {
                val outcome = refreshIfExpiring(trigger)
                if (outcome !is RefreshOutcome.NotNeeded && outcome !is RefreshOutcome.NotPaired) DhLog.i("token refresh check trigger=$trigger → $outcome")
                trigger = RefreshTrigger.PERIODIC
                val quick = outcome is RefreshOutcome.Failed && !quickRetried
                quickRetried = quick
                delay(if (quick) QUICK_RETRY_MS else nextCheckDelayMs(clock()))
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

    /** 다음 점검까지 — 기본 30분, 단 지금 토큰이 그 전에 만료되면 만료 직후 */
    internal fun nextCheckDelayMs(now: Long): Long {
        val exp = _state.value.accessExpiresAtMs ?: return CHECK_INTERVAL_MS
        if (exp <= now) return CHECK_INTERVAL_MS
        return minOf(CHECK_INTERVAL_MS, maxOf(exp - now + EXPIRY_MARGIN_MS, QUICK_RETRY_MS))
    }

    /** MQTT 접속 성공(MqttSessionManager) — 회전한 토큰이 통했다 → MQTT 발 회전 백오프를 처음부터 */
    fun onMqttConnected() {
        val s = _state.value
        if (s.mqttRotationStreak == 0 && s.mqttNextRotationAtMs == null) return
        DhLog.i("mqtt connected → token refresh backoff reset (streak was ${s.mqttRotationStreak})")
        throttleLoggedFor = null
        _state.update { it.copy(mqttRotationStreak = 0, mqttNextRotationAtMs = null) }
    }

    private fun onStoreState(store: TokenStoreState) {
        when (store) {
            TokenStoreState.Loading -> Unit
            is TokenStoreState.Present -> {
                val creds = store.credentials
                val stillRejected = rejectedRefreshToken != null && rejectedRefreshToken == creds.refreshToken
                if (!stillRejected) rejectedRefreshToken = null
                _state.update {
                    it.copy(
                        accessExpiresAtMs = JwtClaims.expiresAtMs(creds.vehicleAccessToken),
                        repairNeededSinceMs = if (stillRejected) it.repairNeededSinceMs else null,
                    )
                }
            }
            TokenStoreState.Empty -> {
                rejectedRefreshToken = null
                throttleLoggedFor = null
                _state.update { it.copy(accessExpiresAtMs = null, repairNeededSinceMs = null, mqttRotationStreak = 0, mqttNextRotationAtMs = null) }
            }
            // 읽지 못했을 뿐 지운 게 아니다 — 만료 시각만 모른다
            is TokenStoreState.Unavailable -> _state.update { it.copy(accessExpiresAtMs = null) }
        }
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
        val s = _state.value
        val lastSuccess = s.lastSuccessAtMs
        if (afterRejection && lastSuccess != null && now - lastSuccess < MIN_REJECTION_INTERVAL_MS) {
            // 방금 받은 토큰이 또 거부됐다 — 회전을 반복하지 않는다(브로커 JWKS 미반영 등). 다음 기회에
            DhLog.w("token refresh throttled trigger=$trigger (last success ${(now - lastSuccess) / 1000}s ago)")
            return RefreshOutcome.Throttled
        }
        if (afterRejection && trigger == RefreshTrigger.MQTT_AUTH) {
            val next = s.mqttNextRotationAtMs
            if (next != null && now < next) {
                if (throttleLoggedFor != next) {
                    throttleLoggedFor = next
                    DhLog.w("token refresh backoff trigger=MQTT_AUTH streak=${s.mqttRotationStreak} next in ${(next - now) / 1000}s — 회전한 토큰도 브로커가 거부(브로커 인증 설정·JWKS 확인)")
                }
                return RefreshOutcome.Throttled
            }
            val lastAttempt = s.lastAttemptAtMs
            if (lastAttempt != null && now - lastAttempt < MIN_MQTT_RETRY_INTERVAL_MS) return RefreshOutcome.Throttled
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
                rejectedRefreshToken = null
                val exp = JwtClaims.expiresAtMs(updated.vehicleAccessToken)
                val mqtt = trigger == RefreshTrigger.MQTT_AUTH
                _state.update {
                    val streak = if (mqtt) it.mqttRotationStreak + 1 else it.mqttRotationStreak
                    it.copy(
                        lastSuccessAtMs = clock(), accessExpiresAtMs = exp, lastResult = "OK $trigger", refreshes = it.refreshes + 1, repairNeededSinceMs = null,
                        mqttRotationStreak = streak,
                        mqttNextRotationAtMs = if (mqtt) now + mqttBackoffMs(streak) else it.mqttNextRotationAtMs,
                    )
                }
                val streakText = if (mqtt) " mqttStreak=${_state.value.mqttRotationStreak}" else ""
                DhLog.i("token refreshed trigger=$trigger exp=${exp?.let { (it - clock()) / 60_000 }?.let { "${it}m" } ?: "?"} token=${TextMasking.maskToken(updated.vehicleAccessToken)}$streakText")
                RefreshOutcome.Refreshed(updated.vehicleAccessToken, rotatedNow = true)
            }
            is ApiResult.HttpFailure.Unauthorized -> onRefreshUnauthorized(result, creds, trigger, afterRejection, now)
            is ApiResult.Failure -> {
                _state.update { it.copy(lastResult = "FAILED ${result.summary}", failures = it.failures + 1) }
                DhLog.w("token refresh failed trigger=$trigger ${result.summary} — 토큰 유지, 다음에 다시")
                RefreshOutcome.Failed(result.summary)
            }
        }
    }

    private suspend fun onRefreshUnauthorized(
        result: ApiResult.HttpFailure.Unauthorized,
        creds: StoredCredentials,
        trigger: RefreshTrigger,
        afterRejection: Boolean,
        now: Long,
    ): RefreshOutcome {
        if (result.code != ErrorCodes.INVALID_TOKEN) {
            // 동행 서버의 거부가 아니다(T32 프록시·캡티브 포털·게이트웨이의 401 등) — 자격 증명은 그대로
            _state.update { it.copy(lastResult = "FAILED 401 ${result.code ?: "no-code"} $trigger", failures = it.failures + 1) }
            DhLog.w("token refresh got 401 without INVALID_TOKEN (code=${result.code ?: "-"}) trigger=$trigger — 동행 서버 응답이 아닐 수 있어 토큰 유지")
            return RefreshOutcome.Failed(result.summary)
        }
        val accessExp = JwtClaims.expiresAtMs(creds.vehicleAccessToken)
        if (!afterRejection && accessExp != null && accessExp > now) {
            // 선제 갱신 거부 — 지금 토큰은 아직 통한다. 지우면 그룹 세션까지 사라지므로 만료·실제 거부 때 지운다(만료 직후 점검이 잡혀 있다)
            rejectedRefreshToken = creds.refreshToken
            _state.update { it.copy(lastResult = "REJECTED(deferred) $trigger", failures = it.failures + 1, repairNeededSinceMs = it.repairNeededSinceMs ?: now) }
            DhLog.w("token refresh rejected (401 INVALID_TOKEN) trigger=$trigger — access token 이 ${(accessExp - now) / 60_000}m 더 유효해 지우지 않고 '다시 연결 필요' 만 표시")
            return RefreshOutcome.RejectedDeferred(accessExp)
        }
        _state.update { it.copy(lastResult = "REJECTED $trigger", failures = it.failures + 1) }
        DhLog.w("token refresh rejected (401 INVALID_TOKEN) trigger=$trigger → clearing credentials")
        // 보낸 refresh 가 아직 저장돼 있을 때만 지운다(비교·삭제는 저장소 잠금 안에서 한 번에 — 그 사이 재페어링한 새 자격 증명은 건드리지 않는다)
        if (!tokenStore.clearIf(expectedRefreshToken = creds.refreshToken, reason = TokenClearReason.REFRESH_REJECTED)) {
            DhLog.i("token refresh rejection ignored — credentials changed meanwhile (re-pair)")
        }
        return RefreshOutcome.Rejected
    }

    private fun current(): StoredCredentials? = (tokenStore.state.value as? TokenStoreState.Present)?.credentials

    companion object {
        /** 만료 이 시간 전부터 선제 갱신 */
        const val REFRESH_AHEAD_MS = 2 * 60 * 60 * 1000L

        /** 선제 갱신 점검 주기 */
        const val CHECK_INTERVAL_MS = 30 * 60 * 1000L

        /** 선제 갱신이 네트워크·5xx 로 실패하면 이만큼 뒤 한 번 더(서버 재사용 유예 60 s 안) */
        const val QUICK_RETRY_MS = 20_000L

        /** 만료 직후 점검 여유 */
        const val EXPIRY_MARGIN_MS = 5_000L

        /** 성공 직후 같은 토큰이 다시 거부되면 이 시간 동안은 회전하지 않는다 */
        const val MIN_REJECTION_INTERVAL_MS = 60_000L

        /** MQTT 인증 실패발 갱신 시도 간격(재연결 백오프마다 서버를 두드리지 않게) */
        const val MIN_MQTT_RETRY_INTERVAL_MS = 30_000L

        /** MQTT 발 회전 백오프: 1분부터 두 배씩, 최대 30분 */
        const val MQTT_BACKOFF_BASE_MS = 60_000L
        const val MQTT_BACKOFF_MAX_MS = 30 * 60 * 1000L

        /** [streak] 번째 MQTT 발 회전 뒤 다음 회전까지(1 → 1분, 2 → 2분, 3 → 4분 … 최대 30분) */
        fun mqttBackoffMs(streak: Int): Long =
            (MQTT_BACKOFF_BASE_MS shl (streak - 1).coerceIn(0, 5)).coerceAtMost(MQTT_BACKOFF_MAX_MS)
    }
}
