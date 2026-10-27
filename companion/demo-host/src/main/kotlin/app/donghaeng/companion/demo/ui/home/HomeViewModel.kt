package app.donghaeng.companion.demo.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.donghaeng.companion.demo.toUserMessage
import app.donghaeng.companion.sdk.DongHaeng
import app.donghaeng.companion.sdk.DongHaengException
import app.donghaeng.companion.sdk.MyVehicle
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class HomeViewModel : ViewModel() {

    data class UiState(
        /** 첫 로딩(아직 목록을 한 번도 못 받음) */
        val loading: Boolean = true,
        /** 목록이 있는 상태에서 다시 읽는 중 */
        val refreshing: Boolean = false,
        val vehicles: List<MyVehicle> = emptyList(),
        val error: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var job: Job? = null

    /** 내 차 목록 — 공식 앱 계정에 연결된 차량(`GET /me/vehicles`, 서버에서 읽는다). 진행 중이면 겹쳐 부르지 않는다 */
    fun refresh() {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            _state.update { if (it.loading) it.copy(error = null) else it.copy(refreshing = true, error = null) }
            try {
                val vehicles = DongHaeng.listMyVehicles()
                _state.update { it.copy(loading = false, refreshing = false, vehicles = vehicles) }
            } catch (e: DongHaengException) {
                _state.update { it.copy(loading = false, refreshing = false, error = e.toUserMessage()) }
            }
        }
    }
}
