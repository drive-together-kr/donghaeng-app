package app.donghaeng.companion.demo.donghaeng

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.donghaeng.companion.demo.R
import app.donghaeng.companion.demo.ui.components.AccentButton
import app.donghaeng.companion.demo.ui.components.Disclaimer
import app.donghaeng.companion.demo.ui.components.KiaButton
import app.donghaeng.companion.demo.ui.components.KiaCard
import app.donghaeng.companion.demo.ui.components.KiaTag
import app.donghaeng.companion.demo.ui.components.KiaTopBar
import app.donghaeng.companion.demo.ui.components.ListRow
import app.donghaeng.companion.demo.ui.components.MsIcon
import app.donghaeng.companion.demo.ui.components.RowDivider
import app.donghaeng.companion.demo.ui.components.demoToast
import app.donghaeng.companion.demo.ui.theme.KiaColors

/**
 * 삽입 지점 C — 홈 서비스 바로가기 "동행"이 여는 동행 메인: 진행 중 그룹 / 지난 그룹 / 사용법 탭.
 */
@Composable
fun DongHaengMainScreen(
    onBack: () -> Unit,
    onScan: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val group by DongHaengStore.activeGroup.collectAsStateWithLifecycle()
    val snapshot by DongHaengStore.snapshot.collectAsStateWithLifecycle()
    val notifications by DongHaengStore.notifications.collectAsStateWithLifecycle()
    val past by DongHaengStore.pastGroups.collectAsStateWithLifecycle()
    val mode by DongHaengStore.mode.collectAsStateWithLifecycle()
    var showMessage by rememberSaveable { mutableStateOfFalse() }

    Column(
        Modifier
            .fillMaxSize()
            .background(KiaColors.Bg),
    ) {
        KiaTopBar(title = "동행", onBack = onBack, onQr = onScan, onSettings = onOpenSettings)
        // 탭 — 기아 제어 화면의 밑줄 탭 모양
        Row(Modifier.padding(horizontal = 20.dp)) {
            listOf("진행 중 그룹", "지난 그룹", "사용법").forEachIndexed { i, label ->
                Column(
                    Modifier
                        .width(IntrinsicSize.Max)
                        .clickable { tab = i }
                        .padding(end = 24.dp),
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (tab == i) KiaColors.TextPrimary else KiaColors.TextSecondary,
                        fontWeight = if (tab == i) FontWeight.Bold else FontWeight.Medium,
                        modifier = Modifier.padding(vertical = 10.dp),
                    )
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .background(if (tab == i) KiaColors.TextPrimary else androidx.compose.ui.graphics.Color.Transparent),
                    )
                }
            }
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 20.dp),
        ) {
            when (tab) {
                0 -> {
                    val g = group
                    if (g == null) {
                        KiaCard {
                            MsIcon(R.drawable.ms_groups, null, tint = KiaColors.Accent, size = 36.dp)
                            Spacer(Modifier.height(12.dp))
                            Text("참여 중인 그룹이 없어요", style = MaterialTheme.typography.titleLarge)
                            Spacer(Modifier.height(6.dp))
                            Text("선두차 화면의 동행 QR 을 스캔하면 내 차가 그룹에 합류하고, 위치·도착 시간을 함께 봐요.", style = MaterialTheme.typography.bodyMedium, color = KiaColors.TextSecondary)
                            Spacer(Modifier.height(16.dp))
                            AccentButton("QR 스캔해서 합류하기", onClick = onScan)
                        }
                    } else {
                        ActiveGroupCard(group = g, snapshot = snapshot, latestNotification = notifications.firstOrNull(), onSendMessage = { showMessage = true }, onOpen = {})
                        Spacer(Modifier.height(12.dp))
                        KiaCard {
                            Text("최근 소식", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(6.dp))
                            if (notifications.isEmpty()) {
                                Text("아직 소식이 없어요", style = MaterialTheme.typography.bodyMedium, color = KiaColors.TextSecondary)
                            }
                            notifications.take(5).forEachIndexed { i, n ->
                                if (i > 0) RowDivider()
                                Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (n.read) KiaColors.SubCard else KiaColors.Accent))
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(n.title, style = MaterialTheme.typography.labelMedium, color = KiaColors.TextSecondary)
                                        Text(n.body, style = MaterialTheme.typography.bodyMedium)
                                    }
                                    Text(relativeTime(n.atMs), style = MaterialTheme.typography.labelSmall, color = KiaColors.TextSecondary)
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        KiaButton("이 폰에서 그룹 보기 끝내기") {
                            DongHaengStore.leaveLocally()
                            demoToast(context, "그룹 보기를 끝냈어요. 차량은 차에서 '나가기'를 누르세요")
                        }
                    }
                    if (mode == DhMode.FAKE) {
                        Spacer(Modifier.height(12.dp))
                        Text("시연 모드: 서버 없이 호스트 쪽 가짜 데이터로 움직이고 있어요.", style = MaterialTheme.typography.labelSmall, color = KiaColors.TextTertiary)
                    }
                }
                1 -> {
                    if (past.isEmpty()) {
                        Text("지난 그룹이 없어요", color = KiaColors.TextSecondary)
                    }
                    KiaCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 4.dp)) {
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
                }
                else -> {
                    val steps = listOf(
                        Triple(R.drawable.ms_qr_code_scanner, "1. 선두차가 그룹을 만들면 차량 화면에 QR 이 떠요", "선두차의 동행 앱에서 목적지를 고르고 '같이 가기'를 누르면 초대 QR 이 생겨요."),
                        Triple(R.drawable.ms_directions_car, "2. 이 앱의 QR 스캔으로 찍고 함께 갈 차를 고르세요", "상단 QR 아이콘 → 그룹 합류 시트에서 내 차(대표/공유받은 차량)를 선택하면 끝."),
                        Triple(R.drawable.ms_location_on, "3. 홈·지도에서 그룹을 함께 봐요", "홈의 '동행 중' 카드와 지도의 '동행' 칩으로 멤버 위치·도착 예정 시간·잔량을 확인해요."),
                        Triple(R.drawable.ms_chat_bubble, "4. 운전자에게는 음성으로", "동승자 메시지와 그룹 소식은 차량에서 음성으로 읽혀서 운전 중에도 안전해요."),
                    )
                    KiaCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 4.dp)) {
                        steps.forEachIndexed { i, (icon, title, body) ->
                            if (i > 0) RowDivider()
                            ListRow(title = title, subtitle = body, icon = icon, showChevron = false, titleStyle = MaterialTheme.typography.titleSmall)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    KiaCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            KiaTag("공식 앱 삽입 지점")
                            Spacer(Modifier.width(8.dp))
                            Text("A 스캔 · B 홈 카드 · C 동행 메인 · D 지도 · E 설정 · F 알림 · G 메시지", style = MaterialTheme.typography.labelSmall, color = KiaColors.TextSecondary)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Disclaimer()
        }
    }

    if (showMessage && group != null) {
        PassengerMessageSheet(onDismiss = { showMessage = false })
    }
}

private fun mutableStateOfFalse() = androidx.compose.runtime.mutableStateOf(false)
