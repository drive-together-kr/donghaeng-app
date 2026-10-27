package app.donghaeng.companion.demo.donghaeng

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.donghaeng.companion.demo.R
import app.donghaeng.companion.demo.ui.components.Disclaimer
import app.donghaeng.companion.demo.ui.components.KiaCard
import app.donghaeng.companion.demo.ui.components.KiaTopBar
import app.donghaeng.companion.demo.ui.components.ListRow
import app.donghaeng.companion.demo.ui.components.RowDivider
import app.donghaeng.companion.demo.ui.components.SectionHeader
import app.donghaeng.companion.demo.ui.components.demoToast
import app.donghaeng.companion.demo.ui.theme.KiaColors

/**
 * 삽입 지점 E — 마이카 > 연동 서비스 > "동행 그룹 주행": 공유 항목(위치/연료/급제동), 차량별 참여 설정, 지난 그룹 기록.
 * 공유 설정은 데모에서는 호스트 메모리에만 저장된다(차량 앱의 공유 설정과 동기화는 추후).
 */
@Composable
fun DongHaengSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val sharing by DongHaengStore.sharing.collectAsStateWithLifecycle()
    val vehicles by DongHaengStore.vehicles.collectAsStateWithLifecycle()
    val participation by DongHaengStore.participation.collectAsStateWithLifecycle()
    val past by DongHaengStore.pastGroups.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .background(KiaColors.Bg),
    ) {
        KiaTopBar(title = "동행 그룹 주행", onBack = onBack)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            Text("그룹에 공유할 내 차 정보를 고르세요. 꺼 둔 항목은 그룹 멤버에게 보이지 않아요.", style = MaterialTheme.typography.bodyMedium, color = KiaColors.TextSecondary)
            Spacer(Modifier.height(20.dp))

            SectionHeader("공유 항목")
            KiaCard(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp)) {
                ToggleRow(R.drawable.ms_location_on, "위치·도착 예정 시간", "지도와 홈 카드에 내 차 위치와 ETA 표시", sharing.location) { DongHaengStore.updateSharing { s -> s.copy(location = it) } }
                RowDivider()
                ToggleRow(R.drawable.ms_local_gas_station, "연료·배터리 잔량", "15% 이하가 되면 그룹에 음성으로 알림", sharing.energy) { DongHaengStore.updateSharing { s -> s.copy(energy = it) } }
                RowDivider()
                ToggleRow(R.drawable.ms_warning, "급제동 알림", "급제동 시 뒤따르는 차에 음성 안내", sharing.hardBrake) { DongHaengStore.updateSharing { s -> s.copy(hardBrake = it) } }
            }

            Spacer(Modifier.height(28.dp))
            SectionHeader("차량별 참여 설정")
            KiaCard(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp)) {
                if (vehicles.isEmpty()) {
                    Text("계정에 연결된 차가 없어요", style = MaterialTheme.typography.bodyMedium, color = KiaColors.TextSecondary, modifier = Modifier.padding(vertical = 16.dp))
                }
                vehicles.forEachIndexed { i, v ->
                    if (i > 0) RowDivider()
                    ToggleRow(
                        R.drawable.ms_directions_car,
                        v.nickname,
                        (if (v.isPrimary) "대표 차량" else "공유받은 차량") + " · 동행 그룹 합류 허용",
                        participation[v.vehicleId] ?: true,
                    ) { DongHaengStore.setParticipation(v.vehicleId, it) }
                }
            }

            Spacer(Modifier.height(28.dp))
            SectionHeader("지난 그룹 기록", action = "전체 삭제", actionChevron = false, onAction = { demoToast(context, "기록 삭제") })
            KiaCard(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp)) {
                if (past.isEmpty()) {
                    Text("지난 그룹이 없어요", style = MaterialTheme.typography.bodyMedium, color = KiaColors.TextSecondary, modifier = Modifier.padding(vertical = 16.dp))
                }
                past.forEachIndexed { i, p ->
                    if (i > 0) RowDivider()
                    ListRow(
                        title = p.name,
                        subtitle = "${p.dateText} · ${p.memberCount}대 · ${p.destinationName.ifBlank { "목적지 기록 없음" }}",
                        icon = R.drawable.ms_history,
                        onClick = { demoToast(context, "지난 그룹 상세") },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("위치 기록은 그룹 종료 시 서버에서 즉시 삭제되고, 이 폰에는 그룹 이름·날짜만 남아요.", style = MaterialTheme.typography.labelSmall, color = KiaColors.TextTertiary)
            Spacer(Modifier.height(8.dp))
            Disclaimer()
        }
    }
}

@Composable
private fun ToggleRow(icon: Int, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListRow(
        title = title,
        subtitle = subtitle,
        icon = icon,
        showChevron = false,
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = onChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = KiaColors.Bg,
                    checkedTrackColor = KiaColors.Accent,
                    uncheckedThumbColor = KiaColors.TextSecondary,
                    uncheckedTrackColor = KiaColors.SubCard,
                    uncheckedBorderColor = KiaColors.SubCard,
                ),
            )
        },
    )
}
