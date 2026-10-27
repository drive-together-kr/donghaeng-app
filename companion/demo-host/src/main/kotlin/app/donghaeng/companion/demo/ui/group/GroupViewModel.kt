package app.donghaeng.companion.demo.ui.group

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.donghaeng.companion.demo.DemoSession
import app.donghaeng.companion.demo.toUserMessage
import app.donghaeng.companion.sdk.DongHaeng
import app.donghaeng.companion.sdk.DongHaengException
import app.donghaeng.companion.sdk.GroupSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** (4) 그룹 화면 상태 — observeGroup 구독 + 동승자 메시지 전송 */
class GroupViewModel(savedStateHandle: SavedStateHandle) : ViewModel() {

    data class UiState(
        val groupId: String,
        val name: String?,
        val snapshot: GroupSnapshot? = null,
        val error: String? = null,
        val sending: Boolean = false,
        val sentCount: Int = 0,
    )

    private val groupId: String = checkNotNull(savedStateHandle["groupId"]) { "groupId 인자가 없습니다" }

    private val _state = MutableStateFlow(
        UiState(
            groupId = groupId,
            name = DemoSession.groups.value.firstOrNull { it.groupId == groupId }?.name,
        ),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                DongHaeng.observeGroup(groupId)
                    .catch { e ->
                        val message = (e as? DongHaengException)?.toUserMessage() ?: (e.message ?: "그룹 상태를 받지 못했어요")
                        _state.update { it.copy(error = message) }
                    }
                    .collect { snapshot -> _state.update { it.copy(snapshot = snapshot, error = null) } }
            } catch (e: DongHaengException) {
                _state.update { it.copy(error = e.toUserMessage()) }
            }
        }
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _state.value.sending) return
        viewModelScope.launch {
            _state.update { it.copy(sending = true) }
            try {
                DongHaeng.sendPassengerMessage(groupId, trimmed)
                _state.update { it.copy(sending = false, sentCount = it.sentCount + 1, error = null) }
            } catch (e: DongHaengException) {
                _state.update { it.copy(sending = false, error = e.toUserMessage()) }
            }
        }
    }
}
