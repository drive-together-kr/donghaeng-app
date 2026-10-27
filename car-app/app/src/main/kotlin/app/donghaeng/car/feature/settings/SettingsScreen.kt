package app.donghaeng.car.feature.settings

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.donghaeng.car.R
import app.donghaeng.car.core.common.TextMasking
import app.donghaeng.car.core.common.TokenStoreState

@Composable
fun SettingsRoute(
    onBack: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SettingsScreen(state = state, onBack = onBack, onDisconnect = viewModel::disconnect, onOpenDiagnostics = onOpenDiagnostics, onVoiceEnabled = viewModel::setVoiceEnabled, onAutoFollow = viewModel::setAutoFollow, onLatencyOverlay = viewModel::setLatencyOverlay)
}

/** 설정 화면 — 연결 상태·연결 해제(T12), 진단 진입(T11). 닉네임은 폰 쪽(`PATCH /me/vehicles`)에서 바꾼다. */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onBack: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onVoiceEnabled: (Boolean) -> Unit = {},
    onAutoFollow: (Boolean) -> Unit = {},
    onLatencyOverlay: (Boolean) -> Unit = {},
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
                OutlinedButton(onClick = onBack, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.settings_back)) }
                Spacer(Modifier.width(20.dp))
                Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineMedium)
            }

            Card(modifier = Modifier.widthIn(max = 720.dp)) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.settings_section_connection), style = MaterialTheme.typography.titleMedium)
                    val tokens = state.tokens
                    val statusText = when (tokens) {
                        TokenStoreState.Loading -> stringResource(R.string.home_connection_checking)
                        TokenStoreState.Empty -> stringResource(R.string.home_connection_disconnected)
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
                    Spacer(Modifier.height(4.dp))
                    Button(
                        onClick = onDisconnect,
                        enabled = tokens is TokenStoreState.Present,
                        modifier = Modifier.heightIn(min = 56.dp),
                    ) { Text(stringResource(R.string.settings_btn_disconnect)) }
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
