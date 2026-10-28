package app.donghaeng.car.core.common

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 페어링으로 받은 차량 자격 증명 (docs/03 §1.2 `POST /device/token` 200 응답을 그대로 보관).
 * 로그에는 마스킹한 값만 남긴다 — 토큰·닉네임 원문 금지(CLAUDE.md 규칙 8), [vehicleId] 도 `TextMasking.maskVehicleId`([TextMasking]).
 *
 * [toString] 은 **마스킹된 값만** 낸다(T33 리뷰) — 문자열 템플릿·예외 메시지·테스트 실패 메시지로 새어 나가도
 * logcat·링 버퍼·`files/dh_log.txt`(진단 화면 캡처)에 토큰·닉네임·소유자 ID 원문이 남지 않게 한다.
 */
data class StoredCredentials(
    val vehicleAccessToken: String,
    val refreshToken: String,
    val vehicleId: String,
    val nickname: String,
    val ownerId: String,
    val mqttUri: String,
    val mqttClientId: String,
    val mqttUsername: String,
) {
    override fun toString(): String =
        "StoredCredentials(vehicleId=${TextMasking.maskVehicleId(vehicleId)}, accessToken=${TextMasking.maskToken(vehicleAccessToken)}, " +
            "refreshToken=${TextMasking.maskToken(refreshToken)}, nickname=${TextMasking.maskNickname(nickname)}, " +
            "ownerId=${TextMasking.maskVehicleId(ownerId)}, mqttUri=$mqttUri, mqttClientId=${TextMasking.maskVehicleId(mqttClientId)}, " +
            "mqttUsername=${TextMasking.maskVehicleId(mqttUsername)})"
}

/**
 * 저장소 상태 — 읽기 전(Loading)과 "읽었는데 없음"(Empty)을 구분해 홈 화면이 잠깐 '연결 안 됨' 으로 깜빡이지 않게 한다.
 * T33: **읽기 실패(Unavailable) ≠ 미페어링(Empty)** — Keystore·복호화·I/O 예외로 못 읽은 것은 지워진 게 아니다.
 * Unavailable 에서는 그룹 세션을 지우지 않고 연결도 하지 않으며, 다음 [TokenStore.load] 가 다시 시도한다(docs/10 R13).
 */
sealed interface TokenStoreState {
    data object Loading : TokenStoreState
    data object Empty : TokenStoreState
    data class Present(val credentials: StoredCredentials) : TokenStoreState {
        /** 마스킹된 [StoredCredentials.toString] 만 — 상태를 통째로 로그에 넣어도 원문이 남지 않게 */
        override fun toString(): String = "Present(credentials=$credentials)"
    }

    /** @param reason 예외 요약(클래스 이름 위주, 토큰·경로 없음) — 진단·홈 배너 표시용 */
    data class Unavailable(val reason: String) : TokenStoreState
}

/**
 * 토큰 삭제 사유 (T33). [ClearRecord.reason] 에 이름으로 남고 홈·진단이 읽는다.
 */
enum class TokenClearReason {
    /** 설정 화면 '연결 해제'(확인 창 뒤) */
    USER_SETTINGS_DISCONNECT,

    /** 페어링 화면 '연결 해제 후 다시 연결'(확인 창 뒤) */
    USER_REPAIR,

    /** 서버 cmd `TOKEN_REVOKED`(폰에서 페어링 해제·다른 계정으로 소유권 이전) */
    TOKEN_REVOKED_CMD,

    /** `POST /auth/refresh` 가 401 — refresh 토큰이 폐기·만료됨 */
    REFRESH_REJECTED,

    /** 서버 주소를 바꿨다(다른 서버의 토큰은 쓸 수 없다 — T32 런타임 서버 변경용 예약) */
    SERVER_CHANGED,

    /** 설정 '저장소 초기화' — 읽을 수 없는 `dh_tokens` 파일을 통째로 지운다 */
    STORE_RESET,
    ;

    /** 사용자가 고른 게 아니라 서버가 끊은 것 → 홈에 '폰에서 다시 연결해 주세요' */
    val needsRepairFromPhone: Boolean get() = this == REFRESH_REJECTED || this == TOKEN_REVOKED_CMD

    companion object {
        fun fromName(name: String?): TokenClearReason? = entries.firstOrNull { it.name == name }
    }
}

/**
 * 차량 토큰 저장소 (T12). 구현: [EncryptedTokenStore](기기, EncryptedSharedPreferences) / [InMemoryTokenStore](테스트).
 *
 * - [load] 는 디스크에서 한 번 읽어 [state] 를 채운다(이미 읽었으면 캐시 반환). 읽기에 실패하면 [TokenStoreState.Unavailable] 을 내고 null — 다음 호출에서 다시 읽는다.
 * - [save]/[clear] 는 디스크와 [state] 를 함께 갱신한다 → 홈·설정 화면이 즉시 반영된다.
 * - [updateTokens] 는 refresh 회전 결과(access + refresh)를 **한 번의 커밋으로** 바꾼다(T33 TokenRefresher).
 * - [clear] 는 사유를 받아 [lastClear] 와 진단 기록(`dh_diag`)에 남긴다.
 */
interface TokenStore {
    val state: StateFlow<TokenStoreState>

    /** 마지막 토큰 삭제 기록(프로세스를 넘어 유지). 없으면 null */
    val lastClear: StateFlow<ClearRecord?>

    suspend fun load(): StoredCredentials?
    suspend fun save(credentials: StoredCredentials)

    /**
     * 현재 저장된 refresh 토큰이 [expectedRefreshToken] 일 때만 두 토큰을 바꾼다(compare-and-set).
     * 그 사이 연결 해제·재페어링이 있었으면 아무것도 하지 않고 null — 해제를 갱신이 되살리지 않게 한다.
     */
    suspend fun updateTokens(expectedRefreshToken: String, accessToken: String, refreshToken: String): StoredCredentials?

    suspend fun clear(reason: TokenClearReason)

    /**
     * 지금 저장된 refresh 토큰이 [expectedRefreshToken] 일 때만 [clear] 한다(compare-and-set — 비교와 삭제가 저장소 잠금 안에서 한 번에).
     * 갱신 응답을 기다리는 사이 재페어링([save])으로 들어온 새 자격 증명을 옛 거부 결과로 지우지 않게 한다(T33 리뷰). 지웠으면 true.
     */
    suspend fun clearIf(expectedRefreshToken: String, reason: TokenClearReason): Boolean
}

/** 테스트·폴백용 메모리 저장소. 프로세스가 끝나면 사라진다. */
class InMemoryTokenStore(
    initial: StoredCredentials? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) : TokenStore {

    private var stored: StoredCredentials? = initial
    private val _state = MutableStateFlow<TokenStoreState>(TokenStoreState.Loading)
    override val state: StateFlow<TokenStoreState> = _state.asStateFlow()

    private val _lastClear = MutableStateFlow<ClearRecord?>(null)
    override val lastClear: StateFlow<ClearRecord?> = _lastClear.asStateFlow()

    /** 테스트: non-null 이면 [load] 가 이 사유로 [TokenStoreState.Unavailable] 을 낸다(저장된 값은 그대로) */
    var unavailableReason: String? = null

    /** 테스트: [clear] 에 들어온 사유 순서 */
    val clearReasons = mutableListOf<TokenClearReason>()

    /** 테스트: [load] 호출 수 */
    var loadCalls = 0
        private set

    override suspend fun load(): StoredCredentials? {
        loadCalls++
        unavailableReason?.let { reason ->
            _state.value = TokenStoreState.Unavailable(reason)
            return null
        }
        return stored.also { publish(it) }
    }

    override suspend fun save(credentials: StoredCredentials) {
        stored = credentials
        publish(credentials)
    }

    override suspend fun updateTokens(expectedRefreshToken: String, accessToken: String, refreshToken: String): StoredCredentials? {
        val current = stored ?: return null
        if (current.refreshToken != expectedRefreshToken) return null
        val updated = current.copy(vehicleAccessToken = accessToken, refreshToken = refreshToken)
        stored = updated
        publish(updated)
        return updated
    }

    override suspend fun clearIf(expectedRefreshToken: String, reason: TokenClearReason): Boolean {
        if (stored?.refreshToken != expectedRefreshToken) return false
        clear(reason)
        return true
    }

    override suspend fun clear(reason: TokenClearReason) {
        val vehicleId = stored?.vehicleId
        stored = null
        clearReasons += reason
        _lastClear.value = ClearRecord(reason.name, clock(), vehicleId?.let(TextMasking::maskVehicleId))
        publish(null)
    }

    private fun publish(credentials: StoredCredentials?) {
        _state.value = if (credentials == null) TokenStoreState.Empty else TokenStoreState.Present(credentials)
    }
}
