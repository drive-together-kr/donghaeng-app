package app.donghaeng.car.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.donghaeng.car.BuildConfig
import app.donghaeng.car.adapter.sdk.ProbeLine
import app.donghaeng.car.adapter.sdk.SdkProbe
import app.donghaeng.car.core.common.ClearRecord
import app.donghaeng.car.core.common.TextMasking
import app.donghaeng.car.core.common.TokenClearReason
import app.donghaeng.car.core.common.TokenStore
import app.donghaeng.car.core.common.TokenStoreState
import app.donghaeng.car.core.network.MqttAddressDisplay
import app.donghaeng.car.core.network.ServerAddresses
import app.donghaeng.car.core.network.ServerConfig
import app.donghaeng.car.core.network.mqtt.MqttGateway
import app.donghaeng.car.core.network.mqtt.MqttState
import app.donghaeng.car.core.network.mqtt.MqttStatus
import app.donghaeng.car.domain.pairing.TokenRefreshState
import app.donghaeng.car.domain.pairing.TokenRefresher
import app.donghaeng.car.domain.session.GroupSession
import app.donghaeng.car.domain.session.GroupSessionStore
import app.donghaeng.car.domain.session.MqttSessionManager
import app.donghaeng.car.domain.session.StoreRetryState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 차량 연결 상태 — [TokenStore] 에 자격 증명이 있으면 CONNECTED. CHECKING = 저장소를 아직 읽는 중. UNAVAILABLE = 저장소를 읽지 못함(T33) */
enum class VehicleConnection { CHECKING, DISCONNECTED, CONNECTED, UNAVAILABLE }

/**
 * 홈 상태 배너 (T33). 하나만 보인다 — 우선순위: 저장소 오류 > 다시 연결 필요(지워짐) > 곧 다시 연결 필요(갱신 거부, 아직 동작) > 서버 연결 끊김.
 * 주행 중에는 버튼 없이 문구만(화면이 판단).
 */
sealed interface HomeBanner {
    /** 토큰 저장소를 읽지 못함 — '다시 시도' */
    data class StorageUnavailable(val reason: String, val attempts: Int, val maxAttempts: Int, val exhausted: Boolean) : HomeBanner

    /** 서버가 끊은 연결(TOKEN_REVOKED · refresh 거부) — '폰에서 다시 연결해 주세요' */
    data class RepairNeeded(val reason: TokenClearReason) : HomeBanner

    /**
     * 선제 갱신이 거부됐지만 지금 토큰은 [expiresAtMs] 까지 유효하다(T33 리뷰 — 지우지 않음). 그 뒤엔 연결이 끊긴다 → 정차 중에 폰에서 다시 연결.
     * [expiresAtMs] 를 모르면 null.
     */
    data class RepairSoon(val expiresAtMs: Long?) : HomeBanner

    /** MQTT 가 [SERVER_BANNER_AFTER_MS] 이상 못 붙음 */
    data class ServerDisconnected(val seconds: Long, val lastError: String?, val authRejected: Boolean) : HomeBanner {
        /** 화면에 보일 원인 분류(원문 예외 문구는 진단에만, E119) */
        val reason: ServerReason get() = serverReason(lastError, authRejected)
    }

    /** 서버 연결이 안 되는 원인 — 사람 말로 한 줄(문구는 strings_t33.xml) */
    enum class ServerReason { AUTH, TIMEOUT, REFUSED, UNREACHABLE, OTHER }

    companion object {
        const val SERVER_BANNER_AFTER_MS = 30_000L
        const val ERROR_SUMMARY_MAX = 80

        /**
         * MQTT 마지막 오류 원문 → 원인 분류(순수 — 단위 테스트). 원문은 HiveMQ·Netty 영어 예외 문구이거나 "refused: …"(주소 정책 거부, T32)·"auth rejected …".
         * 모르는 문구는 [ServerReason.OTHER](배너는 경과 시간만).
         */
        fun serverReason(lastError: String?, authRejected: Boolean): ServerReason {
            if (authRejected) return ServerReason.AUTH
            val e = lastError?.lowercase() ?: return ServerReason.OTHER
            return when {
                e.startsWith("auth rejected") || "not authorized" in e || "bad user name" in e -> ServerReason.AUTH
                e.startsWith("refused:") -> ServerReason.REFUSED
                "timeout" in e || "timed out" in e || "connack" in e -> ServerReason.TIMEOUT
                "unknownhost" in e || "unresolved" in e || "no route" in e || "unreachable" in e || "connection refused" in e || "connectexception" in e || "network" in e -> ServerReason.UNREACHABLE
                else -> ServerReason.OTHER
            }
        }
    }
}

/** 홈 상태 칩의 실시간 연결(서버 MQTT) — '폰 계정 연결'(페어링)과 다른 말(E122) */
enum class Realtime { CONNECTED, CONNECTING, OFF }

/** 사용자에게 보이는 문구(앱 이름·연결 상태 등)는 여기 두지 않고 res/values/strings.xml 에서 가져온다. */
data class HomeUiState(
    val flavor: String = BuildConfig.FLAVOR,
    val versionName: String = BuildConfig.VERSION_NAME,
    /** T32 지금 서버 주소(숨은 메뉴 사용자 지정 ?: 빌드 기본값) */
    val publicBaseUrl: String = ServerConfig.BUILD_DEFAULT_BASE_URL,
    val baseUrlOverridden: Boolean = false,
    /** T32 실제 MQTT 주소(사용자 지정 ?: 페어링 응답 mqtt.uri)와 출처 */
    val mqttAddress: MqttAddressDisplay = MqttAddressDisplay.UNPAIRED,
    val connection: VehicleConnection = VehicleConnection.CHECKING,
    /** 연결됨일 때 서버가 준 차량 별명(화면 표시용 — 로그에는 쓰지 않는다) */
    val nickname: String? = null,
    /** 연결됨일 때 서버 vehicleId 마스킹(앞 4 + 뒤 2) */
    val vehicleIdMasked: String? = null,
    /** T12 페어링 화면 — 항상 열 수 있다(연결 상태에서는 '다시 연결' 안내) */
    val canPairPhone: Boolean = true,
    /** 페어링돼 있으면 그룹을 만들 수 있다(T17). 이미 그룹이 있으면 '그룹 보기' 로 같은 화면을 연다 */
    val canStartGroup: Boolean = false,
    /** 활성 그룹(방장 또는 GROUP_JOINED 로 들어온 멤버). null = 없음 */
    val groupSession: GroupSession? = null,
    /** SDK 점검 결과. null = 아직 실행 전. pleos flavor 에서만 카드에 표시 */
    val sdkProbe: List<ProbeLine>? = null,
    /** T29 주행 중(입력 비활성). 그룹 보기는 허용 */
    val isDriving: Boolean = false,
    /** T33 상태 배너. null = 없음 */
    val banner: HomeBanner? = null,
    /** 서버 실시간 연결(MQTT) — 홈 상태 칩 */
    val realtime: Realtime = Realtime.OFF,
) {
    val showSdkProbe: Boolean get() = flavor == "pleos"

    /** 상태 칩 "차량 SDK 5/5" — 점검 전이면 null */
    val sdkAvailable: Int? get() = sdkProbe?.count { it.available }
    val sdkTotal: Int? get() = sdkProbe?.size

    /**
     * 상태별 주 버튼 하나(2-2·E117): 그룹이 있으면 [그룹 보기], 연결됨이면 [같이 가기 시작], 그 밖(연결 안 됨·확인 중·저장소 오류)은 [내 폰과 연결하기].
     * 저장소 오류여도 그룹이 남아 있으면 [그룹 보기].
     */
    val primaryAction: HomeAction
        get() = when {
            groupSession != null -> HomeAction.OPEN_GROUP
            connection == VehicleConnection.CONNECTED -> HomeAction.START_GROUP
            else -> HomeAction.PAIR_PHONE
        }

    /** 주 버튼을 누를 수 있나 — 그룹 보기는 주행 중에도, 나머지는 정차 중에만(규칙 6). 확인 중·저장소 오류면 연결하기를 막는다 */
    val primaryEnabled: Boolean
        get() = when (primaryAction) {
            HomeAction.OPEN_GROUP -> true
            HomeAction.START_GROUP -> canStartGroup && !isDriving
            HomeAction.PAIR_PHONE -> canPairPhone && !isDriving && connection != VehicleConnection.CHECKING
        }
}

/** 홈 주 버튼 */
enum class HomeAction { PAIR_PHONE, START_GROUP, OPEN_GROUP }

private data class HealthSide(val mqtt: MqttStatus, val lastClear: ClearRecord?, val retry: StoreRetryState, val nowMs: Long, val refresh: TokenRefreshState)

@HiltViewModel
class HomeViewModel @Inject constructor(
    sdkProbe: SdkProbe,
    private val tokenStore: TokenStore,
    sessionStore: GroupSessionStore,
    driving: app.donghaeng.car.core.common.DrivingStateMonitor,
    mqtt: MqttGateway,
    private val sessionManager: MqttSessionManager,
    refresher: TokenRefresher,
    serverConfig: ServerConfig,
) : ViewModel() {

    init {
        viewModelScope.launch { tokenStore.load() }
    }

    /** 배너의 '몇 초째' 와 30초 문턱을 다시 계산하는 틱 */
    private val ticker: Flow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(TICK_MS)
        }
    }

    private val health = combine(mqtt.status, tokenStore.lastClear, sessionManager.storeRetry, ticker, refresher.state) { m, c, r, now, rf -> HealthSide(m, c, r, now, rf) }

    private val healthAndServer = combine(health, serverConfig.current) { h, server -> h to server }

    val uiState: StateFlow<HomeUiState> = combine(sdkProbe.report, tokenStore.state, sessionStore.session, driving.isDriving().onStart { emit(false) }, healthAndServer) { lines, tokens, session, isDriving, hs ->
        val (h, server) = hs
        build(lines, tokens, session).withServer(server, tokens).copy(isDriving = isDriving, banner = banner(tokens, h), realtime = realtimeOf(h.mqtt.state))
    }.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000),
        build(sdkProbe.report.value, tokenStore.state.value, sessionStore.session.value).withServer(serverConfig.current.value, tokenStore.state.value),
    )

    private fun HomeUiState.withServer(server: ServerAddresses, tokens: TokenStoreState) = copy(
        publicBaseUrl = server.publicBaseUrl, baseUrlOverridden = server.baseUrlOverridden,
        mqttAddress = MqttAddressDisplay.of(server, (tokens as? TokenStoreState.Present)?.credentials?.mqttUri),
    )

    /** 저장소 오류 배너의 '다시 시도' */
    fun retryTokenStore() = sessionManager.retryTokenStore()

    private fun build(lines: List<ProbeLine>?, tokens: TokenStoreState, session: GroupSession?): HomeUiState = when (tokens) {
        TokenStoreState.Loading -> HomeUiState(connection = VehicleConnection.CHECKING, sdkProbe = lines)
        TokenStoreState.Empty -> HomeUiState(connection = VehicleConnection.DISCONNECTED, sdkProbe = lines)
        // 읽기 실패: 페어링 화면으로 가도 새 코드를 받지 않는다(PairingViewModel) — 그룹 세션은 지우지 않았으므로 '그룹 보기' 는 그대로 보인다
        is TokenStoreState.Unavailable -> HomeUiState(connection = VehicleConnection.UNAVAILABLE, groupSession = session, sdkProbe = lines)
        is TokenStoreState.Present -> HomeUiState(
            connection = VehicleConnection.CONNECTED,
            nickname = tokens.credentials.nickname,
            vehicleIdMasked = TextMasking.maskVehicleId(tokens.credentials.vehicleId),
            canStartGroup = true,
            groupSession = session,
            sdkProbe = lines,
        )
    }

    companion object {
        const val TICK_MS = 5_000L

        fun realtimeOf(state: MqttState): Realtime = when (state) {
            MqttState.CONNECTED -> Realtime.CONNECTED
            MqttState.CONNECTING, MqttState.RECONNECTING -> Realtime.CONNECTING
            MqttState.DISCONNECTED -> Realtime.OFF
        }

        /** 배너 결정 — 순수 함수(단위 테스트 대상) */
        fun banner(
            tokens: TokenStoreState,
            mqtt: MqttStatus,
            lastClear: ClearRecord?,
            retry: StoreRetryState,
            nowMs: Long,
            refresh: TokenRefreshState = TokenRefreshState(),
        ): HomeBanner? = when (tokens) {
            is TokenStoreState.Unavailable -> HomeBanner.StorageUnavailable(tokens.reason, retry.attempts, retry.maxAttempts, retry.exhausted)
            TokenStoreState.Empty -> TokenClearReason.fromName(lastClear?.reason)?.takeIf { it.needsRepairFromPhone }?.let { HomeBanner.RepairNeeded(it) }
            is TokenStoreState.Present -> {
                val since = mqtt.notConnectedSinceMs
                if (refresh.repairNeededSinceMs != null) {
                    HomeBanner.RepairSoon(refresh.accessExpiresAtMs)
                } else if (mqtt.state != MqttState.CONNECTED && since != null && nowMs - since >= HomeBanner.SERVER_BANNER_AFTER_MS) {
                    HomeBanner.ServerDisconnected(
                        seconds = (nowMs - since) / 1000,
                        lastError = mqtt.lastError?.take(HomeBanner.ERROR_SUMMARY_MAX),
                        authRejected = mqtt.lastError?.startsWith("auth rejected") == true,
                    )
                } else {
                    null
                }
            }
            TokenStoreState.Loading -> null
        }
    }

    private fun banner(tokens: TokenStoreState, h: HealthSide): HomeBanner? = banner(tokens, h.mqtt, h.lastClear, h.retry, h.nowMs, h.refresh)
}
