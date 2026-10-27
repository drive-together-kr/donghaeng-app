package app.donghaeng.companion.demo

import app.donghaeng.companion.sdk.AccountProvider
import app.donghaeng.companion.sdk.DongHaeng
import app.donghaeng.companion.sdk.DongHaengConfig
import app.donghaeng.companion.sdk.GroupJoinResult
import app.donghaeng.companion.sdk.TokenProvider
import app.donghaeng.protocol.rest.TokenResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 호스트 앱(공식 앱)이 원래 갖고 있는 것들을 흉내 낸 메모리 상태: 로그인한 계정과 그 토큰, 합류한 그룹 목록.
 * 동행 모듈은 이 중 **토큰**만 [TokenProvider] 로 받아 간다. 토큰은 메모리에만 두고 저장하지 않는다.
 */
object DemoSession {

    data class Account(val email: String, val nickname: String, val userId: String)

    private val _account = MutableStateFlow<Account?>(null)
    val account: StateFlow<Account?> = _account.asStateFlow()

    private val _groups = MutableStateFlow<List<GroupJoinResult>>(emptyList())
    val groups: StateFlow<List<GroupJoinResult>> = _groups.asStateFlow()

    /** 서버 `POST /v1/auth/login` 응답(호스트 앱의 계정 토큰). 메모리에만 보관 */
    @Volatile
    private var devTokens: TokenResponse? = null

    private val devAuth: DevAuthClient by lazy { DevAuthClient(BuildConfig.DH_PUBLIC_BASE_URL) }

    /**
     * 공식 앱의 로그인 성공 시점에 해당. 서버 dev 로그인으로 토큰을 받고(docs/07 §4.2), 그 accessToken 을 돌려주는 [TokenProvider] 로
     * 동행 모듈을 초기화한다 — 모듈은 이 토큰을 `POST /v1/auth/exchange {provider:"dev", accessToken}` 으로 동행 JWT 와 교환한다.
     * 실제 공식 앱이라면 여기서 자기 OAuth 2.0 토큰 저장소를 감싼 [TokenProvider] 를 넘긴다.
     *
     * @throws DevAuthClient.DevAuthException 서버 로그인 실패(사용자용 문구 포함)
     * @throws IllegalArgumentException 서버 주소 설정 오류(BuildConfig.DH_PUBLIC_BASE_URL)
     */
    suspend fun login(email: String, nickname: String) {
        val tokens = devAuth.login(email = email, displayName = nickname)
        devTokens = tokens
        DongHaeng.init(
            config = DongHaengConfig(
                publicBaseUrl = BuildConfig.DH_PUBLIC_BASE_URL,
                provider = AccountProvider.DEV,
                appVersion = BuildConfig.VERSION_NAME,
            ),
            tokenProvider = DevTokenProvider { devTokens?.accessToken.orEmpty() },
        )
        _account.value = Account(email, nickname, tokens.user.userId)
        _groups.value = emptyList()
    }

    fun addGroup(result: GroupJoinResult) {
        _groups.update { list -> list.filterNot { it.groupId == result.groupId } + result }
    }

    fun logout() {
        devTokens = null
        _account.value = null
        _groups.value = emptyList()
    }
}

/**
 * dev 로그인용 토큰 제공자 — 호스트 메모리([DemoSession])에 있는 `POST /v1/auth/login` 응답의 accessToken 을 그대로 돌려준다.
 * 모듈은 토큰 형식을 해석하지 않고 `/v1/auth/exchange` 에 실어 보낸다. 로그아웃 뒤에는 빈 문자열 → 모듈이 `Unauthorized` 를 낸다.
 */
class DevTokenProvider(private val current: () -> String) : TokenProvider {
    override suspend fun accessToken(): String = current()
}
