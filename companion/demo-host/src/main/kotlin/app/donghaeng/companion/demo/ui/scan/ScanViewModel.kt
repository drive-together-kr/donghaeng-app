package app.donghaeng.companion.demo.ui.scan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.donghaeng.companion.demo.BuildConfig
import app.donghaeng.companion.demo.DemoQrKind
import app.donghaeng.companion.demo.DemoSession
import app.donghaeng.companion.demo.QrPathRule
import app.donghaeng.companion.demo.toUserMessage
import app.donghaeng.companion.sdk.DongHaeng
import app.donghaeng.companion.sdk.DongHaengException
import app.donghaeng.companion.sdk.GroupJoinResult
import app.donghaeng.companion.sdk.PairResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ScanViewModel : ViewModel() {

    sealed interface UiState {
        /** 카메라·붙여넣기 입력을 기다리는 중 */
        data object Scanning : UiState

        /** 그룹 QR 감지 → "그룹 합류" 확인 */
        data class ConfirmJoin(val payload: String) : UiState

        /** 차량 등록 QR 감지 → "차량 등록" 확인 + 별명 입력 */
        data class ConfirmPair(val payload: String) : UiState

        data object Working : UiState
        data class Joined(val result: GroupJoinResult) : UiState
        data class Paired(val result: PairResult) : UiState
        data class Error(val message: String) : UiState
    }

    private val _state = MutableStateFlow<UiState>(UiState.Scanning)
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** 카메라 또는 붙여넣기에서 들어온 QR 문자열. 첫 값만 받고 처리 중에는 무시한다 */
    fun onQrDetected(payload: String) {
        if (_state.value != UiState.Scanning) return
        val trimmed = payload.trim()
        if (trimmed.isEmpty()) return
        // 호스트 앱이 경로만 보고 어느 동행 API 를 부를지 고른다(두 경로 규칙, 모듈 QrParser 와 같은 모양). 호스트·토큰 검증은 모듈 몫
        _state.value = when (QrPathRule.classify(trimmed, BuildConfig.DH_PUBLIC_BASE_URL)) {
            DemoQrKind.JOIN -> UiState.ConfirmJoin(trimmed)
            DemoQrKind.PAIR -> UiState.ConfirmPair(trimmed)
            DemoQrKind.UNKNOWN -> UiState.Error("동행 QR이 아니에요. 차 화면의 그룹 QR 또는 차량 등록 QR을 비춰 주세요")
        }
    }

    fun confirmJoin(payload: String) {
        _state.value = UiState.Working
        viewModelScope.launch {
            try {
                val result = DongHaeng.joinGroup(payload) // vehicleId 생략 → 대표 차량
                DemoSession.addGroup(result)
                _state.value = UiState.Joined(result)
            } catch (e: DongHaengException) {
                _state.value = UiState.Error(e.toUserMessage())
            }
        }
    }

    fun confirmPair(payload: String, nickname: String) {
        _state.value = UiState.Working
        viewModelScope.launch {
            try {
                val result = DongHaeng.pairVehicle(payload, nickname)
                _state.value = UiState.Paired(result)
            } catch (e: DongHaengException) {
                _state.value = UiState.Error(e.toUserMessage())
            }
        }
    }

    /** 대화상자 닫기 → 다시 스캔 대기 */
    fun dismiss() {
        _state.value = UiState.Scanning
    }
}
