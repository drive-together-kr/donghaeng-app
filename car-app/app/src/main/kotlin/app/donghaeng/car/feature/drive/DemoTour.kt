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
import app.donghaeng.car.core.common.DhLog

/**
 * 예시 화면 둘러보기(2-1) — 폰(공식 앱)이 없는 심사위원·첫 사용자가 페어링 QR 에서 막히지 않고 그룹 주행 화면을 볼 수 있게 한다.
 * 실제 연결·서버 없이 기존 시안([DesignPreviewHost], 가상 닉네임·장소 샘플)을 순서대로 보여 주고, 위 띠에 "예시 데이터"임을 늘 밝힌다.
 * 순서는 [DemoTour.STEPS]: 정차 대시보드 → 내비 위 패널(주간) → 급제동 강조 → 무전 확인 → 받은 무전 재생. [다음]은 끝에서 처음으로, [닫기]는 뒤로.
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
}

@Composable
fun DemoTourRoute(onClose: () -> Unit) {
    var index by rememberSaveable { mutableIntStateOf(0) }
    val step = DemoTour.STEPS[index.coerceIn(0, DemoTour.STEPS.lastIndex)]
    Column(Modifier.fillMaxSize()) {
        TourBand(
            index = index,
            caption = stringResource(step.captionRes),
            onNext = { index = DemoTour.next(index); DhLog.i("tour: step=${DemoTour.STEPS[index].variant}") },
            onClose = onClose,
        )
        Box(Modifier.weight(1f).fillMaxWidth()) { DesignPreviewHost(step.variant) }
    }
}

/** 위 띠 — "예시 데이터 — 실제 연결 없이 보는 화면 (1/5)" + 지금 화면 설명 + [다음]·[닫기] */
@Composable
private fun TourBand(index: Int, caption: String, onNext: () -> Unit, onClose: () -> Unit) {
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer) {
        Row(Modifier.padding(horizontal = 24.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.tour_band, index + 1, DemoTour.STEPS.size), fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(caption, fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Button(onClick = onNext, modifier = Modifier.heightIn(min = 76.dp)) { Text(stringResource(R.string.tour_next), fontSize = 24.sp) }
            OutlinedButton(onClick = onClose, modifier = Modifier.heightIn(min = 76.dp)) { Text(stringResource(R.string.tour_close), fontSize = 24.sp) }
        }
    }
}
