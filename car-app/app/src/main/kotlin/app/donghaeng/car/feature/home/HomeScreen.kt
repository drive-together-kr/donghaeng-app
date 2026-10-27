package app.donghaeng.car.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.donghaeng.car.R
import app.donghaeng.car.adapter.sdk.ProbeLine
import app.donghaeng.car.ui.theme.DongHaengTheme

/** 홈 화면 진입점 — ViewModel 연결. */
@Composable
fun HomeRoute(
    onPairPhone: () -> Unit,
    onStartGroup: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    HomeScreen(
        state = state,
        onPairPhone = onPairPhone,
        onStartGroup = onStartGroup,
        onOpenSettings = onOpenSettings,
        onOpenDiagnostics = onOpenDiagnostics,
    )
}

/**
 * 홈 화면. 세로·가로 모두 지원:
 * - 넓은 화면(가로, Pleos 2560x1440)에서는 좌 = 상태·버튼, 우 = 빌드 정보·SDK 점검 두 단
 * - 좁은 화면(세로)에서는 한 단으로 쌓고 스크롤
 * 연결 상태(T12)는 TokenStore 에서, 진단(T11)·설정 버튼은 보조 행에 둔다.
 */
@Composable
fun HomeScreen(
    state: HomeUiState,
    onPairPhone: () -> Unit,
    onStartGroup: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    Scaffold { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            val twoPane = maxWidth >= 840.dp
            val contentModifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp, vertical = 24.dp)

            if (twoPane) {
                Row(modifier = contentModifier, horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        HeaderSection(state)
                        Spacer(Modifier.height(24.dp))
                        ActionSection(state, onPairPhone, onStartGroup, onOpenSettings, onOpenDiagnostics)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        BuildInfoCard(state)
                        if (state.showSdkProbe) {
                            Spacer(Modifier.height(16.dp))
                            SdkProbeCard(state.sdkProbe)
                        }
                    }
                }
            } else {
                Column(modifier = contentModifier) {
                    HeaderSection(state)
                    Spacer(Modifier.height(24.dp))
                    ActionSection(state, onPairPhone, onStartGroup, onOpenSettings, onOpenDiagnostics)
                    Spacer(Modifier.height(24.dp))
                    BuildInfoCard(state)
                    if (state.showSdkProbe) {
                        Spacer(Modifier.height(16.dp))
                        SdkProbeCard(state.sdkProbe)
                    }
                }
            }
        }
    }
}

@Composable
private fun HeaderSection(state: HomeUiState) {
    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
    Text(
        stringResource(R.string.app_tagline),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(16.dp))
    val connectionText = when (state.connection) {
        VehicleConnection.CHECKING -> stringResource(R.string.home_connection_checking)
        VehicleConnection.DISCONNECTED -> stringResource(R.string.home_connection_disconnected)
        VehicleConnection.CONNECTED ->
            if (state.nickname.isNullOrBlank()) stringResource(R.string.home_connection_connected)
            else stringResource(R.string.home_connection_connected_nick, state.nickname)
    }
    Text(
        "${stringResource(R.string.home_vehicle_connection)}: $connectionText",
        style = MaterialTheme.typography.titleLarge,
        color = if (state.connection == VehicleConnection.CONNECTED) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface,
    )
    if (state.connection == VehicleConnection.CONNECTED && state.vehicleIdMasked != null) {
        Text(
            "${stringResource(R.string.home_vehicle_id)} ${state.vehicleIdMasked}",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    state.groupSession?.let { session ->
        Spacer(Modifier.height(8.dp))
        Text(
            if (session.isLeader) stringResource(R.string.home_group_active_leader, session.name) else stringResource(R.string.home_group_active, session.name),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.tertiary,
        )
        Text(
            "${stringResource(R.string.group_destination)}: ${session.destination.name}",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ActionSection(
    state: HomeUiState,
    onPairPhone: () -> Unit,
    onStartGroup: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    Button(
        onClick = onPairPhone,
        enabled = state.canPairPhone,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp),
    ) {
        Text(stringResource(R.string.home_btn_pair_phone))
    }
    Spacer(Modifier.height(12.dp))
    OutlinedButton(
        onClick = onStartGroup,
        enabled = state.canStartGroup,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp),
    ) {
        Text(if (state.groupSession != null) stringResource(R.string.home_btn_open_group) else stringResource(R.string.home_btn_start_group))
    }
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(R.string.home_buttons_hint),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = onOpenSettings, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
            Text(stringResource(R.string.home_btn_settings))
        }
        OutlinedButton(onClick = onOpenDiagnostics, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
            Text(stringResource(R.string.home_btn_diagnostics))
        }
    }
}

@Composable
private fun BuildInfoCard(state: HomeUiState) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            InfoRow(stringResource(R.string.home_label_flavor), state.flavor)
            InfoRow(stringResource(R.string.home_label_version), state.versionName)
            InfoRow(stringResource(R.string.home_label_base_url), state.publicBaseUrl)
            InfoRow(stringResource(R.string.home_label_mqtt_uri), state.mqttPublicUri)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(120.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SdkProbeCard(lines: List<ProbeLine>?) {
    Card {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.home_sdk_probe_title), style = MaterialTheme.typography.titleMedium)
            HorizontalDivider()
            if (lines == null) {
                Text(stringResource(R.string.home_sdk_probe_pending), style = MaterialTheme.typography.bodyMedium)
            } else {
                lines.forEach { line ->
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            if (line.available) "OK" else "--",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (line.available) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                            modifier = Modifier.width(44.dp),
                        )
                        Column {
                            Text(line.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                line.detail,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Preview(name = "세로", widthDp = 480, heightDp = 900)
@Preview(name = "가로", widthDp = 1280, heightDp = 720)
@Composable
private fun HomeScreenPreview() {
    DongHaengTheme {
        HomeScreen(
            state = HomeUiState(
                flavor = "pleos",
                connection = VehicleConnection.CONNECTED,
                nickname = "아빠차",
                vehicleIdMasked = "veh_…7Q",
                sdkProbe = listOf(
                    ProbeLine("Vehicle 2.3.2", true, "ai.pleos.playground.vehicle.Vehicle"),
                    ProbeLine("NaviHelper 3.0.5", false, "ClassNotFoundException"),
                ),
            ),
            onPairPhone = {},
            onStartGroup = {},
            onOpenSettings = {},
            onOpenDiagnostics = {},
        )
    }
}
