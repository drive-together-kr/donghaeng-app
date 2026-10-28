package app.donghaeng.car.feature.groupdrive

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.ui.platform.LocalContext
import app.donghaeng.car.BuildConfig
import app.donghaeng.car.R
import app.donghaeng.car.core.common.DhLog
import app.donghaeng.car.domain.session.GroupSession
import app.donghaeng.car.feature.drive.DriveDashboardMapper
import app.donghaeng.car.feature.drive.DriveDashboardScreen
import app.donghaeng.car.surface.NaviLauncher
import app.donghaeng.car.surface.OverlayPermissionMonitor
import app.donghaeng.car.ui.theme.DongHaengTheme
import app.donghaeng.protocol.GroupTopics
import app.donghaeng.protocol.MemberStatus
import app.donghaeng.protocol.Place
import app.donghaeng.protocol.Severity
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * 그룹 주행 화면 진입점 (T25 → 2026-10-28 정차 대시보드로 교체, docs/05 §5.1).
 *
 * 그룹이 있으면 [DriveDashboardScreen](실데이터 = [DriveDashboardMapper]) 위에 선두 경로 제안 카드(T28)를 띄운다. 그룹이 없으면 기존 화면의 "끝났어요" 패널.
 * 초대 QR·나가기/종료(확인 다이얼로그)는 정차 블록 '그룹 관리'(그룹 생성 화면 Active)에 있다. [내비 보기] = 순정 내비를 앞으로([NaviLauncher]: 순정 내비 패키지 → 순정 내비로 풀리는 지도 selector → pleos 는 NaviHelper 화면 요청).
 * [무전](F16) = [PttDashboardViewModel.press](트리거 DASHBOARD) — 녹음·확인·보냄·재생 상태와 보내지 않은 무전·지난 무전도 그 ViewModel 이 준다.
 * 마이크 권한이 없으면 정차 중에만 [권한 열기] — 다이얼로그(다시 묻지 않음이면 앱 정보 화면)를 띄우고, 화면에 돌아올 때마다 권한을 다시 읽는다.
 */
@Composable
fun GroupDriveRoute(
    onBack: () -> Unit,
    onOpenInvite: () -> Unit,
    onOpenEvents: () -> Unit = {},
    onManageGroup: () -> Unit = onOpenInvite,
    onOpenSettings: () -> Unit = {},
    viewModel: GroupDriveViewModel = hiltViewModel(),
    pttViewModel: PttDashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val ptt by pttViewModel.input.collectAsStateWithLifecycle()
    val dashboard = remember(state, ptt) { DriveDashboardMapper.map(state, ptt = ptt) }
    if (dashboard == null) {
        GroupDriveScreen(state = state, onBack = onBack, onOpenInvite = onOpenInvite, onEndOrLeave = { viewModel.endOrLeave() }, onClearError = viewModel::clearError, onOpenEvents = onOpenEvents, onAcceptProposal = viewModel::acceptProposal, onDeclineProposal = viewModel::declineProposal)
        return
    }
    val context = LocalContext.current
    val overlaySettings = remember(dashboard.overlayNotice) { if (dashboard.overlayNotice) OverlayPermissionMonitor.settingsIntent(context) else null }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pttViewModel.onMicResult(granted)
        // 다시 묻지 않음(이미 두 번 거부) — 다이얼로그 없이 바로 거부가 온다 → 앱 정보 화면(있을 때만)
        val activity = context as? android.app.Activity
        if (!granted && activity != null && !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)) {
            MicPermissionScreen.openAppSettings(context)
        }
    }
    // 설정 화면에서 돌아오면 권한을 다시 읽는다
    LifecycleResumeEffect(Unit) {
        pttViewModel.refreshMic()
        onPauseOrDispose { }
    }
    Box(Modifier.fillMaxSize()) {
        DriveDashboardScreen(
            ui = dashboard,
            onPtt = pttViewModel::press,
            onOpenNavi = { if (!NaviLauncher.bringToFront(context, pleosBuild = BuildConfig.ADAPTER == "pleos")) viewModel.showNavigationFallback() },
            onManageGroup = onManageGroup,
            onSettings = onOpenSettings,
            onOpenEvents = onOpenEvents,
            onHome = onBack,
            onOpenOverlaySettings = overlaySettings?.let { intent -> { runCatching { context.startActivity(intent) }.onFailure { DhLog.w("group drive: 오버레이 설정 열기 실패 ${it.javaClass.simpleName}") } } },
            onResendUnsent = pttViewModel::resend,
            onDeleteUnsent = pttViewModel::deleteUnsent,
            onReplayPast = pttViewModel::replay,
            onOpenMicPermission = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
        )
        state.proposal?.let { p ->
            ProposalCard(state, p, viewModel::acceptProposal, viewModel::declineProposal, Modifier.align(Alignment.TopCenter).padding(top = 24.dp, start = 24.dp, end = 24.dp).widthIn(max = 960.dp))
        }
    }
    state.error?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::clearError,
            title = { Text(stringResource(R.string.drive_error_title)) },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = viewModel::clearError) { Text(stringResource(R.string.drive_ok)) } },
        )
    }
}

/**
 * 그룹 주행 화면 (T25, docs/05 §5): 멤버 리스트 + Compose Canvas 경량 지도. 가로(차량 센터 디스플레이)는 좌 리스트 / 우 지도, 세로는 위 지도 / 아래 리스트.
 * 주행 중에는 입력(나가기·종료·초대 QR)을 막고 보기만 허용한다(docs/05 §4).
 */
@Composable
fun GroupDriveScreen(
    state: GroupDriveUiState,
    onBack: () -> Unit,
    onOpenInvite: () -> Unit,
    onEndOrLeave: () -> Unit,
    onClearError: () -> Unit,
    onOpenEvents: () -> Unit = {},
    onAcceptProposal: () -> Unit = {},
    onDeclineProposal: () -> Unit = {},
) {
    var confirmLeave by remember { mutableStateOf(false) }
    Scaffold { innerPadding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(innerPadding)) {
            val landscape = maxWidth > maxHeight
            Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Header(state, onBack)
                if (state.session == null) {
                    EndedPane(onBack)
                } else if (landscape) {
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        MemberPane(state, Modifier.weight(1f).fillMaxHeight())
                        MapPane(state, Modifier.weight(1.15f).fillMaxHeight())
                    }
                } else {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        MapPane(state, Modifier.fillMaxWidth().weight(0.9f))
                        MemberPane(state, Modifier.fillMaxWidth().weight(1.1f))
                    }
                }
                if (state.session != null) {
                    state.proposal?.let { ProposalCard(state, it, onAcceptProposal, onDeclineProposal) }
                    EventsStrip(state, onOpenEvents)
                    ActionBar(state, onOpenInvite, onLeaveClick = { confirmLeave = true })
                }
            }
        }
    }
    if (confirmLeave) {
        val leader = state.session?.isLeader == true
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(if (leader) stringResource(R.string.drive_confirm_end_title) else stringResource(R.string.drive_confirm_leave_title)) },
            text = { Text(if (leader) stringResource(R.string.drive_confirm_end_text) else stringResource(R.string.drive_confirm_leave_text)) },
            confirmButton = { TextButton(onClick = { confirmLeave = false; onEndOrLeave() }) { Text(if (leader) stringResource(R.string.group_btn_end) else stringResource(R.string.group_btn_leave)) } },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text(stringResource(R.string.drive_cancel)) } },
        )
    }
    state.error?.let { message ->
        AlertDialog(
            onDismissRequest = onClearError,
            title = { Text(stringResource(R.string.drive_error_title)) },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = onClearError) { Text(stringResource(R.string.drive_ok)) } },
        )
    }
}

@Composable
private fun Header(state: GroupDriveUiState, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = onBack, enabled = !state.isDriving, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.group_back)) }
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text(state.session?.name ?: stringResource(R.string.drive_title), style = MaterialTheme.typography.headlineMedium)
            state.session?.let { s ->
                Text(
                    "${stringResource(R.string.group_destination)}: ${s.destination.name}" + (state.me?.etaText?.let { "   ·   ${stringResource(R.string.drive_my_eta)} $it" } ?: ""),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        ConnectionChip(state)
    }
}

@Composable
private fun ConnectionChip(state: GroupDriveUiState) {
    val (label, color) = when (state.connection) {
        ConnState.CONNECTED -> stringResource(R.string.drive_conn_connected) to MaterialTheme.colorScheme.secondary
        ConnState.RECONNECTING -> stringResource(R.string.drive_conn_reconnecting) to MaterialTheme.colorScheme.tertiary
        ConnState.OFFLINE -> stringResource(R.string.drive_conn_offline) to MaterialTheme.colorScheme.error
    }
    Column(horizontalAlignment = Alignment.End) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.titleMedium, color = color)
        }
        Text(
            state.snapshotAgeS?.let { stringResource(R.string.drive_snapshot_age, it) } ?: stringResource(R.string.drive_snapshot_none),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // T31 지연 오버레이: 서버 발행 ts → 수신(시계 오프셋 보정) — 설정에서 끌 수 있다
        state.latencyText?.let { Text(stringResource(R.string.drive_latency, it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary) }
    }
}

@Composable
private fun MemberPane(state: GroupDriveUiState, modifier: Modifier) {
    Card(modifier = modifier) {
        Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.group_members, state.members.size), style = MaterialTheme.typography.titleMedium)
            HorizontalDivider()
            if (state.members.isEmpty()) {
                Text(stringResource(R.string.drive_waiting_snapshot), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            state.members.forEach { MemberRow(it) }
        }
    }
}

@Composable
private fun MemberRow(m: MemberUi) {
    val dim = !m.online || m.status == MemberStatus.OFFLINE
    val textColor = if (dim) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Box(Modifier.size(20.dp).clip(CircleShape).background(if (dim) Color.Gray else Color(m.colorArgb)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(m.nickname, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = textColor)
                if (m.isLeader) Tag(stringResource(R.string.group_role_leader), MaterialTheme.colorScheme.primary)
                if (m.isMe) Tag(stringResource(R.string.drive_me), MaterialTheme.colorScheme.secondary)
            }
            val line2 = listOfNotNull(
                m.speedKph?.let { "%.0f km/h".format(it) },
                if (m.isLeader) null else m.distanceToLeaderM?.let { stringResource(R.string.drive_to_leader, formatDistance(it)) },
                m.etaText?.let { "ETA $it" },
                m.energyText,
            ).joinToString("  ·  ")
            if (line2.isNotEmpty()) Text(line2, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        StatusTag(m)
    }
}

@Composable
private fun Tag(text: String, color: Color) {
    Spacer(Modifier.width(8.dp))
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = color,
        modifier = Modifier.background(color.copy(alpha = 0.12f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun StatusTag(m: MemberUi) {
    val (label, color) = when (m.status) {
        MemberStatus.DRIVING -> stringResource(R.string.drive_status_driving) to MaterialTheme.colorScheme.secondary
        MemberStatus.STOPPED -> stringResource(R.string.drive_status_stopped) to MaterialTheme.colorScheme.tertiary
        MemberStatus.ARRIVED -> stringResource(R.string.drive_status_arrived) to MaterialTheme.colorScheme.primary
        MemberStatus.OFFLINE -> stringResource(R.string.drive_status_offline) to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(horizontalAlignment = Alignment.End) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = color)
        m.ageS?.let { Text(stringResource(R.string.drive_age, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun MapPane(state: GroupDriveUiState, modifier: Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Box(Modifier.fillMaxSize()) {
            GroupMapCanvas(
                members = state.members,
                myLat = state.myLat,
                myLon = state.myLon,
                myHeading = state.myHeading,
                destination = state.session?.destination,
                modifier = Modifier.fillMaxSize(),
            )
            Text(
                stringResource(R.string.drive_map_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp),
            )
        }
    }
}

/** T28 — 선두 경로 제안: 따라가기/아니요. 주행 중에는 버튼을 막고 30초 뒤 자동 거절(남은 초 표시) */
@Composable
internal fun ProposalCard(state: GroupDriveUiState, p: app.donghaeng.car.domain.routesync.RouteProposal, onAccept: () -> Unit, onDecline: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.drive_proposal_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(p.speech, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            val route = (p.waypoints.map { it.name } + p.destination.name).joinToString(" → ")
            Text(route, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onAccept, enabled = !state.isDriving, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.drive_proposal_accept)) }
                OutlinedButton(onClick = onDecline, enabled = !state.isDriving, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.drive_proposal_decline)) }
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.drive_proposal_countdown, state.proposalRemainingS ?: 0), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

@Composable
private fun EventsStrip(state: GroupDriveUiState, onOpenEvents: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.group_recent_events), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onOpenEvents) { Text(stringResource(R.string.drive_btn_events)) }
            }
            if (state.events.isEmpty()) Text(stringResource(R.string.events_empty_short), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.events.take(3).forEach { e ->
                val color = when (e.severity) {
                    Severity.ALERT -> MaterialTheme.colorScheme.error
                    Severity.WARN -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.onSurface
                }
                Text("• ${e.speech ?: app.donghaeng.car.feature.events.EventsViewModel.labelOf(e.type)}", style = MaterialTheme.typography.bodyLarge, color = color)   // 문구 없는 이벤트(ROUTE_CHANGED 등)는 한국어 라벨
            }
        }
    }
}

@Composable
private fun ActionBar(state: GroupDriveUiState, onOpenInvite: () -> Unit, onLeaveClick: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.session?.isLeader == true) {
            Button(onClick = onOpenInvite, enabled = !state.isDriving, modifier = Modifier.weight(1f).heightIn(min = 60.dp)) { Text(stringResource(R.string.drive_btn_invite)) }
        }
        OutlinedButton(onClick = onLeaveClick, enabled = !state.isDriving && !state.busy, modifier = Modifier.weight(1f).heightIn(min = 60.dp)) {
            Text(if (state.session?.isLeader == true) stringResource(R.string.group_btn_end) else stringResource(R.string.group_btn_leave))
        }
    }
    if (state.isDriving) {
        Text(stringResource(R.string.drive_locked_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EndedPane(onBack: () -> Unit) {
    Card {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.drive_ended_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.drive_ended_text), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onBack, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.group_back)) }
        }
    }
}

// ---------------------------------------------------------------- 경량 지도

/**
 * Compose Canvas 경량 지도: 선두차(없으면 나, 없으면 첫 멤버)를 중심으로 북쪽 위, 모든 멤버가 들어오게 자동 축척.
 * 거리 링·멤버 점(색)·진행 방향 삼각형·별명·내 차 테두리·목적지(화면 밖이면 가장자리 화살표 + 거리)를 그린다. 타일 없음(오프라인·저사양 OK).
 */
@Composable
fun GroupMapCanvas(
    members: List<MemberUi>,
    myLat: Double?,
    myLon: Double?,
    myHeading: Float?,
    destination: Place?,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val onSurface = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val primary = MaterialTheme.colorScheme.primary
    val labelStyle = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, color = onSurface)
    val smallStyle = TextStyle(fontSize = 12.sp, color = muted)
    val emptyText = stringResource(R.string.drive_map_empty)

    Canvas(modifier) {
        val positioned = members.filter { it.lat != null && it.lon != null }
        val center = positioned.firstOrNull { it.isLeader } ?: positioned.firstOrNull { it.isMe } ?: positioned.firstOrNull()
        val centerLat = center?.lat ?: myLat
        val centerLon = center?.lon ?: myLon
        if (centerLat == null || centerLon == null) {
            val layout = textMeasurer.measure(emptyText, labelStyle)
            drawText(layout, topLeft = Offset(size.width / 2 - layout.size.width / 2, size.height / 2 - layout.size.height / 2))
            return@Canvas
        }
        val points = positioned.map { it.lat!! to it.lon!! } + listOfNotNull(if (myLat != null && myLon != null) myLat to myLon else null)
        val proj = GroupMapProjection.fit(centerLat, centerLon, points, size.width, size.height)
        val cx = size.width / 2
        val cy = size.height / 2

        // 거리 링
        val step = proj.ringStepM()
        for (k in 1..4) {
            val r = step * k / proj.metersPerPx
            if (r > hypot(size.width, size.height)) break
            drawCircle(muted.copy(alpha = 0.25f), radius = r, center = Offset(cx, cy), style = Stroke(1.dp.toPx()))
            val label = textMeasurer.measure(formatDistance(step * k), smallStyle)
            drawText(label, topLeft = Offset(cx + 4.dp.toPx(), cy - r - label.size.height))
        }
        // 북쪽 표시
        val north = textMeasurer.measure("N", smallStyle)
        drawText(north, topLeft = Offset(size.width - north.size.width - 10.dp.toPx(), 8.dp.toPx()))

        // 목적지
        destination?.let { d ->
            val p = proj.toPoint(d.lat, d.lon)
            val inset = 26.dp.toPx()
            if (proj.contains(p, 0f)) {
                drawFlag(Offset(p.x, p.y), primary)
                val label = textMeasurer.measure(d.name, labelStyle)
                drawText(label, topLeft = Offset(p.x + 10.dp.toPx(), p.y - label.size.height))
            } else {
                val e = proj.edgePointToward(p, inset)
                val angle = Math.toDegrees(atan2((p.y - cy).toDouble(), (p.x - cx).toDouble())).toFloat()
                drawArrow(Offset(e.x, e.y), angle, primary)
                val (ex, ny) = proj.offsetM(d.lat, d.lon)
                val distM = hypot(ex, ny).roundToInt()
                val label = textMeasurer.measure("${d.name} ${formatDistance(distM)}", smallStyle.copy(color = primary))
                val lx = (e.x - label.size.width / 2).coerceIn(4f, size.width - label.size.width - 4f)
                val ly = (if (e.y < cy) e.y + 14.dp.toPx() else e.y - 14.dp.toPx() - label.size.height).coerceIn(4f, size.height - label.size.height - 4f)
                drawText(label, topLeft = Offset(lx, ly))
            }
        }

        // 멤버
        positioned.forEach { m ->
            val p = proj.toPoint(m.lat!!, m.lon!!)
            val dim = !m.online || m.status == MemberStatus.OFFLINE
            val color = if (dim) Color.Gray else Color(m.colorArgb)
            val radius = 12.dp.toPx()
            if (m.isMe) drawCircle(onSurface, radius = radius + 6.dp.toPx(), center = Offset(p.x, p.y), style = Stroke(3.dp.toPx()))
            drawCircle(color, radius = radius, center = Offset(p.x, p.y))
            m.heading?.let { h -> drawHeading(Offset(p.x, p.y), h, radius, color) }
            if (m.isLeader) drawCircle(Color.White, radius = radius * 0.35f, center = Offset(p.x, p.y))
            val name = (if (m.isLeader) "★ " else "") + m.nickname
            val label = textMeasurer.measure(name, labelStyle.copy(color = if (dim) muted else onSurface))
            drawText(label, topLeft = Offset(p.x - label.size.width / 2, p.y + radius + 4.dp.toPx()))
        }
        // 스냅샷에 아직 내 위치가 없으면 내 위치를 빈 원으로
        if (myLat != null && myLon != null && positioned.none { it.isMe }) {
            val p = proj.toPoint(myLat, myLon)
            drawCircle(onSurface, radius = 12.dp.toPx(), center = Offset(p.x, p.y), style = Stroke(3.dp.toPx()))
            myHeading?.let { h -> drawHeading(Offset(p.x, p.y), h, 12.dp.toPx(), onSurface) }
        }
    }
}

private fun DrawScope.drawHeading(center: Offset, headingDeg: Float, radius: Float, color: Color) {
    rotate(headingDeg, pivot = center) {
        val path = Path().apply {
            moveTo(center.x, center.y - radius * 2.1f)
            lineTo(center.x - radius * 0.6f, center.y - radius * 1.1f)
            lineTo(center.x + radius * 0.6f, center.y - radius * 1.1f)
            close()
        }
        drawPath(path, color)
    }
}

private fun DrawScope.drawFlag(at: Offset, color: Color) {
    val h = 22.dp.toPx()
    drawLine(color, start = at, end = Offset(at.x, at.y - h), strokeWidth = 3.dp.toPx())
    val flag = Path().apply {
        moveTo(at.x, at.y - h)
        lineTo(at.x + h * 0.7f, at.y - h * 0.78f)
        lineTo(at.x, at.y - h * 0.55f)
        close()
    }
    drawPath(flag, color)
    drawCircle(color, radius = 4.dp.toPx(), center = at)
}

private fun DrawScope.drawArrow(at: Offset, angleDeg: Float, color: Color) {
    rotate(angleDeg, pivot = at) {
        val s = 11.dp.toPx()
        val path = Path().apply {
            moveTo(at.x + s, at.y)
            lineTo(at.x - s * 0.7f, at.y - s * 0.75f)
            lineTo(at.x - s * 0.3f, at.y)
            lineTo(at.x - s * 0.7f, at.y + s * 0.75f)
            close()
        }
        drawPath(path, color)
    }
}

internal fun formatDistance(meters: Int): String = if (meters >= 1_000) "%.1f km".format(meters / 1_000.0) else "$meters m"

@Preview(name = "그룹 주행 가로", widthDp = 1280, heightDp = 720)
@Composable
private fun GroupDrivePreview() {
    val session = GroupSession(
        groupId = "grp_PREVIEW", name = "추석 귀성길", leaderVehicleId = "veh_A", destination = Place(35.1595, 126.8526, "광주송정역"),
        topics = GroupTopics("s", "e"), joinedAtMs = 0L, isLeader = true,
    )
    val members = listOf(
        MemberUi("veh_A", "아빠 차", 0xFF2E7DFF, isLeader = true, isMe = true, online = true, status = MemberStatus.DRIVING, lat = 36.3504, lon = 127.3845, heading = 200f, speedKph = 92f, distanceToLeaderM = 0, etaText = "182.0 km · 1시간 50분", energyText = "연료 34%", ageS = 1),
        MemberUi("veh_B", "엄마 차", 0xFFFF6B2E, isLeader = false, isMe = false, online = true, status = MemberStatus.DRIVING, lat = 36.3560, lon = 127.3890, heading = 195f, speedKph = 88f, distanceToLeaderM = 760, etaText = "183.2 km · 1시간 52분", energyText = "배터리 61%", ageS = 2),
        MemberUi("veh_C", "삼촌 차", 0xFF00A86B, isLeader = false, isMe = false, online = false, status = MemberStatus.OFFLINE, lat = 36.3700, lon = 127.3800, heading = null, speedKph = null, distanceToLeaderM = 2200, etaText = null, energyText = null, ageS = 95),
    )
    DongHaengTheme {
        GroupDriveScreen(
            state = GroupDriveUiState(session = session, members = members, me = members[0], leader = members[0], connection = ConnState.CONNECTED, snapshotAgeS = 1,
                events = listOf(EventUi("e1", "LOW_ENERGY", Severity.WARN, "엄마 차 연료가 12% 남았어요.", 0L))),
            onBack = {}, onOpenInvite = {}, onEndOrLeave = {}, onClearError = {},
        )
    }
}

/** 마이크 권한을 다시 묻지 않는 상태일 때 앱 정보 화면(권한 → 마이크) — 기기에 그 화면이 없으면 로그만 */
internal object MicPermissionScreen {
    fun openAppSettings(context: android.content.Context) {
        val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null))
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        // resolveActivity 는 패키지 가시성(<queries>) 때문에 null 일 수 있어 바로 열고 실패를 받는다(ActivityNotFoundException — Pleos 에 화면이 없을 수 있다)
        runCatching { context.startActivity(intent) }.onFailure { DhLog.w("ptt: 앱 정보 화면 열기 실패 ${it.javaClass.simpleName} — 마이크 권한은 adb pm grant 로(docs/09 무전)") }
    }
}
