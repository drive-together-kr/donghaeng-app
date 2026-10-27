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
     * 공식 앱의 로그인 성공 시점에 해당. 네트워크 없이 dev 토큰(`dev:<email>`)을 만들고 동행 모듈을 초기화한다.
     * 실제 공식 앱이라면 여기서 자기 OAuth 2.0 토큰 저장소를 감싼 [TokenProvider] 를 넘긴다.
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

/** dev 로그인용 토큰 제공자 — `dev:<email>` 을 그대로 돌려준다(서버 identity 의 dev 제공자와 짝). */
class DevTokenProvider(private val email: String) : TokenProvider {
    override suspend fun accessToken(): String = "dev:$email"
}
