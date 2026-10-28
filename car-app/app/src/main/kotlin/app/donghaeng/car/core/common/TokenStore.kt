package app.donghaeng.car.core.common

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 페어링으로 받은 차량 자격 증명 (docs/03 §1.2 `POST /device/token` 200 응답을 그대로 보관).
 * 로그에는 마스킹한 값만 남긴다 — 토큰·닉네임 원문 금지(CLAUDE.md 규칙 8), [vehicleId] 도 `TextMasking.maskVehicleId`([TextMasking]).
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
)

/** 저장소 상태 — 읽기 전(Loading)과 "읽었는데 없음"(Empty)을 구분해 홈 화면이 잠깐 '연결 안 됨' 으로 깜빡이지 않게 한다. */
sealed interface TokenStoreState {
    data object Loading : TokenStoreState
    data object Empty : TokenStoreState
    data class Present(val credentials: StoredCredentials) : TokenStoreState
}

/**
 * 차량 토큰 저장소 (T12). 구현: [EncryptedTokenStore](기기, EncryptedSharedPreferences) / [InMemoryTokenStore](테스트).
 *
 * - [load] 는 디스크에서 한 번 읽어 [state] 를 채운다(이미 읽었으면 캐시 반환).
 * - [save]/[clear] 는 디스크와 [state] 를 함께 갱신한다 → 홈·설정 화면이 즉시 반영된다.
 * - TODO(T16): MqttGateway 가 vehicleAccessToken 을 password 로 쓴다. 만료(24h) 시 refresh 는 T16 이후.
 */
interface TokenStore {
    val state: StateFlow<TokenStoreState>
    suspend fun load(): StoredCredentials?
    suspend fun save(credentials: StoredCredentials)
    suspend fun clear()
}

/** 테스트·폴백용 메모리 저장소. 프로세스가 끝나면 사라진다. */
class InMemoryTokenStore(initial: StoredCredentials? = null) : TokenStore {

    private var stored: StoredCredentials? = initial
    private val _state = MutableStateFlow<TokenStoreState>(TokenStoreState.Loading)
    override val state: StateFlow<TokenStoreState> = _state.asStateFlow()

    override suspend fun load(): StoredCredentials? = stored.also { publish(it) }

    override suspend fun save(credentials: StoredCredentials) {
        stored = credentials
        publish(credentials)
    }

    override suspend fun clear() {
        stored = null
        publish(null)
    }

    private fun publish(credentials: StoredCredentials?) {
        _state.value = if (credentials == null) TokenStoreState.Empty else TokenStoreState.Present(credentials)
    }
}
