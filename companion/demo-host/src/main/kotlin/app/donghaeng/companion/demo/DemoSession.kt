package app.donghaeng.companion.demo

import app.donghaeng.companion.demo.donghaeng.DhMode
import app.donghaeng.companion.demo.donghaeng.DongHaengStore
import app.donghaeng.companion.demo.donghaeng.FakeGateway
import app.donghaeng.companion.demo.donghaeng.SdkGateway
import app.donghaeng.companion.sdk.AccountProvider
import app.donghaeng.companion.sdk.DongHaeng
import app.donghaeng.companion.sdk.DongHaengConfig
import app.donghaeng.companion.sdk.TokenProvider
import app.donghaeng.protocol.rest.TokenResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 호스트 앱(공식 앱)이 원래 갖고 있는 것들을 흉내 낸 메모리 상태: 로그인한 계정과 그 토큰.
 * 동행 모듈은 이 중 **토큰**만 [TokenProvider] 로 받아 간다. 토큰은 메모리에만 두고 저장하지 않는다.
 * 그룹 상태는 [DongHaengStore] 가 들고 있다(동행 삽입 지점의 상태).
 */
object DemoSession {

    data class Account(val email: String, val nickname: String, val userId: String, val demo: Boolean)

    private val _account = MutableStateFlow<Account?>(null)
    val account: StateFlow<Account?> = _account.asStateFlow()

    /** 서버 `POST /v1/auth/login` 응답(호스트 앱의 계정 토큰). 메모리에만 보관 */
    @Volatile
    private var devTokens: TokenResponse? = null

    private val devAuth: DevAuthClient by lazy { DevAuthClient(BuildConfig.DH_PUBLIC_BASE_URL) }

    /** 홈 상단에 보일 대표 차량 이름(시연용 더미) */
    const val VEHICLE_NAME = "쏘렌토 HEV(시연)"
    const val USER_NAME = "홍길동"

    /**
     * 공식 앱의 로그인 성공 시점에 해당. 서버 dev 로그인으로 토큰을 받고(docs/07 §4.2), 그 accessToken 을 돌려주는 [TokenProvider] 로
     * 동행 모듈을 초기화한다 — 모듈은 이 토큰을 `POST /v1/auth/exchange {provider:"dev", accessToken}` 으로 동행 JWT 와 교환한다.
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
        DongHaengStore.configure(DhMode.SDK, SdkGateway(BuildConfig.DH_PUBLIC_BASE_URL))
        _account.value = Account(email, nickname, tokens.user.userId, demo = false)
    }

    /** 서버 없이 화면만 둘러보기 — 호스트 쪽 가짜 게이트웨이로 동행 기능이 움직인다 */
    fun enterDemoMode() {
        devTokens = null
        DongHaengStore.configure(DhMode.FAKE, FakeGateway())
        _account.value = Account("demo@kia.local", USER_NAME, "usr_demo", demo = true)
    }

    fun logout() {
        devTokens = null
        DongHaengStore.reset()
        _account.value = null
    }
}

/**
 * dev 로그인용 토큰 제공자 — 호스트 메모리([DemoSession])에 있는 `POST /v1/auth/login` 응답의 accessToken 을 그대로 돌려준다.
 * 모듈은 토큰 형식을 해석하지 않고 `/v1/auth/exchange` 에 실어 보낸다. 로그아웃 뒤에는 빈 문자열 → 모듈이 `Unauthorized` 를 낸다.
 */
class DevTokenProvider(private val current: () -> String) : TokenProvider {
    override suspend fun accessToken(): String = current()
}
