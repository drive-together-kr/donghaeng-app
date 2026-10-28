package app.donghaeng.car.feature.settings

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
    )
}

/**
 * 설정 화면 — 연결 상태·연결 해제(T12), 진단 진입(T11). 닉네임은 폰 쪽(`PATCH /me/vehicles`)에서 바꾼다.
 * T33: '연결 해제'·'저장소 초기화' 는 확인 창 뒤로, 주행 중 비활성. 마지막 해제 사유·시각을 보여 준다(docs/10 R13).
 * T32: 제목을 3초 안에 5번 탭하면 숨은 메뉴 '서버 주소'([ServerAddressDialog]) — 주행 중에는 탭을 무시한다.
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
                .padding(horizontal = 32.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onBack, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.settings_back)) }
                Spacer(Modifier.width(20.dp))
                Text(
                    stringResource(R.string.settings_title),
                    style = MaterialTheme.typography.headlineMedium,
                    // T32 숨은 메뉴: 표시 없는 탭 영역(리플 없음). 주행 중에는 세지 않는다
                    modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        if (state.isDriving) titleTaps.reset() else if (titleTaps.tap(SystemClock.elapsedRealtime())) serverDialog = true
                    },
                )
            }

            Card(modifier = Modifier.widthIn(max = 720.dp)) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.settings_section_connection), style = MaterialTheme.typography.titleMedium)
                    val tokens = state.tokens
                    val statusText = when (tokens) {
                        TokenStoreState.Loading -> stringResource(R.string.home_connection_checking)
                        TokenStoreState.Empty -> stringResource(R.string.home_connection_disconnected)
                        is TokenStoreState.Unavailable -> stringResource(R.string.t33_settings_store_unavailable, tokens.reason)
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
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = { confirm = CONFIRM_DISCONNECT },
                            enabled = tokens is TokenStoreState.Present && !state.isDriving,
                            modifier = Modifier.heightIn(min = 56.dp),
                        ) { Text(stringResource(R.string.settings_btn_disconnect)) }
                        if (tokens is TokenStoreState.Unavailable) {
                            OutlinedButton(
                                onClick = { confirm = CONFIRM_RESET },
                                enabled = !state.isDriving,
                                modifier = Modifier.heightIn(min = 56.dp),
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
            }

            Card(modifier = Modifier.widthIn(max = 720.dp)) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.settings_section_diagnostics), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.settings_diagnostics_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = onOpenDiagnostics, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.settings_btn_diagnostics)) }
                }
            }

            Card(modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth()) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.settings_section_voice), style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.settings_voice_switch), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.settings_voice_placeholder), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        androidx.compose.material3.Switch(checked = state.voiceEnabled, onCheckedChange = onVoiceEnabled)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.settings_autofollow_switch), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.settings_autofollow_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        androidx.compose.material3.Switch(checked = state.autoFollow, onCheckedChange = onAutoFollow)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.settings_latency_switch), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.settings_latency_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        androidx.compose.material3.Switch(checked = state.latencyOverlay, onCheckedChange = onLatencyOverlay)
                    }
                }
            }
        }
    }
}
