package app.donghaeng.companion.demo.feature.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.donghaeng.companion.demo.DemoSession
import app.donghaeng.companion.demo.DevAuthClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 로그인 화면 상태 — 입력 검증 → 서버 dev 로그인 → 동행 모듈 초기화. 또는 시연 모드 진입 */
class LoginViewModel : ViewModel() {

    data class UiState(
        val loading: Boolean = false,
        val error: String? = null,
        val loggedIn: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun submit(email: String, nickname: String) {
        if (_state.value.loading) return
        val trimmedEmail = email.trim()
        val trimmedNickname = nickname.trim()
        val validation = when {
            trimmedEmail.isEmpty() -> "이메일을 입력해 주세요"
            !EMAIL.matches(trimmedEmail) -> "이메일 형식이 아니에요 (예: rider01@dev.local)"
            trimmedNickname.isEmpty() -> "이름을 입력해 주세요"
            trimmedNickname.length > MAX_NICKNAME -> "이름은 ${MAX_NICKNAME}자 이내로 입력해 주세요"
            else -> null
        }
        if (validation != null) {
            _state.update { it.copy(error = validation) }
            return
        }
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                DemoSession.login(trimmedEmail, trimmedNickname)
                _state.update { it.copy(loading = false, loggedIn = true) }
            } catch (e: DevAuthClient.DevAuthException) {
                _state.update { it.copy(loading = false, error = (e.message ?: "로그인에 실패했어요") + " — 서버가 없으면 '서버 없이 둘러보기'를 누르세요") }
            } catch (e: IllegalArgumentException) {
                _state.update { it.copy(loading = false, error = "서버 주소 설정 오류: ${e.message}") }
            }
        }
    }

    fun enterDemo() {
        DemoSession.enterDemoMode()
        _state.update { it.copy(loading = false, error = null, loggedIn = true) }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    private companion object {
        const val MAX_NICKNAME = 20
        val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    }
}
