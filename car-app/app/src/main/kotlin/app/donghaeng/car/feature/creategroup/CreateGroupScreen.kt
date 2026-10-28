package app.donghaeng.car.feature.creategroup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.donghaeng.car.R
import app.donghaeng.car.feature.pairing.QrCodeImage
import app.donghaeng.protocol.Place
import app.donghaeng.protocol.rest.MemberDto

@Composable
fun CreateGroupRoute(
    onBack: () -> Unit,
    onOpenDrive: () -> Unit = {},
    viewModel: CreateGroupViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
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
 * 그룹 생성(T17) — 정차 중 화면. 왼쪽/위: 목적지·이름·정원 입력 → [같이 가기 시작]. 생성되면 서버가 준 `invite.url` 을 QR 로 크게,
 * 멤버 목록과 남은 시간을 함께 보여 준다(QR 문자열은 서버 응답 그대로, docs/05 §7). 멤버(GROUP_JOINED 로 들어온 차)는 QR 없이 멤버 목록만.
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
    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onBack, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.group_back)) }
                Spacer(Modifier.width(20.dp))
                Text(
                    when (state) {
                        is CreateGroupState.Active -> state.session.name
                        else -> stringResource(R.string.group_title)
                    },
                    style = MaterialTheme.typography.headlineMedium,
                )
            }
            when (state) {
                CreateGroupState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                    Spacer(Modifier.width(16.dp))
                    Text(stringResource(R.string.group_loading), style = MaterialTheme.typography.titleMedium)
                }
                is CreateGroupState.Form -> FormContent(state, onLoadNavi, onPickPreset, onName, onMaxMembers, onCreate)
                is CreateGroupState.Active -> {
                    Button(onClick = onOpenDrive, modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp)) { Text(stringResource(R.string.group_btn_open_drive)) }
                    ActiveContent(state, onReissue, onEndOrLeave, onRefresh)
                }
            }
        }
    }
}

@Composable
private fun FormContent(
    form: CreateGroupState.Form,
    onLoadNavi: () -> Unit,
    onPickPreset: (Place) -> Unit,
    onName: (String) -> Unit,
    onMaxMembers: (Int) -> Unit,
    onCreate: () -> Unit,
) {
    if (form.notPaired) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Text(stringResource(R.string.group_not_paired), modifier = Modifier.padding(20.dp), style = MaterialTheme.typography.titleMedium)
        }
    }
    Card {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.group_destination), style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(form.destination?.name ?: stringResource(R.string.group_destination_none), style = MaterialTheme.typography.headlineSmall)
                    Text(
                        when {
                            form.loadingNavi -> stringResource(R.string.group_destination_loading)
                            form.destination == null -> ""
                            form.fromNavi -> stringResource(R.string.group_destination_from_navi)
                            else -> form.destination.address ?: stringResource(R.string.group_destination_preset)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = onLoadNavi, enabled = !form.loadingNavi && !form.isDriving, modifier = Modifier.heightIn(min = 56.dp)) {
                    Text(stringResource(R.string.group_btn_load_navi))
                }
            }
            HorizontalDivider()
            Text(stringResource(R.string.group_presets), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                DESTINATION_PRESETS.forEach { place ->
                    FilterChip(
                        selected = !form.fromNavi && form.destination?.name == place.name,
                        enabled = !form.isDriving,
                        onClick = { onPickPreset(place) },
                        label = { Text(place.name) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
        }
    }
    Card {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = form.name,
                onValueChange = onName,
                label = { Text(stringResource(R.string.group_name)) },
                singleLine = true,
                enabled = !form.isDriving,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.group_max_members), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MAX_MEMBER_CHOICES.forEach { n ->
                    FilterChip(selected = form.maxMembers == n, enabled = !form.isDriving, onClick = { onMaxMembers(n) }, label = { Text("${n}대") }, modifier = Modifier.heightIn(min = 48.dp))
                }
            }
        }
    }
    form.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge) }
    if (form.isDriving) Text(stringResource(R.string.group_driving_hint), color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.titleMedium)
    Button(onClick = onCreate, enabled = form.canCreate, modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp)) {
        if (form.creating) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp, color = MaterialTheme.colorScheme.onPrimary)
            Spacer(Modifier.width(12.dp))
        }
        Text(stringResource(R.string.group_btn_create), style = MaterialTheme.typography.titleLarge)
    }
    Text(stringResource(R.string.group_create_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ActiveContent(active: CreateGroupState.Active, onReissue: () -> Unit, onEndOrLeave: () -> Unit, onRefresh: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val twoPane = maxWidth >= 840.dp
        if (twoPane) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) { InvitePane(active, onReissue) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) { MembersPane(active, onEndOrLeave, onRefresh) }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                InvitePane(active, onReissue)
                MembersPane(active, onEndOrLeave, onRefresh)
            }
        }
    }
}

@Composable
private fun InvitePane(active: CreateGroupState.Active, onReissue: () -> Unit) {
    val session = active.session
    Card {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "${stringResource(R.string.group_destination)}: ${session.destination.name}",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth(),
            )
            val invite = session.invite
            if (session.isLeader && invite != null) {
                QrCodeImage(content = invite.url, modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth())
                Text(stringResource(R.string.group_scan_hint), style = MaterialTheme.typography.titleMedium)
                // QR 내용(서버가 준 초대 URL) — 카메라 없는 폰 에뮬레이터에서 '붙여넣기'로 합류할 때 읽어 쓴다. QR 과 같은 정보라 추가 노출은 아니다
                Text(invite.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val remaining = active.inviteRemainingS
                Text(
                    if (active.inviteExpired) stringResource(R.string.group_invite_expired)
                    else stringResource(R.string.group_invite_remaining, formatRemaining(remaining ?: 0)),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (active.inviteExpired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onReissue, enabled = !active.reissuing, modifier = Modifier.heightIn(min = 56.dp)) {
                    Text(if (active.reissuing) stringResource(R.string.group_reissuing) else stringResource(R.string.group_btn_reissue))
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
}

/**
 * 멤버 목록 + 그룹 종료/나가기. 정차 대시보드의 '그룹 관리'로 오는 유일한 종료 경로라 **확인 다이얼로그**를 거친다
 * (방장 한 번 탭 = 그룹 전체 종료 — 예전 그룹 주행 화면의 confirmLeave 와 같은 문구, docs/10 R13 오탭 방지).
 */
@Composable
private fun MembersPane(active: CreateGroupState.Active, onEndOrLeave: () -> Unit, onRefresh: () -> Unit) {
    var confirmLeave by remember { mutableStateOf(false) }
    Card {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.group_members, active.members.size), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = onRefresh, modifier = Modifier.heightIn(min = 44.dp)) { Text(stringResource(R.string.group_btn_refresh)) }
            }
            HorizontalDivider()
            if (active.members.isEmpty()) {
                Text(stringResource(R.string.group_members_pending), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            active.members.forEach { MemberRow(it) }
        }
    }
    if (active.recentSpeech.isNotEmpty()) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.group_recent_events), style = MaterialTheme.typography.titleMedium)
                active.recentSpeech.forEach { Text("• $it", style = MaterialTheme.typography.bodyLarge) }
            }
        }
    }
    active.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge) }
    if (active.isDriving) Text(stringResource(R.string.group_driving_hint), color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.titleMedium)
    OutlinedButton(onClick = { confirmLeave = true }, enabled = !active.busy && !active.isDriving, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
        Text(if (active.session.isLeader) stringResource(R.string.group_btn_end) else stringResource(R.string.group_btn_leave))
    }
    if (confirmLeave) {
        val leader = active.session.isLeader
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(if (leader) stringResource(R.string.drive_confirm_end_title) else stringResource(R.string.drive_confirm_leave_title)) },
            text = { Text(if (leader) stringResource(R.string.drive_confirm_end_text) else stringResource(R.string.drive_confirm_leave_text)) },
            confirmButton = {
                // 다이얼로그가 떠 있는 동안 주행으로 바뀌면 확정 버튼도 막는다(주행 중 입력 비활성)
                TextButton(onClick = { confirmLeave = false; onEndOrLeave() }, enabled = !active.busy && !active.isDriving) {
                    Text(if (leader) stringResource(R.string.group_btn_end) else stringResource(R.string.group_btn_leave))
                }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text(stringResource(R.string.drive_cancel)) } },
        )
    }
}

@Composable
private fun MemberRow(member: MemberDto) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Box(
            Modifier
                .size(16.dp)
                .clip(CircleShape)
                .background(runCatching { Color(android.graphics.Color.parseColor(member.color)) }.getOrDefault(Color.Gray)),
        )
        Spacer(Modifier.width(12.dp))
        Text(member.nickname, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text(
            if (member.role == "LEADER") stringResource(R.string.group_role_leader) else stringResource(R.string.group_role_member),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatRemaining(seconds: Long): String = "%d:%02d".format(seconds / 60, seconds % 60)
