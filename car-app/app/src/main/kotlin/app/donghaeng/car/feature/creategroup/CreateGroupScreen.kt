package app.donghaeng.car.feature.creategroup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.donghaeng.car.R
import app.donghaeng.car.feature.pairing.QrCodeImage
import app.donghaeng.car.ui.components.DestinationArt
import app.donghaeng.car.ui.components.DestinationBanner
import app.donghaeng.car.ui.components.DestinationPhotoCard
import app.donghaeng.car.ui.components.DhCard
import app.donghaeng.car.ui.components.DhIcons
import app.donghaeng.car.ui.components.DhPrimaryButton
import app.donghaeng.car.ui.components.DhSecondaryButton
import app.donghaeng.car.ui.components.RoadSign
import app.donghaeng.car.ui.theme.DhPalette
import app.donghaeng.protocol.Place
import app.donghaeng.protocol.rest.MemberDto

@Composable
fun CreateGroupRoute(
    onBack: () -> Unit,
    onOpenDrive: () -> Unit = {},
    /** 홈 사진 줄에서 고른 추천 목적지 이름 — 입력 화면이 처음 뜰 때 한 번 골라 둔다 */
    presetName: String? = null,
    viewModel: CreateGroupViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var presetApplied by rememberSaveable { mutableStateOf(false) }
    val isForm = state is CreateGroupState.Form
    LaunchedEffect(isForm, presetName) {
        if (isForm && !presetApplied && presetName != null) {
            DESTINATION_PRESETS.firstOrNull { it.name == presetName }?.let(viewModel::pickPreset)
            presetApplied = true
        }
    }
    CreateGroupScreen(
        state = state,
        onBack = onBack,
        onLoadNavi = viewModel::loadNaviDestination,
        onPickPreset = viewModel::pickPreset,
        onName = viewModel::setName,
        onMaxMembers = viewModel::setMaxMembers,
        onCreate = viewModel::create,
        onReissue = viewModel::reissueInvite,
        onEndOrLeave = viewModel::endOrLeave,
        onRefresh = viewModel::refreshMembers,
        onOpenDrive = onOpenDrive,
    )
}

/**
 * 그룹 생성(T17) — 정차 중 화면('밤 고속도로' 디자인, docs/05 §5.0).
 * 만들기 전: 목적지 사진 카드(추천 5곳) 또는 [내비에서 불러오기] → 고른 목적지는 고속도로 표지로 → 이름·정원 → [같이 가기 시작].
 * 만든 뒤: 왼쪽 초대 QR(흰 판 위, 서버가 준 `invite.url` 그대로 — docs/05 §7)·남은 시간, 오른쪽 목적지 표지·멤버·[그룹 주행 화면 보기].
 * 멤버(GROUP_JOINED 로 들어온 차)는 QR 없이 멤버 목록만.
 */
@Composable
fun CreateGroupScreen(
    state: CreateGroupState,
    onBack: () -> Unit,
    onLoadNavi: () -> Unit,
    onPickPreset: (Place) -> Unit,
    onName: (String) -> Unit,
    onMaxMembers: (Int) -> Unit,
    onCreate: () -> Unit,
    onReissue: () -> Unit,
    onEndOrLeave: () -> Unit,
    onRefresh: () -> Unit,
    onOpenDrive: () -> Unit = {},
) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { innerPadding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(innerPadding)) {
            val wide = maxWidth >= 840.dp
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = if (wide) 48.dp else 28.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = onBack, modifier = Modifier.heightIn(min = 76.dp), shape = MaterialTheme.shapes.medium) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(26.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.group_back), color = MaterialTheme.colorScheme.onSurface)
                    }
                    Spacer(Modifier.width(24.dp))
                    Text(
                        when (state) {
                            is CreateGroupState.Active -> state.session.name
                            else -> stringResource(R.string.group_title)
                        },
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                when (state) {
                    CreateGroupState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                        Spacer(Modifier.width(16.dp))
                        Text(stringResource(R.string.group_loading), style = MaterialTheme.typography.titleMedium)
                    }
                    is CreateGroupState.Form -> FormContent(state, wide, onLoadNavi, onPickPreset, onName, onMaxMembers, onCreate)
                    is CreateGroupState.Active -> ActiveContent(state, wide, onReissue, onEndOrLeave, onRefresh, onOpenDrive)
                }
            }
        }
    }
}

@Composable
private fun FormContent(
    form: CreateGroupState.Form,
    wide: Boolean,
    onLoadNavi: () -> Unit,
    onPickPreset: (Place) -> Unit,
    onName: (String) -> Unit,
    onMaxMembers: (Int) -> Unit,
    onCreate: () -> Unit,
) {
    if (form.notPaired) {
        Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.large) {
            Text(stringResource(R.string.group_not_paired), modifier = Modifier.padding(20.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
    // 1) 어디로 — 사진 카드(추천) + 내비에서 불러오기
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.group_where_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.group_presets), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DhSecondaryButton(stringResource(R.string.group_btn_load_navi), onLoadNavi, enabled = !form.loadingNavi && !form.isDriving, icon = DhIcons.navigation)
    }
    // 사진 카드 줄 — FlowRow 는 그림의 원래 폭으로 줄을 나눠 카드가 한 줄에 하나씩 커졌다(2026-11-05) → 고정 열 수로 직접 나눈다
    val columns = if (wide) 5 else 2
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        DESTINATION_PRESETS.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { place ->
                    val selected = !form.fromNavi && form.destination?.name == place.name
                    val art = DestinationArt.forName(place.name)
                    if (art != null) {
                        DestinationPhotoCard(
                            name = place.name,
                            image = art,
                            selected = selected,
                            onClick = { onPickPreset(place) },
                            enabled = !form.isDriving,
                            subtitle = place.address?.split(' ')?.take(2)?.joinToString(" "),
                            modifier = Modifier.weight(1f).aspectRatio(if (wide) 0.95f else 1.5f),
                        )
                    } else {
                        FilterChip(selected = selected, enabled = !form.isDriving, onClick = { onPickPreset(place) }, label = { Text(place.name) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp))
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
    // 2) 고른 목적지(표지) + 이름·정원 + 만들기
    val summary: @Composable ColumnScope.() -> Unit = {
        Text(stringResource(R.string.group_destination), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val dest = form.destination
        val sub = when {
            form.loadingNavi -> stringResource(R.string.group_destination_loading)
            dest == null -> null
            form.fromNavi -> stringResource(R.string.group_destination_from_navi)
            else -> dest.address ?: stringResource(R.string.group_destination_preset)
        }
        val title = dest?.name?.takeIf { it.isNotBlank() } ?: if (dest != null) stringResource(R.string.group_destination_from_navi) else stringResource(R.string.group_destination_none)
        RoadSign(title, subtitle = sub?.takeIf { it != title }, muted = dest == null, modifier = Modifier.fillMaxWidth())
    }
    val details: @Composable ColumnScope.() -> Unit = {
        OutlinedTextField(
            value = form.name,
            onValueChange = onName,
            label = { Text(stringResource(R.string.group_name)) },
            singleLine = true,
            enabled = !form.isDriving,
            textStyle = MaterialTheme.typography.titleMedium,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(stringResource(R.string.group_max_members), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MAX_MEMBER_CHOICES.forEach { n ->
                FilterChip(
                    selected = form.maxMembers == n,
                    enabled = !form.isDriving,
                    onClick = { onMaxMembers(n) },
                    label = { Text(stringResource(R.string.group_max_members_n, n), style = MaterialTheme.typography.titleSmall) },
                    shape = MaterialTheme.shapes.small,
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = DhPalette.signGreen,
                        selectedLabelColor = Color.White,
                    ),
                    modifier = Modifier.heightIn(min = 56.dp),
                )
            }
        }
    }
    if (wide) {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            DhCard(Modifier.weight(1f), content = details)
            DhCard(Modifier.weight(1f)) {
                summary()
                CreateButton(form, onCreate)
            }
        }
    } else {
        DhCard(Modifier.fillMaxWidth(), content = summary)
        DhCard(Modifier.fillMaxWidth(), content = details)
        CreateButton(form, onCreate)
    }
    form.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge) }
    if (form.isDriving) Text(stringResource(R.string.group_driving_hint), color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.group_create_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun CreateButton(form: CreateGroupState.Form, onCreate: () -> Unit) {
    // 목적지를 아직 안 골라 버튼이 꺼져 있으면 왜 꺼졌는지 말한다
    if (form.needsDestination) Text(stringResource(R.string.group_create_need_destination), color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.titleMedium)
    if (form.creating) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.group_loading), style = MaterialTheme.typography.titleMedium)
        }
    }
    DhPrimaryButton(stringResource(R.string.group_btn_create), onCreate, Modifier.fillMaxWidth(), enabled = form.canCreate, icon = DhIcons.qr)
}

@Composable
private fun ActiveContent(
    active: CreateGroupState.Active,
    wide: Boolean,
    onReissue: () -> Unit,
    onEndOrLeave: () -> Unit,
    onRefresh: () -> Unit,
    onOpenDrive: () -> Unit,
) {
    if (wide) {
        // 선두(초대 QR 있음): 왼쪽 QR · 오른쪽 목적지·멤버. 멤버 차: 왼쪽 큰 목적지 사진 · 오른쪽 멤버
        val leader = active.session.isLeader
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                if (!leader) DestinationHeader(active, tall = true)
                InvitePane(active, onReissue)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                if (leader) DestinationHeader(active)
                DhPrimaryButton(stringResource(R.string.group_btn_open_drive), onOpenDrive, Modifier.fillMaxWidth(), icon = DhIcons.navigation)
                MembersPane(active, onEndOrLeave, onRefresh)
            }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            DestinationHeader(active)
            DhPrimaryButton(stringResource(R.string.group_btn_open_drive), onOpenDrive, Modifier.fillMaxWidth(), icon = DhIcons.navigation)
            InvitePane(active, onReissue)
            MembersPane(active, onEndOrLeave, onRefresh)
        }
    }
}

/** 목적지 — 사진(있으면) 위에 고속도로 표지 */
@Composable
private fun DestinationHeader(active: CreateGroupState.Active, tall: Boolean = false) {
    DestinationBanner(active.session.destination.name, stringResource(R.string.group_destination), Modifier.fillMaxWidth(), height = if (tall) 420.dp else 220.dp)
}

@Composable
private fun InvitePane(active: CreateGroupState.Active, onReissue: () -> Unit) {
    val session = active.session
    DhCard(Modifier.fillMaxWidth()) {
        val invite = session.invite
        if (session.isLeader && invite == null) {
            // 승계로 새 선두차가 됐는데 초대가 없다 — 초대 QR 을 새로 만든다(서버 POST /groups/{id}/invites, 선두만)
            Text(stringResource(R.string.group_invite_none_hint), style = MaterialTheme.typography.titleMedium)
            Button(onClick = onReissue, enabled = !active.reissuing && !active.isDriving, modifier = Modifier.heightIn(min = 76.dp)) {
                Text(if (active.reissuing) stringResource(R.string.group_reissuing) else stringResource(R.string.group_btn_make_invite))
            }
        } else if (session.isLeader && invite != null) {
            Text(stringResource(R.string.group_scan_hint), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                // QR 은 흰 판 위에(카메라가 어두운 바탕의 QR 도 읽지만, 흰 여백이 있어야 인식이 빠르다)
                Surface(color = Color.White, shape = RoundedCornerShape(20.dp)) {
                    QrCodeImage(content = invite.url, modifier = Modifier.padding(16.dp).size(360.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val remaining = active.inviteRemainingS
                    Surface(
                        color = if (active.inviteExpired) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(
                            if (active.inviteExpired) stringResource(R.string.group_invite_expired)
                            else stringResource(R.string.group_invite_remaining, formatRemaining(remaining ?: 0)),
                            style = MaterialTheme.typography.titleMedium,
                            color = if (active.inviteExpired) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                    // QR 내용(서버가 준 초대 URL) — 카메라 없는 폰에서 공식 앱 '코드·주소 직접 입력'에 넣는다(D039). QR 과 같은 정보라 추가 노출은 아니다
                    Text(stringResource(R.string.group_scan_manual_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(invite.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    // 주행 중에는 다시 만들 수 없다(규칙 6 — ViewModel 도 무시한다)
                    DhSecondaryButton(
                        if (active.reissuing) stringResource(R.string.group_reissuing) else stringResource(R.string.group_btn_reissue),
                        onReissue,
                        enabled = !active.reissuing && !active.isDriving,
                    )
                }
            }
        } else {
            Text(stringResource(R.string.group_member_joined_hint), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.group_leader_label, active.members.firstOrNull { it.role == "LEADER" }?.nickname ?: "…"),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 멤버 목록 + 그룹 종료/나가기. 정차 대시보드의 '그룹 관리'로 오는 유일한 종료 경로라 **확인 다이얼로그**를 거친다
 * (방장 한 번 탭 = 그룹 전체 종료 — 예전 그룹 주행 화면의 confirmLeave 와 같은 문구, docs/10 R13 오탭 방지).
 */
@Composable
private fun MembersPane(active: CreateGroupState.Active, onEndOrLeave: () -> Unit, onRefresh: () -> Unit) {
    var confirmLeave by remember { mutableStateOf(false) }
    DhCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(DhIcons.group, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(30.dp))
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.group_members, active.members.size), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onRefresh, shape = MaterialTheme.shapes.medium) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.group_btn_refresh), color = MaterialTheme.colorScheme.onSurface)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (active.members.isEmpty()) {
            Text(stringResource(R.string.group_members_pending), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        active.members.forEach { MemberRow(it) }
    }
    if (active.recentSpeech.isNotEmpty()) {
        DhCard(Modifier.fillMaxWidth(), padding = 22.dp) {
            Text(stringResource(R.string.group_recent_events), style = MaterialTheme.typography.titleMedium)
            active.recentSpeech.forEach { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
    active.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge) }
    if (active.isDriving) Text(stringResource(R.string.group_driving_hint), color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.titleMedium)
    OutlinedButton(
        onClick = { confirmLeave = true },
        enabled = !active.busy && !active.isDriving,
        shape = MaterialTheme.shapes.medium,
        border = androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth().heightIn(min = 76.dp),
    ) {
        Text(
            if (active.session.isLeader) stringResource(R.string.group_btn_end) else stringResource(R.string.group_btn_leave),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.labelLarge,
        )
    }
    if (confirmLeave) {
        val leader = active.session.isLeader
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(if (leader) stringResource(R.string.drive_confirm_end_title) else stringResource(R.string.drive_confirm_leave_title)) },
            text = { Text(if (leader) stringResource(R.string.drive_confirm_end_text) else stringResource(R.string.drive_confirm_leave_text)) },
            confirmButton = {
                // 다이얼로그가 떠 있는 동안 주행으로 바뀌면 확정 버튼도 막는다(주행 중 입력 비활성)
                Button(onClick = { confirmLeave = false; onEndOrLeave() }, enabled = !active.busy && !active.isDriving) {
                    Text(if (leader) stringResource(R.string.group_btn_end) else stringResource(R.string.group_btn_leave))
                }
            },
            dismissButton = { OutlinedButton(onClick = { confirmLeave = false }) { Text(stringResource(R.string.drive_cancel)) } },
        )
    }
}

@Composable
private fun MemberRow(member: MemberDto) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        val color = runCatching { Color(android.graphics.Color.parseColor(member.color)) }.getOrDefault(Color.Gray)
        Box(Modifier.size(48.dp).clip(CircleShape).background(color.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
            Icon(DhIcons.car, contentDescription = null, tint = color, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.width(16.dp))
        Text(member.nickname, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        val leader = member.role == "LEADER"
        Surface(
            color = if (leader) DhPalette.signGreen else MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.small,
        ) {
            Text(
                if (leader) stringResource(R.string.group_role_leader) else stringResource(R.string.group_role_member),
                style = MaterialTheme.typography.labelMedium,
                color = if (leader) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
}

private fun formatRemaining(seconds: Long): String = "%d:%02d".format(seconds / 60, seconds % 60)
