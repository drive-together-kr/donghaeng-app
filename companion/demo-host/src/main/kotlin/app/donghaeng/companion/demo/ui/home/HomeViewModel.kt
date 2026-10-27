package app.donghaeng.companion.demo.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.donghaeng.companion.demo.toUserMessage
import app.donghaeng.companion.sdk.DongHaeng
import app.donghaeng.companion.sdk.DongHaengException
import app.donghaeng.companion.sdk.MyVehicle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class HomeViewModel : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val vehicles: List<MyVehicle> = emptyList(),
        val error: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** 내 차 목록 — 공식 앱 계정에 연결된 차량(`GET /me/vehicles`) */
    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val vehicles = DongHaeng.listMyVehicles()
                _state.update { it.copy(loading = false, vehicles = vehicles) }
            } catch (e: DongHaengException) {
                _state.update { it.copy(loading = false, error = e.toUserMessage()) }
            }
        }
    }
}
