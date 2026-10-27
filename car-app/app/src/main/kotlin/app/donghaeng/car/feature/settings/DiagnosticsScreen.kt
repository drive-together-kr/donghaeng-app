package app.donghaeng.car.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.donghaeng.car.R
import app.donghaeng.car.adapter.sdk.ProbeLine
import app.donghaeng.car.domain.capability.CapabilityReport
import app.donghaeng.car.domain.model.CapabilityDomain
import app.donghaeng.car.domain.model.CapabilityEntry
import app.donghaeng.car.domain.model.CapabilitySource
import app.donghaeng.car.domain.model.VehicleCapabilities
import app.donghaeng.car.ui.theme.DongHaengTheme

@Composable
fun DiagnosticsRoute(
    onBack: () -> Unit,
    viewModel: DiagnosticsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    DiagnosticsScreen(state = state, onBack = onBack)
}

/**
 * 진단 화면 (T11, docs/09 §3 체크리스트를 화면으로). 표: 항목 | 지원 | 상세 | 출처 — `adb logcat -s DH_CAP` 과 같은 내용.
 * 사용자가 결과를 캡처해 붙여넣기만 하면 되게 만든 확인용 화면(CLAUDE.md 작업 방식).
 */
@Composable
fun DiagnosticsScreen(state: DiagnosticsUiState, onBack: () -> Unit) {
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
                OutlinedButton(onClick = onBack, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.diag_back)) }
                Spacer(Modifier.width(20.dp))
                Text(stringResource(R.string.diag_title), style = MaterialTheme.typography.headlineMedium)
            }

            VehicleCard(state)
            CapabilityCard(state.report?.capabilities)
            ProbeCard(state.probe)
            BuildCard(state)
        }
    }
}

@Composable
private fun VehicleCard(state: DiagnosticsUiState) {
    Card {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.diag_section_vehicle), style = MaterialTheme.typography.titleMedium)
            HorizontalDivider()
            val report = state.report
            val vehicleIdText = when {
                report == null -> stringResource(R.string.diag_pending)
                report.vehicleIdMasked == null -> stringResource(R.string.diag_vehicle_id_none)
                else -> "${report.vehicleIdMasked}  (${stringResource(R.string.diag_vehicle_id_length, report.vehicleIdLength)})"
            }
            LabelRow(stringResource(R.string.diag_vehicle_id_masked), vehicleIdText)
            LabelRow(stringResource(R.string.diag_paired), state.pairedVehicleIdMasked ?: stringResource(R.string.diag_paired_none))
        }
    }
}

@Composable
private fun CapabilityCard(capabilities: VehicleCapabilities?) {
    Card {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.diag_section_capabilities), style = MaterialTheme.typography.titleMedium)
            HorizontalDivider()
            if (capabilities == null) {
                Text(stringResource(R.string.diag_pending), style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                HeaderCell(stringResource(R.string.diag_col_domain), 1.3f)
                HeaderCell(stringResource(R.string.diag_col_supported), 0.9f)
                HeaderCell(stringResource(R.string.diag_col_detail), 4.5f)
                HeaderCell(stringResource(R.string.diag_col_source), 1.3f)
            }
            HorizontalDivider()
            capabilities.entries.forEach { entry -> CapabilityRow(entry) }
            if (capabilities.raw.isNotEmpty()) {
                Spacer(Modifier.width(1.dp))
                Text(
                    capabilities.raw.entries.joinToString("  ·  ") { "${it.key}=${it.value}" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CapabilityRow(entry: CapabilityEntry) {
    val (supportedText, color) = when (entry.supported) {
        true -> stringResource(R.string.diag_supported_true) to MaterialTheme.colorScheme.secondary
        false -> stringResource(R.string.diag_supported_false) to MaterialTheme.colorScheme.error
        null -> stringResource(R.string.diag_supported_unknown) to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Cell(entry.domain.label, 1.3f)
        Cell(supportedText, 0.9f, color)
        Cell(entry.detail, 4.5f, MaterialTheme.colorScheme.onSurfaceVariant)
        Cell(entry.source.name, 1.3f, MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.HeaderCell(text: String, weight: Float) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.weight(weight).padding(end = 8.dp),
    )
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Cell(text: String, weight: Float, color: Color = Color.Unspecified) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        modifier = Modifier.weight(weight).padding(end = 8.dp),
    )
}

@Composable
private fun ProbeCard(lines: List<ProbeLine>?) {
    Card {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.diag_section_probe), style = MaterialTheme.typography.titleMedium)
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
                        Text(line.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(220.dp))
                        Text(line.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun BuildCard(state: DiagnosticsUiState) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.diag_section_build), style = MaterialTheme.typography.titleMedium)
            HorizontalDivider()
            LabelRow(stringResource(R.string.home_label_flavor), state.flavor)
            LabelRow(stringResource(R.string.diag_label_adapter), state.adapter)
            LabelRow(stringResource(R.string.home_label_version), state.versionName)
            LabelRow(stringResource(R.string.home_label_base_url), state.publicBaseUrl)
            LabelRow(stringResource(R.string.home_label_mqtt_uri), state.mqttPublicUri)
            LabelRow(stringResource(R.string.diag_label_debug), state.debug.toString())
            LabelRow(stringResource(R.string.diag_label_android), state.androidApi.toString())
            LabelRow(stringResource(R.string.diag_label_device), state.deviceModel)
        }
    }
}

@Composable
private fun LabelRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(200.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Preview(name = "진단 가로", widthDp = 1280, heightDp = 800)
@Composable
private fun DiagnosticsPreview() {
    DongHaengTheme {
        DiagnosticsScreen(
            state = DiagnosticsUiState(
                report = CapabilityReport(
                    vehicleIdMasked = "KMHX…01", vehicleIdLength = 17,
                    capabilities = VehicleCapabilities(
                        entries = listOf(
                            CapabilityEntry(CapabilityDomain.CAR_INFO, true, "VIN accessType=READ, CRN accessType=NONE", CapabilitySource.PLEOS_SDK),
                            CapabilityEntry(CapabilityDomain.FUEL, true, "accessType=READ", CapabilitySource.PLEOS_SDK),
                            CapabilityEntry(CapabilityDomain.HAZARD, false, "TurnSignalValue=OFF/LEFT/RIGHT 뿐", CapabilitySource.PLEOS_SDK),
                            CapabilityEntry(CapabilityDomain.SPEED, null, "T22: Car API 확인 예정", CapabilitySource.UNCHECKED),
                        ),
                        raw = mapOf("adapter" to "pleos"),
                    ),
                    adapter = "pleos",
                ),
                probe = listOf(ProbeLine("Vehicle 2.3.2", true, "ai.pleos.playground.vehicle.Vehicle")),
                pairedVehicleIdMasked = "veh_…7Q",
            ),
            onBack = {},
        )
    }
}
