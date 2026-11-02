package app.donghaeng.car.feature.settings

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.draw.scale
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.donghaeng.car.R
import app.donghaeng.car.core.common.TextMasking
import app.donghaeng.car.core.common.TokenStoreState
import app.donghaeng.car.core.network.ServerAddressRules
import app.donghaeng.car.core.network.ServerOverrides
import app.donghaeng.car.ui.components.DestructiveConfirmDialog
import app.donghaeng.car.ui.components.clearReasonLabel
import app.donghaeng.car.ui.components.formatShortTime

private const val CONFIRM_DISCONNECT = "disconnect"
private const val CONFIRM_RESET = "reset"

@Composable
fun SettingsRoute(
    onBack: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SettingsScreen(
        state = state, onBack = onBack, onDisconnect = viewModel::disconnect, onOpenDiagnostics = onOpenDiagnostics, onVoiceEnabled = viewModel::setVoiceEnabled,
        onAutoFollow = viewModel::setAutoFollow, onLatencyOverlay = viewModel::setLatencyOverlay, onResetStore = viewModel::resetStore,
        onCheckServer = viewModel::checkServerForm, onApplyServer = viewModel::applyServer, onResetServer = viewModel::resetServer,
        onPttHandleButton = viewModel::setPttHandleButton,
    )
}

/**
 * 설정 화면 — 연결 상태·연결 해제(T12), 진단 진입(T11). 닉네임은 폰 쪽(`PATCH /me/vehicles`)에서 바꾼다.
 * T33: '연결 해제'·'저장소 초기화' 는 확인 창 뒤로, 주행 중 비활성. 마지막 해제 사유·시각을 보여 준다(docs/10 R13).
 * T32: 제목을 3초 안에 5번 탭하면 숨은 메뉴 '서버 주소'([ServerAddressDialog]) — 주행 중에는 탭을 무시한다.
 * F16: '핸들 버튼으로 무전'(기본 켬, 정차 중에만 바꾼다).
 * 구역(3-4): 안내(음성 안내) / 주행(자동 따라가기·핸들 버튼 무전) / 연결(폰 계정 연결·해제) / 개발자(진단·서버 지연 표시). 주행 중에는 스위치를 잠근다
 * (음성 안내는 끄기만 허용 — [SettingsLocks], 3-5·E035).
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onBack: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onVoiceEnabled: (Boolean) -> Unit = {},
    onAutoFollow: (Boolean) -> Unit = {},
    onLatencyOverlay: (Boolean) -> Unit = {},
    onResetStore: () -> Unit = {},
    onCheckServer: (String, String) -> ServerAddressRules.FormResult = { _, _ -> ServerAddressRules.FormResult.Unchanged },
    onApplyServer: (ServerOverrides) -> Unit = {},
    onResetServer: () -> Unit = {},
    onPttHandleButton: (Boolean) -> Unit = {},
) {
    var confirm by rememberSaveable { mutableStateOf<String?>(null) }
    var serverDialog by rememberSaveable { mutableStateOf(false) }
    val titleTaps = remember { HiddenTapCounter() }
    if (serverDialog) {
        ServerAddressDialog(
            current = state.server,
            isDriving = state.isDriving,
            onCheck = onCheckServer,
            onApply = onApplyServer,
            onReset = onResetServer,
            onDismiss = { serverDialog = false },
        )
    }
    when (confirm) {
        CONFIRM_DISCONNECT -> DestructiveConfirmDialog(
            title = stringResource(R.string.t33_disconnect_confirm_title),
            message = stringResource(R.string.t33_disconnect_confirm_message),
            confirmText = stringResource(R.string.t33_disconnect_confirm_ok),
            isDriving = state.isDriving,
            onConfirm = { confirm = null; onDisconnect() },
            onDismiss = { confirm = null },
        )
        CONFIRM_RESET -> DestructiveConfirmDialog(
            title = stringResource(R.string.t33_reset_confirm_title),
            message = stringResource(R.string.t33_reset_confirm_message),
            confirmText = stringResource(R.string.t33_settings_btn_reset_store),
            isDriving = state.isDriving,
            onConfirm = { confirm = null; onResetStore() },
            onDismiss = { confirm = null },
        )
    }
    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 40.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onBack, modifier = Modifier.heightIn(min = 76.dp)) { Text(stringResource(R.string.settings_back)) }
                Spacer(Modifier.width(24.dp))
                Text(
                    stringResource(R.string.settings_title),
                    style = MaterialTheme.typography.headlineMedium,
                    // T32 숨은 메뉴: 표시 없는 탭 영역(리플 없음). 주행 중에는 세지 않는다
                    modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        if (state.isDriving) titleTaps.reset() else if (titleTaps.tap(SystemClock.elapsedRealtime())) serverDialog = true
                    },
                )
            }
            if (state.isDriving) {
                Text(stringResource(R.string.settings_driving_locked), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.tertiary)
            }

            // 안내 — 음성 안내는 주행 중에도 '끄기'만 된다(켜기는 정차 중)
            SettingsCard(stringResource(R.string.settings_section_voice)) {
                SwitchRow(
                    title = stringResource(R.string.settings_voice_switch),
                    hint = stringResource(R.string.settings_voice_placeholder),
                    checked = state.voiceEnabled,
                    enabled = SettingsLocks.voiceSwitchEnabled(state.isDriving, state.voiceEnabled),
                    lockedHint = if (state.isDriving && state.voiceEnabled) stringResource(R.string.settings_voice_driving_hint) else null,
                    onChange = onVoiceEnabled,
                )
            }

            // 주행 — 자동 따라가기(켜면 대기 제안을 바로 수락하므로 주행 중 잠금, E035)·핸들 버튼으로 무전(F16)
            SettingsCard(stringResource(R.string.settings_section_drive)) {
                SwitchRow(
                    title = stringResource(R.string.settings_autofollow_switch),
                    hint = stringResource(R.string.settings_autofollow_hint),
                    checked = state.autoFollow,
                    enabled = SettingsLocks.lockedWhileDriving(state.isDriving),
                    onChange = onAutoFollow,
                )
                // F16 무전 — 핸들 재생/정지(미디어) 버튼 매핑(HEADSETHOOK·MEDIA_PLAY_PAUSE·MEDIA_PLAY — 통화 KEYCODE_CALL 은 쓰지 않는다). 정차 중에만 바꾼다(주행 중 입력 UI 비활성, CLAUDE.md 규칙 6)
                SwitchRow(
                    title = stringResource(R.string.settings_ptt_handle_switch),
                    hint = stringResource(R.string.settings_ptt_handle_hint),
                    checked = state.pttHandleButton,
                    enabled = SettingsLocks.lockedWhileDriving(state.isDriving),
                    lockedHint = if (state.isDriving) stringResource(R.string.settings_ptt_driving_locked) else null,
                    onChange = onPttHandleButton,
                )
            }

            // 연결 — 폰 계정 연결 상태·연결 해제(확인 창 뒤, 주행 중 비활성)
            SettingsCard(stringResource(R.string.settings_section_connection)) {
                val tokens = state.tokens
                val statusText = when (tokens) {
                    TokenStoreState.Loading -> stringResource(R.string.home_connection_checking)
                    TokenStoreState.Empty -> stringResource(R.string.home_connection_disconnected)
                    is TokenStoreState.Unavailable -> stringResource(R.string.t33_settings_store_unavailable)
                    is TokenStoreState.Present ->
                        if (tokens.credentials.nickname.isBlank()) stringResource(R.string.home_connection_connected)
                        else stringResource(R.string.home_connection_connected_nick, tokens.credentials.nickname)
                }
                Text("${stringResource(R.string.home_vehicle_connection)}: $statusText", style = MaterialTheme.typography.bodyLarge)
                if (tokens is TokenStoreState.Present) {
                    Text(
                        "${stringResource(R.string.home_vehicle_id)} ${TextMasking.maskVehicleId(tokens.credentials.vehicleId)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.lastClear?.let { record ->
                    Text(
                        stringResource(R.string.t33_settings_last_clear, formatShortTime(record.atMs), clearReasonLabel(record.reason)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(
                        onClick = { confirm = CONFIRM_DISCONNECT },
                        enabled = tokens is TokenStoreState.Present && !state.isDriving,
                        modifier = Modifier.heightIn(min = 76.dp),
                    ) { Text(stringResource(R.string.settings_btn_disconnect)) }
                    if (tokens is TokenStoreState.Unavailable) {
                        OutlinedButton(
                            onClick = { confirm = CONFIRM_RESET },
                            enabled = !state.isDriving,
                            modifier = Modifier.heightIn(min = 76.dp),
                        ) { Text(stringResource(R.string.t33_settings_btn_reset_store)) }
                    }
                }
                if (state.isDriving) {
                    Text(stringResource(R.string.t33_driving_disabled), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
                }
                state.serverNotice?.let { notice ->
                    Text(
                        stringResource(if (notice == ServerNotice.BASE_CHANGED) R.string.t32_server_notice_base_changed else R.string.t32_server_notice_mqtt_reconnected),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
                if (state.justDisconnected && tokens is TokenStoreState.Empty) {
                    Text(
                        stringResource(R.string.settings_disconnected_notice),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }

            // 개발자 — 진단 화면(차량 기능 지원 여부·연결 상태)·서버 지연 표시
            SettingsCard(stringResource(R.string.settings_section_diagnostics)) {
                Text(stringResource(R.string.settings_diagnostics_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = onOpenDiagnostics, modifier = Modifier.heightIn(min = 76.dp)) { Text(stringResource(R.string.settings_btn_diagnostics)) }
                SwitchRow(
                    title = stringResource(R.string.settings_latency_switch),
                    hint = stringResource(R.string.settings_latency_hint),
                    checked = state.latencyOverlay,
                    enabled = SettingsLocks.lockedWhileDriving(state.isDriving),
                    onChange = onLatencyOverlay,
                )
            }
        }
    }
}

/** 설정 카드 — 모든 카드가 같은 폭(가득, 최대 960 dp, 3-4) */
@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().widthIn(max = 960.dp)) {
        Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}

/**
 * 스위치 한 줄 — 줄 전체가 눌리는 영역(차량 기준 76 dp 이상)이고, 설명 글과 스위치 사이를 24 dp 띄운다(설명이 스위치에 붙어 잘리던 것, 3-4).
 * 스위치는 1.4배로 키워 그린다(보이는 크기 ≈ 73×45 dp). [enabled] = false 면 줄 전체가 흐려지고 눌리지 않는다(주행 중 잠금, 3-5·E035).
 */
@Composable
private fun SwitchRow(title: String, hint: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit, lockedHint: String? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            lockedHint?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary) }
        }
        Spacer(Modifier.width(24.dp))
        androidx.compose.material3.Switch(
            checked = checked,
            onCheckedChange = null,   // 줄 전체(toggleable)가 받는다
            enabled = enabled,
            modifier = Modifier.padding(horizontal = 12.dp).scale(1.4f),
        )
    }
}

/** 설정 스위치 잠금 규칙(순수 — 단위 테스트, 규칙 6) */
object SettingsLocks {
    /** 주행 중 잠그는 스위치(자동 따라가기·핸들 버튼 무전·서버 지연 표시) */
    fun lockedWhileDriving(isDriving: Boolean): Boolean = !isDriving

    /** 음성 안내 — 주행 중에도 '끄기'만은 허용(켜져 있을 때만 누를 수 있다). 끈 상태를 주행 중에 다시 켜지는 못한다 */
    fun voiceSwitchEnabled(isDriving: Boolean, voiceOn: Boolean): Boolean = !isDriving || voiceOn
}
