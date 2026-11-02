package app.donghaeng.car.feature.drive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.donghaeng.car.R
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.donghaeng.car.core.common.DhLog
import app.donghaeng.car.core.common.DrivingStateMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * 예시 화면 둘러보기(2-1) — 폰(공식 앱)이 없는 심사위원·첫 사용자가 페어링 QR 에서 막히지 않고 그룹 주행 화면을 볼 수 있게 한다.
 * 실제 연결·서버 없이 기존 시안([DesignPreviewHost], 가상 닉네임·장소 샘플)을 순서대로 보여 주고, 위 띠에 "예시 데이터"임을 늘 밝힌다.
 * 순서는 [DemoTour.STEPS]: 정차 대시보드 → 내비 위 패널(주간) → 급제동 강조 → 무전 확인 → 받은 무전 재생. [다음]은 끝에서 처음으로, [닫기]는 뒤로.
 * 정차 중에만 들어올 수 있고(홈 버튼), 둘러보는 중 출발하면 바로 닫고 뒤로 간다 — 실제 대시보드처럼 보이는 예시 데이터가 주행 중 앞 화면에
 * 남지 않게, 주행 중 입력 UI 비활성(CLAUDE.md 규칙 6). 닫히기 전 한 프레임 동안에도 [다음]은 눌리지 않는다.
 */
object DemoTour {
    data class Step(val variant: String, val captionRes: Int)

    val STEPS: List<Step> = listOf(
        Step("parked", R.string.tour_step_parked),
        Step("panel_light", R.string.tour_step_panel),
        Step("panel_danger", R.string.tour_step_danger),
        Step("ptt_confirm", R.string.tour_step_ptt_confirm),
        Step("ptt_playing", R.string.tour_step_ptt_playing),
    )

    /** 다음 단계(끝이면 처음) — 순수 */
    fun next(index: Int): Int = (index + 1).mod(STEPS.size)

    /** 주행 중이면 둘러보기를 닫는다(규칙 6) — 순수 */
    fun closesWhenDriving(isDriving: Boolean): Boolean = isDriving
}

/** 둘러보기의 주행 여부(CarUxRestrictions — [DrivingStateMonitor]). 값을 모르는 동안은 정차로 본다(진입 버튼이 이미 정차 중에만 열린다) */
@HiltViewModel
class DemoTourViewModel @Inject constructor(driving: DrivingStateMonitor) : ViewModel() {
    val isDriving: StateFlow<Boolean> = driving.isDriving().stateIn(viewModelScope, SharingStarted.Eagerly, false)
}

@Composable
fun DemoTourRoute(onClose: () -> Unit, viewModel: DemoTourViewModel = hiltViewModel()) {
    var index by rememberSaveable { mutableIntStateOf(0) }
    val step = DemoTour.STEPS[index.coerceIn(0, DemoTour.STEPS.lastIndex)]
    val driving by viewModel.isDriving.collectAsState()
    LaunchedEffect(driving) {
        if (DemoTour.closesWhenDriving(driving)) {
            DhLog.i("tour: closed (driving)")
            onClose()
        }
    }
    Column(Modifier.fillMaxSize()) {
        TourBand(
            index = index,
            caption = stringResource(step.captionRes),
            enabled = !driving,
            onNext = { index = DemoTour.next(index); DhLog.i("tour: step=${DemoTour.STEPS[index].variant}") },
            onClose = onClose,
        )
        Box(Modifier.weight(1f).fillMaxWidth()) { DesignPreviewHost(step.variant) }
    }
}

/** 위 띠 — "예시 데이터 — 실제 연결 없이 보는 화면 (1/5)" + 지금 화면 설명 + [다음]·[닫기] */
@Composable
private fun TourBand(index: Int, caption: String, enabled: Boolean, onNext: () -> Unit, onClose: () -> Unit) {
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer) {
        Row(Modifier.padding(horizontal = 24.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.tour_band, index + 1, DemoTour.STEPS.size), fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(caption, fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Button(onClick = onNext, enabled = enabled, modifier = Modifier.heightIn(min = 76.dp)) { Text(stringResource(R.string.tour_next), fontSize = 24.sp) }
            OutlinedButton(onClick = onClose, modifier = Modifier.heightIn(min = 76.dp)) { Text(stringResource(R.string.tour_close), fontSize = 24.sp) }
        }
    }
}
