package app.donghaeng.companion.demo.donghaeng

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.donghaeng.companion.demo.R
import app.donghaeng.companion.demo.ui.components.AccentButton
import app.donghaeng.companion.demo.ui.components.KiaChip
import app.donghaeng.companion.demo.ui.components.KiaTag
import app.donghaeng.companion.demo.ui.components.MsIcon
import app.donghaeng.companion.demo.ui.components.SubCard
import app.donghaeng.companion.demo.ui.theme.KiaColors
import app.donghaeng.companion.sdk.DongHaengException
import app.donghaeng.companion.sdk.GroupJoinResult
import kotlinx.coroutines.launch

/**
 * 삽입 지점 A — "그룹 합류" 바텀시트. 그룹명·방장·목적지·인원(초대 미리보기) + "어떤 차로 함께 갈까요?"(내 차 목록) → [함께 가기] → joinGroup.
 *
 * 차 고르기 규칙은 [JoinVehiclePolicy]: 이 그룹의 방장 차·참여를 꺼 둔 차는 고를 수 없고, 고를 수 있는 차가 한 대일 때만 미리 고른다
 * (D093 — 예전엔 대표 차량 = 방장 차를 기본으로 골라 409, D026 — 같은 계정을 쓰는 다른 사람의 대표 차를 고를 수 있었다).
 * 시트를 열 때마다 내 차 목록을 다시 불러오고, 불러오는 중·실패를 '차 없음'과 구분해 보여 준다(D080).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JoinGroupSheet(
    qrPayload: String,
    onDismiss: () -> Unit,
    onJoined: (GroupJoinResult) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val vehicles by DongHaengStore.vehicles.collectAsStateWithLifecycle()
    val vehiclesLoading by DongHaengStore.vehiclesLoading.collectAsStateWithLifecycle()
    val vehiclesError by DongHaengStore.vehiclesError.collectAsStateWithLifecycle()
    val participation by DongHaengStore.participation.collectAsStateWithLifecycle()
    val activeGroup by DongHaengStore.activeGroup.collectAsStateWithLifecycle()
    var preview by remember { mutableStateOf<InvitePreview?>(null) }
    var previewLoading by remember { mutableStateOf(true) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(qrPayload) {
        // 목록은 열 때마다 새로(다른 기기에서 해제·추가됐을 수 있다) — 미리보기와 함께
        DongHaengStore.refreshVehicles()
        previewLoading = true
        preview = DongHaengStore.previewInvite(qrPayload)
        previewLoading = false
    }
    val off = participation.filterValues { !it }.keys
    val options = JoinVehiclePolicy.options(vehicles, preview?.leaderVehicleId, off)
    LaunchedEffect(options) {
        selectedId = JoinVehiclePolicy.selection(options, selectedId)
    }
    val selected = options.firstOrNull { it.selectable && it.vehicle.vehicleId == selectedId }?.vehicle

    ModalBottomSheet(
        onDismissRequest = { if (!busy) onDismiss() },
        sheetState = sheetState,
        containerColor = KiaColors.Card,
        dragHandle = { Box(Modifier.padding(top = 12.dp).size(width = 40.dp, height = 4.dp).clip(CircleShape).background(KiaColors.SubCard)) },
    ) {
        Column(
            Modifier
                .padding(horizontal = 24.dp)
                .padding(top = 12.dp, bottom = 24.dp)
                .navigationBarsPadding(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MsIcon(R.drawable.ms_groups, null, tint = KiaColors.Accent, size = 26.dp)
                Spacer(Modifier.width(10.dp))
                Text("동행 그룹 합류", style = MaterialTheme.typography.headlineSmall)
            }
            Spacer(Modifier.height(16.dp))

            SubCard {
                when {
                    previewLoading -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = KiaColors.Accent)
                        Spacer(Modifier.width(10.dp))
                        Text("초대 정보를 확인하는 중…", style = MaterialTheme.typography.bodyMedium, color = KiaColors.TextSecondary)
                    }
                    preview != null -> {
                        val p = preview!!
                        Text(p.name, style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(10.dp))
                        InfoRow(R.drawable.ms_flag, "방장", p.leaderNickname)
                        InfoRow(R.drawable.ms_location_on, "목적지", p.destinationName)
                        InfoRow(R.drawable.ms_group, "인원", "${p.memberCount}대 함께 가는 중 · 최대 ${p.maxMembers}대")
                    }
                    else -> {
                        Text("동행 그룹 초대", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(6.dp))
                        Text("초대 정보를 미리 볼 수 없어요. 그래도 합류를 시도할 수 있어요.", style = MaterialTheme.typography.bodySmall, color = KiaColors.TextSecondary)
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Text("어떤 차로 함께 갈까요?", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            when {
                vehicles.isEmpty() && vehiclesLoading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = KiaColors.Accent)
                    Spacer(Modifier.width(10.dp))
                    Text("내 차 목록을 불러오는 중…", style = MaterialTheme.typography.bodyMedium, color = KiaColors.TextSecondary)
                }
                vehicles.isEmpty() && vehiclesError != null -> {
                    Text("내 차 목록을 불러오지 못했어요: $vehiclesError", style = MaterialTheme.typography.bodyMedium, color = KiaColors.Dot)
                    Spacer(Modifier.height(8.dp))
                    KiaChip("다시 불러오기", icon = R.drawable.ms_refresh, onClick = { DongHaengStore.refreshVehicles() })
                }
                vehicles.isEmpty() -> Text(
                    "계정에 연결된 차가 없어요. 차량 화면의 등록 QR(또는 코드)을 먼저 등록해 주세요.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = KiaColors.TextSecondary,
                )
                options.none { it.selectable } -> Text(
                    "고를 수 있는 차가 없어요. 이 그룹의 방장 차이거나 '동행 그룹 주행' 설정에서 참여를 꺼 둔 차예요.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = KiaColors.TextSecondary,
                )
                selected == null -> Text("함께 갈 차를 골라 주세요.", style = MaterialTheme.typography.bodySmall, color = KiaColors.TextSecondary)
            }
            options.forEach { option ->
                val v = option.vehicle
                val isSelected = selected?.vehicleId == v.vehicleId
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (isSelected) KiaColors.SubCard else KiaColors.Card)
                        .border(1.dp, if (isSelected) KiaColors.Accent else KiaColors.SubCard, RoundedCornerShape(16.dp))
                        .clickable(enabled = !busy && option.selectable) { selectedId = v.vehicleId }
                        .alpha(if (option.selectable) 1f else 0.45f)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MsIcon(R.drawable.ms_directions_car, null, tint = if (isSelected) KiaColors.Accent else KiaColors.TextSecondary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(v.nickname, style = MaterialTheme.typography.titleSmall)
                        Text(listOf(v.brand, v.model).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = KiaColors.TextSecondary)
                    }
                    KiaTag(JoinVehiclePolicy.tag(option))
                }
            }

            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = KiaColors.Dot, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(20.dp))
            AccentButton(
                text = if (busy) "합류하는 중…" else "함께 가기",
                enabled = !busy && selected != null,
            ) {
                val vehicle = selected ?: return@AccentButton
                busy = true
                error = null
                scope.launch {
                    try {
                        val result = DongHaengStore.join(qrPayload, vehicle, destinationName = preview?.destinationName.orEmpty())
                        onJoined(result)
                    } catch (e: DongHaengException) {
                        error = e.toUserText()
                    } catch (e: Exception) {
                        error = "합류에 실패했어요 (${e.javaClass.simpleName})"
                    } finally {
                        busy = false
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "합류하면 선택한 차의 화면에 그룹이 뜨고, 위치·도착 예정 시간·잔량이 그룹과 공유돼요. " +
                    "그 차가 다른 그룹에 있었다면 그 그룹에서 빠져요(방장이었으면 다른 차가 이어받아요).",
                style = MaterialTheme.typography.labelSmall,
                color = KiaColors.TextTertiary,
            )
            activeGroup?.let { current ->
                Spacer(Modifier.height(4.dp))
                Text(
                    "이 폰은 지금 '${current.name}' 그룹을 보고 있어요. 다른 그룹에 합류하면 그 그룹은 지난 그룹으로 옮겨요.",
                    style = MaterialTheme.typography.labelSmall,
                    color = KiaColors.TextTertiary,
                )
            }
        }
    }
}

@Composable
private fun InfoRow(icon: Int, label: String, value: String) {
    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        MsIcon(icon, null, tint = KiaColors.TextSecondary, size = 18.dp)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = KiaColors.TextSecondary, modifier = Modifier.width(52.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
