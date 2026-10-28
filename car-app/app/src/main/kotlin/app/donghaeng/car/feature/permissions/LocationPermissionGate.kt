package app.donghaeng.car.feature.permissions

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.donghaeng.car.core.common.DhLog
import app.donghaeng.car.core.common.DrivingStateMonitor
import app.donghaeng.car.core.common.LocationPermissions
import app.donghaeng.car.domain.location.LocationFeed
import app.donghaeng.car.domain.session.MqttSessionManager
import app.donghaeng.car.domain.vehicle.VehicleSignals
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * 위치·알림(+ F16 무전 마이크) 런타임 권한 요청 시점 (docs/05 §1.2): **활성 그룹이 있고 정차 상태일 때 1회**. 그 전에는 묻지 않는다.
 * 거부돼도 기능은 계속된다 — 위치 체인이 다음 순위(Fake)로 내려가고 속도는 위치 계산값을 쓴다. 마이크가 거부되면 무전 보내기만 안 된다.
 */
@HiltViewModel
class LocationPermissionViewModel @Inject constructor(
    private val locationFeed: LocationFeed,
    private val vehicleSignals: VehicleSignals,
    private val sessionManager: MqttSessionManager,
    drivingMonitor: DrivingStateMonitor,
    @ApplicationContext private val context: Context,
    private val ptt: app.donghaeng.car.domain.ptt.PttController,
    private val driveSession: app.donghaeng.car.service.DriveSessionController,
) : ViewModel() {

    @Volatile
    private var asked = false

    /** 지금 요청해야 하는가 */
    val shouldAsk: StateFlow<Boolean> = combine(sessionManager.session, drivingMonitor.isDriving()) { session, driving ->
        session != null && !driving && !asked && LocationPermissions.missing(context).isNotEmpty()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun markAsked() {
        asked = true
    }

    fun onResult(result: Map<String, Boolean>) {
        DhLog.i("location permissions: ${result.entries.joinToString { "${it.key.substringAfterLast('.')}=${it.value}" }}")
        locationFeed.restart()
        vehicleSignals.restart()   // CAR_SPEED 승인 → Car API 속도 재구독
        ptt.refreshPermissions()   // F16: RECORD_AUDIO 결과 → 무전 버튼 "마이크 권한 필요" 갱신
        // 앱이 앞에 있는 지금 포그라운드 서비스 타입을 새 권한에 맞춘다(location·microphone — 내비가 앞일 때도 위치·무전 녹음이 되게)
        driveSession.refreshType(sessionManager.session.value)
    }
}

/** NavHost 옆에 한 번 두면 조건이 맞을 때 시스템 권한 다이얼로그를 띄운다 */
@Composable
fun LocationPermissionGate(viewModel: LocationPermissionViewModel = hiltViewModel()) {
    val shouldAsk by viewModel.shouldAsk.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { viewModel.onResult(it) }
    LaunchedEffect(shouldAsk) {
        if (shouldAsk) {
            viewModel.markAsked()
            DhLog.i("location permissions: 그룹 활성 + 정차 → 요청")
            launcher.launch(LocationPermissions.runtimePermissions(context))
        }
    }
}
