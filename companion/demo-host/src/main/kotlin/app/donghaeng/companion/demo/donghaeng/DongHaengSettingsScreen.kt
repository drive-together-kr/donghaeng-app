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
import app.donghaeng.companion.demo.ui.components.KiaChip
import app.donghaeng.companion.demo.ui.components.KiaTopBar
import app.donghaeng.companion.demo.ui.components.ListRow
import app.donghaeng.companion.demo.ui.components.RowDivider
import app.donghaeng.companion.demo.ui.components.SectionHeader
import app.donghaeng.companion.demo.ui.components.demoToast
import app.donghaeng.companion.demo.ui.theme.KiaColors

/**
 * 삽입 지점 E — 마이카 > 연동 서비스 > "동행 그룹 주행": 공유 항목(위치/연료/급제동), 차량별 참여 설정, 지난 그룹 기록.
 *
 * 정직한 표시(D078·D092·D116): 항목별 공유 끄기는 아직 차량·서버 어디에도 없다(PRD §7 의 '공유 항목별 on/off' 는 미구현) —
 * 그래서 공유 항목 스위치는 **켜진 채 비활성**으로 두고 '준비 중'이라고 쓴다(예전엔 끄면 "멤버에게 보이지 않아요"라고 했지만 그대로 공유됐다).
 * 차량별 참여는 이 폰 안에서 실제로 동작한다 — 끈 차는 그룹 합류 시트에서 고를 수 없다([JoinVehiclePolicy], 이 설치에 저장).
 */
@Composable
fun DongHaengSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val vehicles by DongHaengStore.vehicles.collectAsStateWithLifecycle()
    val vehiclesError by DongHaengStore.vehiclesError.collectAsStateWithLifecycle()
    val vehiclesLoading by DongHaengStore.vehiclesLoading.collectAsStateWithLifecycle()
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
            Text(
                "그룹에 합류한 차는 위치·도착 예정 시간·잔량·급제동이 그룹에 공유되고, 그룹을 나가거나 그룹이 끝나면 공유가 멈춰요.",
                style = MaterialTheme.typography.bodyMedium,
                color = KiaColors.TextSecondary,
            )
            Spacer(Modifier.height(20.dp))

            SectionHeader("공유 항목")
            KiaCard(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp)) {
                // 반영할 경로가 아직 없다 — 켜진 채 비활성(실제 동작 그대로)으로 보인다
                ToggleRow(R.drawable.ms_location_on, "위치·도착 예정 시간", "지도와 홈 카드에 내 차 위치와 ETA 표시", checked = true, enabled = false) {}
                RowDivider()
                ToggleRow(R.drawable.ms_local_gas_station, "연료·배터리 잔량", "15% 이하가 되면 그룹에 음성으로 알림", checked = true, enabled = false) {}
                RowDivider()
                ToggleRow(R.drawable.ms_warning, "급제동 알림", "급제동 시 뒤따르는 차에 음성 안내", checked = true, enabled = false) {}
            }
            Text(
                "시연용 — 항목별 공유 끄기는 준비 중이라 아직 저장되지 않아요(지금은 모두 공유).",
                style = MaterialTheme.typography.labelSmall,
                color = KiaColors.TextTertiary,
                modifier = Modifier.padding(top = 8.dp, start = 4.dp),
            )

            Spacer(Modifier.height(28.dp))
            SectionHeader("차량별 참여 설정")
            KiaCard(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp)) {
                val listStatus = VehicleListStatus.of(vehicles, vehiclesLoading, vehiclesError)
                if (listStatus != VehicleListStatus.LIST) {
                    Text(
                        when (listStatus) {
                            VehicleListStatus.LOADING -> "내 차 목록을 불러오는 중…"
                            VehicleListStatus.ERROR -> "내 차 목록을 불러오지 못했어요: ${vehiclesError.orEmpty()}"
                            else -> "계정에 연결된 차가 없어요"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (listStatus == VehicleListStatus.ERROR) KiaColors.Dot else KiaColors.TextSecondary,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                    if (listStatus == VehicleListStatus.ERROR) {
                        KiaChip("다시 불러오기", icon = R.drawable.ms_refresh, onClick = { DongHaengStore.refreshVehicles() }, modifier = Modifier.padding(bottom = 12.dp))
                    }
                }
                vehicles.forEachIndexed { i, v ->
                    if (i > 0) RowDivider()
                    ToggleRow(
                        R.drawable.ms_directions_car,
                        v.nickname,
                        (if (v.isPrimary) "대표 차량" else "내 차") + " · 이 폰에서 그룹 합류에 쓰기",
                        participation[v.vehicleId] ?: true,
                    ) { DongHaengStore.setParticipation(v.vehicleId, it) }
                }
            }
            Text(
                "끈 차는 이 폰의 '그룹 합류'에서 고를 수 없어요(이 폰에만 저장).",
                style = MaterialTheme.typography.labelSmall,
                color = KiaColors.TextTertiary,
                modifier = Modifier.padding(top = 8.dp, start = 4.dp),
            )

            Spacer(Modifier.height(28.dp))
            SectionHeader(
                "지난 그룹 기록",
                action = "전체 삭제",
                actionChevron = false,
                onAction = {
                    DongHaengStore.clearPastGroups()
                    demoToast(context, "이 폰의 지난 그룹 기록을 지웠어요")
                },
            )
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
private fun ToggleRow(icon: Int, title: String, subtitle: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    ListRow(
        title = title,
        subtitle = subtitle,
        icon = icon,
        showChevron = false,
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = onChange,
                enabled = enabled,
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
