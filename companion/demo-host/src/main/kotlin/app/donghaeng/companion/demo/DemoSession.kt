package app.donghaeng.companion.demo

import app.donghaeng.companion.sdk.AccountProvider
import app.donghaeng.companion.sdk.DongHaeng
import app.donghaeng.companion.sdk.DongHaengConfig
import app.donghaeng.companion.sdk.GroupJoinResult
import app.donghaeng.companion.sdk.TokenProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 호스트 앱(공식 앱)이 원래 갖고 있는 것들을 흉내 낸 메모리 상태: 로그인한 계정, 합류한 그룹 목록.
 * 동행 모듈은 이 중 **토큰**만 [TokenProvider] 로 받아 간다.
 */
object DemoSession {

    data class Account(val email: String, val nickname: String)

    private val _account = MutableStateFlow<Account?>(null)
    val account: StateFlow<Account?> = _account.asStateFlow()

    private val _groups = MutableStateFlow<List<GroupJoinResult>>(emptyList())
    val groups: StateFlow<List<GroupJoinResult>> = _groups.asStateFlow()

    /**
     * 공식 앱의 로그인 성공 시점에 해당. dev 로그인 토큰은 서버 `DevTokenVerifier` 가 검증하는 `POST /v1/auth/login` 응답의
     * accessToken 이어야 한다(docs/07 §4.2). 서버 identity 가 없는 T13 전까지는 임시 placeholder 토큰으로 동행 모듈을 초기화하고,
     * 모듈은 이때 메모리 Fake 로 동작한다. 실제 공식 앱이라면 여기서 자기 OAuth 2.0 토큰 저장소를 감싼 [TokenProvider] 를 넘긴다.
     */
    fun login(email: String, nickname: String) {
        val tokenProvider = DevTokenProvider(email)
        DongHaeng.init(
            config = DongHaengConfig(
                publicBaseUrl = BuildConfig.DH_PUBLIC_BASE_URL,
                provider = AccountProvider.DEV,
                appVersion = BuildConfig.VERSION_NAME,
            ),
            tokenProvider = tokenProvider,
        )
        _account.value = Account(email, nickname)
        _groups.value = emptyList()
    }

    fun addGroup(result: GroupJoinResult) {
        _groups.update { list -> list.filterNot { it.groupId == result.groupId } + result }
    }

    fun logout() {
        _account.value = null
        _groups.value = emptyList()
    }
}

/**
 * dev 로그인용 토큰 제공자 — 임시 placeholder 문자열을 돌려준다. 계약상 dev 토큰은 `POST /v1/auth/login` 응답의 accessToken(JWT)이며
 * 아래 문자열 형식은 어떤 문서·서버 코드도 정의하지 않는다(모듈은 형식을 해석하지 않고 Bearer 로만 싣는다).
 * TODO(T13): `POST {base}/v1/auth/login {provider:"dev", email, displayName}` 응답의 accessToken 으로 교체(호스트 메모리에만 보관).
 */
class DevTokenProvider(private val email: String) : TokenProvider {
    override suspend fun accessToken(): String = "dev:$email"
}
