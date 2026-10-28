package app.donghaeng.car.feature.groupdrive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.donghaeng.car.adapter.trigger.PttTriggerSource
import app.donghaeng.car.adapter.trigger.VoiceTriggerAdapter
import app.donghaeng.car.core.common.DhLog
import app.donghaeng.car.core.common.MicPermission
import app.donghaeng.car.domain.ptt.PttController
import app.donghaeng.car.domain.ptt.PttSettings
import app.donghaeng.car.domain.ptt.VoicePlayback
import app.donghaeng.car.domain.session.MqttSessionManager
import app.donghaeng.car.feature.drive.PttDashboardInput
import app.donghaeng.car.service.DriveSessionController
import app.donghaeng.car.surface.SurfaceState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 정차 대시보드의 무전(F16) — 화면 상태와 버튼 동작. 그룹 주행 화면(GroupDriveRoute)이 [GroupDriveViewModel] 옆에 하나 더 둔다
 * (기존 ViewModel·테스트를 건드리지 않으려고 따로 뺐다).
 *
 * - [input] = 무전 상태(`SurfaceState.ptt` — 패널과 같은 값) + 보내지 않은 무전 + 지난 무전 + 설정 '핸들 버튼으로 무전' → `DriveDashboardMapper.map(…, ptt = input)`.
 * - [press] = 트리거 `DASHBOARD`(설정과 무관, 주행 중에도 — 입력은 버튼 하나뿐).
 * - [resend]·[deleteUnsent]·[replay] 는 **정차 중에만**(목록 자체가 정차 블록에만 보이고, PttController 도 주행 중이면 거절한다).
 * - [onMicResult] = 마이크 권한 다이얼로그 결과 → 무전 버튼 "마이크 권한 필요" 갱신 + 포그라운드 서비스 타입(microphone) 갱신.
 */
@HiltViewModel
class PttDashboardViewModel @Inject constructor(
    private val surface: SurfaceState,
    private val controller: PttController,
    private val playback: VoicePlayback,
    private val trigger: VoiceTriggerAdapter,
    settings: PttSettings,
    private val mic: MicPermission,
    private val sessionManager: MqttSessionManager,
    private val driveSession: DriveSessionController,
) : ViewModel() {

    val input: StateFlow<PttDashboardInput> = combine(
        surface.ptt,
        controller.unsent,
        // 재생 위치가 100 ms 마다 바뀌어도 목록이 바뀔 때만 다시 그린다
        playback.state.map { it.past }.distinctUntilChanged(),
        settings.handleButton,
    ) { ptt, unsent, past, handle -> PttDashboardInput(ptt, unsent, past, handle) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PttDashboardInput(ptt = surface.ptt.value))

    /** 하단 [무전] */
    fun press() = trigger.press(PttTriggerSource.DASHBOARD)

    fun resend(id: String) {
        viewModelScope.launch {
            val started = controller.resend(id)
            DhLog.i("ptt: dashboard resend id=$id started=$started")
        }
    }

    fun deleteUnsent(id: String) {
        viewModelScope.launch {
            val deleted = controller.deleteUnsent(id)
            DhLog.i("ptt: dashboard delete id=$id deleted=$deleted")
        }
    }

    /** '지난 무전' 다시 듣기 — 정차 중에만(주행 중 탭 입력 금지, CLAUDE.md 규칙 6) */
    fun replay(voiceId: String) {
        if (surface.isDriving.value) {
            DhLog.i("ptt: dashboard replay refused (driving — 정차 중에만)")
            return
        }
        viewModelScope.launch {
            val queued = playback.replay(voiceId)
            DhLog.i("ptt: dashboard replay voiceId=$voiceId queued=$queued")
        }
    }

    /** 대시보드 [권한 열기] 결과(정차 중) — 다이얼로그가 안 떠도(다시 묻지 않음) 설정 화면에서 돌아오면 [refreshMic] 이 다시 읽는다 */
    fun onMicResult(granted: Boolean) {
        DhLog.i("ptt: mic permission result granted=$granted")
        refreshMic()
        // 포그라운드 서비스에 microphone 타입을 붙여야 순정 내비가 앞일 때도 녹음이 무음이 되지 않는다(DriveSessionService)
        if (granted) driveSession.refreshType(sessionManager.session.value)
    }

    /** 화면 복귀·권한 결과 뒤 마이크 권한을 다시 읽는다 */
    fun refreshMic() {
        mic.refresh()
        controller.refreshPermissions()
    }
}
