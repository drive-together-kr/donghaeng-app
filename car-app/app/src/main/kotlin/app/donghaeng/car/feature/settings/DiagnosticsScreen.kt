package app.donghaeng.car.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.donghaeng.car.BuildConfig
import app.donghaeng.car.R
import app.donghaeng.car.adapter.sdk.ProbeLine
import app.donghaeng.car.core.common.LocationPermissions
import androidx.compose.ui.text.font.FontFamily
import app.donghaeng.car.core.common.TextMasking
import app.donghaeng.car.core.common.TokenStoreState
import app.donghaeng.car.ui.components.clearRecordText
import app.donghaeng.car.ui.components.formatShortTime
import app.donghaeng.car.domain.capability.CapabilityReport
import app.donghaeng.car.domain.model.CapabilityDomain
import app.donghaeng.car.domain.model.CapabilityEntry
import app.donghaeng.car.domain.model.CapabilitySource
import app.donghaeng.car.domain.model.VehicleCapabilities
import app.donghaeng.car.ui.theme.CompactTypography
import app.donghaeng.car.ui.theme.DongHaengTheme

@Composable
fun DiagnosticsRoute(
    onBack: () -> Unit,
    viewModel: DiagnosticsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val t33 by viewModel.t33State.collectAsStateWithLifecycle()
    val extra by viewModel.extraState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { viewModel.onPermissionResult(it) }
    DiagnosticsScreen(
        state = state,
        onBack = onBack,
        onNaviAction = viewModel::onNaviAction,
        onLocationAction = { action ->
            viewModel.onLocationAction(action)
            if (action == LocationDebugAction.REQUEST_PERMISSION) permissionLauncher.launch(LocationPermissions.runtimePermissions(context))
        },
        onRestartVehicleSignals = viewModel::restartVehicleSignals,
        onVoiceAction = viewModel::onVoiceAction,
        onRouteSyncAction = viewModel::onRouteSyncAction, onResyncClock = viewModel::resyncClock,
        t33 = t33,
        extra = extra,
    )
}

/**
 * 진단 화면 (T11, docs/09 §3 체크리스트를 화면으로). 표: 항목 | 지원 | 상세 | 출처 — `adb logcat -s DH_CAP` 과 같은 내용.
 * 사용자가 결과를 캡처해 붙여넣기만 하면 되게 만든 확인용 화면(CLAUDE.md 작업 방식). 개발자용 표라 예전 글자 크기([CompactTypography])를 쓴다.
 * 맨 위 '이 차에서 쓰는 차량 기능'(5-2)은 capability 실측값을 사람 말로 요약한다.
 * E035: 부작용 있는 확인용 동작(내비 경로 요청·내비 화면·경로 변경 테스트 발행·제안 수락/거절·자동 따라가기 토글·테스트 발화·음성 켜기/끄기)은
 * debug 빌드에서만 보이고, 그 밖 버튼도 모두 주행 중 비활성(ViewModel 도 무시). 경로 요청·테스트 발행은 확인 창을 거친다. '예시 안내 듣기'는 release 에도(정차 중).
 */
@Composable
fun DiagnosticsScreen(
    state: DiagnosticsUiState,
    onBack: () -> Unit,
    onNaviAction: (NaviDebugAction) -> Unit = {},
    onLocationAction: (LocationDebugAction) -> Unit = {},
    onRestartVehicleSignals: () -> Unit = {},
    onVoiceAction: (VoiceDebugAction) -> Unit = {},
    onRouteSyncAction: (RouteSyncDebugAction) -> Unit = {},
    onResyncClock: () -> Unit = {},
    t33: T33DiagState = T33DiagState(),
    extra: DiagExtraState = DiagExtraState(),
) {
    MaterialTheme(colorScheme = MaterialTheme.colorScheme, typography = CompactTypography) {
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
                OutlinedButton(onClick = onBack) { Text(stringResource(R.string.diag_back)) }
                Spacer(Modifier.width(20.dp))
                Text(stringResource(R.string.diag_title), style = MaterialTheme.typography.headlineMedium)
            }

            VehicleFeaturesCard(state.report?.capabilities)
            VehicleCard(state)
            // 음성 경로 카드는 위쪽에 — 심사 환경 재현('예시 안내 듣기')을 스크롤 없이(R19, docs/09 §1.4)
            VoiceCard(state, onVoiceAction, extra)
            TokenCard(t33, state)
            RecentLogCard(t33)
            CapabilityCard(state.report?.capabilities)
            ProbeCard(state.probe)
            MqttCard(state, extra)
            NaviCard(state, onNaviAction)
            LocationCard(state, onLocationAction)
            VehicleSignalCard(state, onRestartVehicleSignals)
            TelemetryCard(state, extra)
            EventEngineCard(state)
            RouteSyncCard(state, onRouteSyncAction)
            DrivingCard(state)
            LatencyCard(state, onResyncClock)
            BuildCard(state)
        }
    }
    }
}

/** 5-2 '이 차에서 쓰는 차량 기능' — 쓰는 신호(급제동 감지·저잔량 알림·도착 예상)와 이 차가 지원하지 않아 숨긴 것 */
@Composable
private fun VehicleFeaturesCard(capabilities: VehicleCapabilities?) {
    val summary = VehicleFeatureSummary.of(capabilities)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.diag_features_title), style = MaterialTheme.typography.titleLarge)
            if (summary == null) {
                Text(stringResource(R.string.diag_pending), style = MaterialTheme.typography.bodyLarge)
                return@Column
            }
            if (summary.used.isNotEmpty()) Text(stringResource(R.string.diag_features_used, summary.used.joinToString("·")), style = MaterialTheme.typography.bodyLarge)
            if (summary.hidden.isNotEmpty()) Text(stringResource(R.string.diag_features_hidden, summary.hidden.joinToString("·")), style = MaterialTheme.typography.bodyLarge)
            if (summary.unknown.isNotEmpty()) Text(stringResource(R.string.diag_features_unknown, summary.unknown.joinToString("·")), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** 부작용 있는 확인용 동작의 확인 창 */
@Composable
private fun DebugConfirmDialog(message: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.diag_confirm_title)) },
        text = { Text(message) },
        confirmButton = { Button(onClick = onConfirm) { Text(stringResource(R.string.diag_confirm_ok)) } },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.drive_cancel)) } },
    )
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

/**
 * T33 — 저장소 상태·토큰 만료·갱신 결과·마지막 삭제 사유(토큰/그룹 세션)·저장소 재시도·MQTT 인증 거부.
 * R13 같은 '조용한 해제' 를 결과만 캡처해 붙여넣으면 원인을 알 수 있게 한다.
 */
@Composable
private fun TokenCard(t33: T33DiagState, state: DiagnosticsUiState) {
    Card {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.t33_diag_section_tokens), style = MaterialTheme.typography.titleMedium)
            HorizontalDivider()
            val storeText = when (val t = t33.tokens) {
                TokenStoreState.Loading -> "LOADING"
                TokenStoreState.Empty -> "EMPTY"
                is TokenStoreState.Present -> "PRESENT ${TextMasking.maskVehicleId(t.credentials.vehicleId)}"
                is TokenStoreState.Unavailable -> "UNAVAILABLE ${t.reason}"
            }
            LabelRow(stringResource(R.string.t33_diag_store_state), storeText)
            val r = t33.refresh
            val now = if (t33.nowMs > 0) t33.nowMs else System.currentTimeMillis()
            LabelRow(
                stringResource(R.string.t33_diag_token_exp),
                r.accessExpiresAtMs?.let { exp -> "${formatShortTime(exp)}  (${(exp - now) / 60_000}분 남음)" } ?: "-",
            )
            LabelRow(
                stringResource(R.string.t33_diag_refresh),
                "성공 ${r.refreshes} / 실패 ${r.failures}" + (r.lastResult?.let { "   마지막 $it" } ?: "") + (r.lastAttemptAtMs?.let { "  @${formatShortTime(it)}" } ?: "") + (if (r.running) "" else "  (중지)"),
            )
            LabelRow(stringResource(R.string.t33_diag_last_token_clear), clearRecordText(t33.lastTokenClear))
            LabelRow(stringResource(R.string.t33_diag_last_session_clear), clearRecordText(t33.lastSessionClear))
            val retry = t33.storeRetry
            LabelRow(stringResource(R.string.t33_diag_store_retry), "${retry.attempts}/${retry.maxAttempts}" + if (retry.exhausted) "  (소진)" else "")
            LabelRow(stringResource(R.string.t33_diag_repair_needed), r.repairNeededSinceMs?.let { "예 — 갱신 거부 ${formatShortTime(it)} (지금 토큰 만료 때 해제)" } ?: "아니오")
            val m = state.mqtt
            LabelRow(stringResource(R.string.t33_diag_mqtt_auth), "${m.authFailures}회" + (m.lastAuthFailureAtMs?.let { "  마지막 ${formatShortTime(it)}" } ?: ""))
            LabelRow(
                stringResource(R.string.t33_diag_mqtt_rotation),
                "연속 회전 ${r.mqttRotationStreak}회" + (r.mqttNextRotationAtMs?.takeIf { it > now }?.let { "  다음 허용 ${formatShortTime(it)}" } ?: ""),
            )
        }
    }
}

/** T33 — DhLog 링 버퍼 마지막 30줄(I/W/E, 이미 마스킹됨). 전체는 files/dh_log.txt */
@Composable
private fun RecentLogCard(t33: T33DiagState) {
    Card {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.t33_diag_section_logs), style = MaterialTheme.typography.titleMedium)
            HorizontalDivider()
            if (t33.recentLogs.isEmpty()) {
                Text(stringResource(R.string.t33_diag_logs_empty), style = MaterialTheme.typography.bodyMedium)
            } else {
                t33.recentLogs.forEach { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = when {
                            line.contains(" E ") -> MaterialTheme.colorScheme.error
                            line.contains(" W ") -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
            // adb 명령은 debug 빌드에서만 맞다(release 는 run-as 불가) — 1-2
            if (BuildConfig.DEBUG) Text(stringResource(R.string.t33_diag_logs_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            // 어댑터 원시 값(작업 ID 가 섞인 개발용 줄)은 debug 빌드에서만 — 1-2
            if (BuildConfig.DEBUG && capabilities.raw.isNotEmpty()) {
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
            LabelRow(stringResource(R.string.home_label_base_url), if (state.baseUrlOverridden) stringResource(R.string.t32_address_override, state.publicBaseUrl) else state.publicBaseUrl)
            LabelRow(stringResource(R.string.home_label_mqtt_uri), app.donghaeng.car.feature.home.mqttAddressText(state.mqttAddress))
            LabelRow(stringResource(R.string.diag_label_debug), state.debug.toString())
            LabelRow(stringResource(R.string.diag_label_android), state.androidApi.toString())
            LabelRow(stringResource(R.string.diag_label_device), state.deviceModel)
        }
    }
}

/** T16 — MQTT 연결 상태·그룹 세션·마지막 cmd. 브로커 재시작 뒤 reconnects 가 늘고 state 가 CONNECTED 로 돌아오면 자동 복구가 된 것 */
@Composable
private fun MqttCard(state: DiagnosticsUiState, extra: DiagExtraState = DiagExtraState()) {
    Card {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.diag_section_mqtt), style = MaterialTheme.typography.titleMedium)
            HorizontalDivider()
            val m = state.mqtt
            LabelRow(stringResource(R.string.diag_mqtt_state), m.state.name + if (m.tls) "  (TLS)" else "")
            LabelRow(stringResource(R.string.t32_diag_mqtt_transport), stringResource(R.string.t32_diag_mqtt_transport_value, m.transport.label, m.transportFallbacks))
            LabelRow(stringResource(R.string.diag_mqtt_broker), m.brokerUri ?: "-")
            LabelRow(stringResource(R.string.diag_mqtt_reconnects), "${m.reconnects}")
            LabelRow(stringResource(R.string.diag_mqtt_traffic), "in ${m.inboundCount} / out ${m.outboundCount}")
            LabelRow(stringResource(R.string.diag_mqtt_subscribed), if (m.subscribed.isEmpty()) "-" else m.subscribed.joinToString(separator = "\n") { topic -> maskTopic(topic) })
            LabelRow(stringResource(R.string.diag_mqtt_last_error), m.lastError ?: "-")
            LabelRow(stringResource(R.string.diag_mqtt_denied), if (m.deniedTopics.isEmpty()) "-" else m.deniedTopics.joinToString(separator = "\n") { topic -> maskTopic(topic) })
            val rc = extra.reconcile
            LabelRow(stringResource(R.string.diag_reconcile), if (rc.count == 0) "-" else "${rc.count}회" + (rc.lastAtMs?.let { "  마지막 ${formatShortTime(it)}" } ?: "") + (rc.lastTrigger?.let { "  $it" } ?: "") + (rc.lastResult?.let { " → $it" } ?: ""))
            val session = state.groupSession
            LabelRow(stringResource(R.string.diag_group_session), session?.let { "${TextMasking.maskVehicleId(it.groupId)}  ${it.name}" } ?: stringResource(R.string.diag_group_none))
            LabelRow(stringResource(R.string.diag_last_cmd), state.lastCommand?.let { "${it.type} ${it.groupIdMasked ?: ""}".trim() } ?: "-")
            val gs = state.groupState
            val gsAge = state.groupStateAtMs?.let { ts -> "${(System.currentTimeMillis() - ts) / 1000}s 전" } ?: ""
            LabelRow(
                stringResource(R.string.diag_group_state),
                gs?.let { s ->
                    "$gsAge  멤버 ${s.members.size} / 온라인 ${s.members.count { it.online }}\n" +
                        s.members.joinToString("\n") { m ->
                            val speed = m.speedKph?.let { v -> "%.0f km/h".format(v) } ?: "-"
                            val dist = m.distanceToLeaderM?.let { d -> "${d}m" } ?: "-"
                            val age = m.ageMs?.let { a -> "${a / 1000}s" } ?: "-"
                            "${TextMasking.maskNickname(m.nickname)} ${m.status.name} ${if (m.online) "on" else "off"} $speed 선두↔$dist age=$age"
                        }
                } ?: "-",
            )
        }
    }
}

/** T20 — 내비 콜백 종류별 수와 마지막 값. Pleos AVD 에서 내비 앱을 조작하면 어떤 콜백이 오는지 여기서 확인한다 */
@Composable
private fun NaviCard(state: DiagnosticsUiState, onNaviAction: (NaviDebugAction) -> Unit) {
    Card {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.diag_section_navi), style = MaterialTheme.typography.titleMedium)
            HorizontalDivider()
            val n = state.navi
            LabelRow(stringResource(R.string.diag_navi_state), (if (n.running) "구독 중" else "중지") + "  restarts=${n.restarts}  total=${n.totalEvents}")
            LabelRow(stringResource(R.string.diag_navi_counts), if (n.counts.isEmpty()) "-" else n.counts.entries.sortedByDescending { it.value }.joinToString("\n") { "${it.key} ${it.value}" })
            val fixAge = n.lastFixTs?.let { ts -> "${(System.currentTimeMillis() - ts) / 1000}s 전" } ?: ""
            val fixHeading = n.lastFixHeading?.let { h -> "heading=$h" } ?: "heading=?"
            LabelRow(stringResource(R.string.diag_navi_last_fix), n.lastFixMasked?.let { f -> "$f  $fixHeading  $fixAge" } ?: "-")
            LabelRow(stringResource(R.string.diag_navi_status), listOfNotNull(n.lastStatus, n.lastRouteState).joinToString(" / ").ifEmpty { "-" })
            LabelRow(stringResource(R.string.diag_navi_destination), listOfNotNull(n.destinationName, n.waypointCount?.let { "경유지 $it" }).joinToString(" / ").ifEmpty { "-" })
            LabelRow(stringResource(R.string.diag_navi_trip), listOfNotNull(n.lastTrip, n.speedLimitKph?.let { "제한 $it km/h" }, n.lastArrival).joinToString(" / ").ifEmpty { "-" })
            LabelRow(stringResource(R.string.diag_mqtt_last_error), n.lastError ?: "-")
            var confirmRoute by remember { mutableStateOf(false) }
            if (confirmRoute) DebugConfirmDialog(stringResource(R.string.diag_confirm_route), onConfirm = { confirmRoute = false; onNaviAction(NaviDebugAction.REQUEST_ROUTE) }, onDismiss = { confirmRoute = false })
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // 경로 요청·내비 화면은 실제 내비를 바꾼다 — debug 빌드에서만(E035). 모두 주행 중 비활성
                if (state.debug) {
                    OutlinedButton(onClick = { confirmRoute = true }, enabled = !state.isDriving) { Text(stringResource(R.string.diag_navi_action_route)) }
                    OutlinedButton(onClick = { onNaviAction(NaviDebugAction.SHOW_NAVIGATION) }, enabled = !state.isDriving) { Text(stringResource(R.string.diag_navi_action_show)) }
                }
                OutlinedButton(onClick = { onNaviAction(NaviDebugAction.QUERY_DESTINATION) }, enabled = !state.isDriving) { Text(stringResource(R.string.diag_navi_action_dest)) }
            }
            LabelRow(stringResource(R.string.diag_navi_last_action), state.naviActionResult ?: "-")
        }
    }
}

/** T29 — 주행 제한: CarUxRestrictionsManager 의 requiresDistractionOptimization. Virtual Console 속도 > 0 이면 true 가 돼야 하고, 앱 화면은 가려지지 않아야 한다 */
@Composable
private fun DrivingCard(state: DiagnosticsUiState) {
    Card {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.diag_section_uxr), style = MaterialTheme.typography.titleMedium)
            HorizontalDivider()
            val d = state.driving
            LabelRow(stringResource(R.string.diag_uxr_state), if (state.isDriving) "주행 중 — 입력 비활성" else "정차 — 입력 허용")
            LabelRow(stringResource(R.string.diag_uxr_source), "${d.source}   requiresDO=${d.requiresDistractionOptimization ?: "?"}   restrictions=${d.activeRestrictions?.let { r -> "0x" + Integer.toHexString(r) } ?: "-"}   updates=${d.updates}   retries=${d.retries}")
            LabelRow(stringResource(R.string.diag_mqtt_last_error), d.lastError ?: "-")
        }
    }
}

/** T28 — 경로 동기화: 선두차는 ROUTE_CHANGED 발행 수, 멤버는 대기 제안·수락/거절 수. '경로 변경 테스트 발행'(선두차)·제안 수락/거절·자동 따라가기 */
@Composable
private fun RouteSyncCard(state: DiagnosticsUiState, onAction: (RouteSyncDebugAction) -> Unit) {
    Card {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.diag_section_routesync), style = MaterialTheme.typography.titleMedium)
            HorizontalDivider()
            val r = state.routeSync
            val role = when (state.groupSession?.isLeader) { true -> "선두차"; false -> "멤버"; null -> "그룹 없음" }
            LabelRow(stringResource(R.string.diag_rs_role), role + "   자동 따라가기 " + (if (r.autoFollow) "켜짐" else "꺼짐") + (if (r.running) "" else "  (중지)"))
            val sentAge = r.lastRouteChangedAtMs?.let { ts -> "${(System.currentTimeMillis() - ts) / 1000}s 전  " } ?: ""
            LabelRow(stringResource(R.string.diag_rs_sent), "${r.routeChangedSent}건" + (r.lastRouteChangedSummary?.let { s -> "   $sentAge$s" } ?: ""))
            LabelRow(stringResource(R.string.diag_rs_pending), r.pending?.let { p -> "${(p.waypoints.map { w -> w.name } + p.destination.name).joinToString(" → ")}  (${((p.expiresAtMs - System.currentTimeMillis()) / 1000).coerceAtLeast(0)}s 뒤 자동 거절)" } ?: "-")
            LabelRow(stringResource(R.string.diag_rs_acks), "수락 ${r.accepted} / 거절 ${r.declined} / 자동 거절 ${r.autoDeclined}" + (r.lastResult?.let { s -> "   $s" } ?: ""))
            LabelRow(stringResource(R.string.diag_rs_hold), "정차까지 보류 ${r.held} / 음성 답 ${r.voiceAnswered} / 새 제안에 밀림 ${r.superseded}" + (r.pending?.let { p -> if (p.heldUntilParked) "   (지금 보류 중)" else "" } ?: ""))
            // 테스트 발행(그룹 전체에 제안·음성)·수락(내 내비 경로 변경)·거절·자동 따라가기 토글은 debug 빌드·정차 중에만(E035 — 주행 중 잠긴 수락을 여기서 우회하지 않게)
            if (state.debug) {
                var confirmPublish by remember { mutableStateOf(false) }
                if (confirmPublish) DebugConfirmDialog(stringResource(R.string.diag_confirm_publish), onConfirm = { confirmPublish = false; onAction(RouteSyncDebugAction.PUBLISH_TEST) }, onDismiss = { confirmPublish = false })
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { confirmPublish = true }, enabled = state.groupSession?.isLeader == true && !state.isDriving) { Text(stringResource(R.string.diag_rs_action_publish)) }
                    OutlinedButton(onClick = { onAction(RouteSyncDebugAction.ACCEPT) }, enabled = r.pending != null && !state.isDriving) { Text(stringResource(R.string.drive_proposal_accept)) }
                    OutlinedButton(onClick = { onAction(RouteSyncDebugAction.DECLINE) }, enabled = r.pending != null && !state.isDriving) { Text(stringResource(R.string.drive_proposal_decline)) }
                    OutlinedButton(onClick = { onAction(RouteSyncDebugAction.TOGGLE_AUTO) }, enabled = !state.isDriving) { Text(stringResource(R.string.diag_rs_action_auto)) }
                }
            }
        }
    }
}

/**
 * T27 + R19 체인 — 음성 안내: 지금 경로(ANDROID_TTS·PLEOS_TTS·DISPLAY_ONLY)·고른 사유·엔진, 후보별 상태, 요청→시작 지연(최근 10건),
 * 큐·읽음/생략 수, 마지막 문장(종류·길이만). '예시 안내 듣기'(정차 중, release 에도 있음 — 심사 환경 재현)·테스트 발화·켜기/끄기.
 */
@Composable
private fun VoiceCard(state: DiagnosticsUiState, onAction: (VoiceDebugAction) -> Unit, extra: DiagExtraState = DiagExtraState()) {
    Card {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.diag_section_voice), style = MaterialTheme.typography.titleMedium)
            HorizontalDivider()
            val v = state.voice
            val t = state.tts
            LabelRow(stringResource(R.string.diag_voice_path), "${VoiceDiagFormat.pathCode(t)}  ${VoiceDiagFormat.pathLine(t)}")
            LabelRow(stringResource(R.string.diag_voice_path_reason), VoiceDiagFormat.reasonLine(t))
            LabelRow(stringResource(R.string.diag_voice_candidates), VoiceDiagFormat.candidatesText(t))
            LabelRow(stringResource(R.string.diag_voice_latency), VoiceDiagFormat.latencyLine(t.latency))
            LabelRow(stringResource(R.string.diag_voice_state), (if (v.enabled) "켜짐" else "꺼짐") + (if (v.running) "" else "  (중지)") + "   큐 ${v.queued}")
            LabelRow(stringResource(R.string.diag_voice_counts), "읽음 ${v.spoken}   생략: 오래됨 ${v.skippedStale} · 내 차 ${v.skippedOwn} · 중복 ${v.skippedDuplicate} · 꺼짐 ${v.skippedDisabled} · 문구 없음 ${v.skippedNoSpeech} · 지난 그룹 ${v.droppedOldGroup}   무전에 양보 ${v.yieldedToMic}")
            LabelRow(stringResource(R.string.diag_ptt_stats), extra.pttStats.toString())
            LabelRow(stringResource(R.string.diag_voice_tts_counts), VoiceDiagFormat.countsLine(t))
            val age = v.lastSpokenAtMs?.let { ts -> "${(System.currentTimeMillis() - ts) / 1000}s 전  " } ?: ""
            LabelRow(stringResource(R.string.diag_voice_last), v.lastSpokenType?.let { ty -> "$age$ty ${v.lastSpokenPriority} (len=${v.lastSpokenLen})" } ?: "-")
            Text(stringResource(R.string.diag_voice_sample_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { onAction(VoiceDebugAction.SAMPLE) }, enabled = !state.isDriving) { Text(stringResource(R.string.diag_voice_action_sample)) }
                // 테스트 발화·켜기/끄기는 debug 빌드에서만(E035 — 음성 켜기/끄기는 설정 '음성 안내')
                if (state.debug) {
                    OutlinedButton(onClick = { onAction(VoiceDebugAction.SPEAK_TEST) }, enabled = !state.isDriving) { Text(stringResource(R.string.diag_voice_action_test)) }
                    OutlinedButton(onClick = { onAction(VoiceDebugAction.TOGGLE) }, enabled = !state.isDriving) { Text(if (v.enabled) stringResource(R.string.diag_voice_action_off) else stringResource(R.string.diag_voice_action_on)) }
                }
            }
        }
    }
}

/** T26 — 차량 이벤트 판정기·발행 상태. Virtual Console 로 SoC 12, 속도 60→0+브레이크, 60초 정차 등을 넣으면 여기 수가 늘어야 한다 */
@Composable
private fun EventEngineCard(state: DiagnosticsUiState) {
    Card {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.diag_section_events), style = MaterialTheme.typography.titleMedium)
            HorizontalDivider()
            val e = state.events
            LabelRow(stringResource(R.string.diag_tm_state), if (!e.running) "중지" else if (e.active) "판정 중 (샘플 ${e.samples})" else "대기 (활성 그룹 없음)")
            LabelRow(stringResource(R.string.diag_ev_counts), if (e.detectedCounts.isEmpty()) "-" else e.detectedCounts.entries.joinToString("   ") { c -> "${c.key} ${c.value}" })
            LabelRow(stringResource(R.string.diag_ev_published), "${e.publishedCount} / 실패 ${e.failedCount}")
            val age = e.lastEventTs?.let { ts -> "${(System.currentTimeMillis() - ts) / 1000}s 전  " } ?: ""
            LabelRow(stringResource(R.string.diag_ev_last), e.lastEvent?.let { s -> age + s } ?: "-")
            LabelRow(stringResource(R.string.diag_ev_detector), "에너지 경고 ${e.energyAlertPct?.let { p -> "$p%" } ?: "-"}   정차 ${e.stoppedForS?.let { s -> "${s}s" } ?: "-"}")
        }
    }
}

/** T23 — 텔레메트리 발행 상태. 활성 그룹이 있으면 1초마다 발행 수가 늘어야 한다 */
@Composable
private fun TelemetryCard(state: DiagnosticsUiState, extra: DiagExtraState = DiagExtraState()) {
    Card {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.diag_section_telemetry), style = MaterialTheme.typography.titleMedium)
            HorizontalDivider()
            val t = state.telemetry
            val status = when {
                !t.running -> "중지"
                t.active -> "발행 중 (${t.groupName ?: ""})"
                else -> "대기 (활성 그룹 없음)"
            }
            LabelRow(stringResource(R.string.diag_tm_state), status)
            LabelRow(stringResource(R.string.diag_tm_counts), "${t.sentCount} / ${t.failedCount} / ${t.skippedNoFix}")
            val age = t.lastSentTs?.let { ts -> "${(System.currentTimeMillis() - ts) / 1000}s 전" } ?: "-"
            LabelRow(stringResource(R.string.diag_tm_last), age + (t.lastSummary?.let { s -> "  $s" } ?: ""))
            LabelRow(stringResource(R.string.diag_tm_extras), "eta ${t.etaSentCount} / energy ${t.energySentCount}")
            LabelRow(stringResource(R.string.diag_tm_topic), t.topicMasked ?: "-")
            LabelRow(stringResource(R.string.diag_tm_service), (if (state.driveServiceRunning) "실행 중" else "없음") + (extra.lastStartFailure?.let { "   마지막 시작 실패 $it" } ?: ""))
        }
    }
}

/** T22 — 차량 신호 최신값·수신 수·스트림 상태. Virtual Console 로 값을 바꾸면 여기와 로그 `vehicle signal …` 가 따라와야 한다 */
@Composable
private fun VehicleSignalCard(state: DiagnosticsUiState, onRestart: () -> Unit) {
    Card {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.diag_section_vehicle_signals), style = MaterialTheme.typography.titleMedium)
            HorizontalDivider()
            val v = state.vehicle
            fun n(x: Any?) = x?.toString() ?: "-"
            val speedText = v.speedKph?.let { s -> "%.1f km/h".format(s) } ?: "-"
            LabelRow(stringResource(R.string.diag_vs_energy), "연료 ${n(v.fuelPercent)}%   EV SoC ${n(v.evSoc)}%")
            LabelRow(stringResource(R.string.diag_vs_drive), "속도 $speedText   기어 ${n(v.gear)}   브레이크 ${n(v.brakePedal)}")
            LabelRow(stringResource(R.string.diag_vs_body), "방향지시등 ${n(v.turnSignal)}   에어백 전개 ${n(v.airbagDeployed)}")
            val now = System.currentTimeMillis()
            LabelRow(stringResource(R.string.diag_vs_updates), if (v.updates.isEmpty()) "-" else v.updates.entries.joinToString("   ") { e -> "${e.key} ${e.value}" + (v.lastTs[e.key]?.let { ts -> "(${(now - ts) / 1000}s)" } ?: "") })
            LabelRow(stringResource(R.string.diag_vs_streams), if (v.streams.isEmpty()) "-" else v.streams.entries.filter { e -> e.value != "구독 중" }.joinToString("\n") { e -> "${e.key}: ${e.value}" }.ifEmpty { "모두 구독 중" })
            LabelRow(stringResource(R.string.diag_loc_counts), "total ${v.totalUpdates} / restarts ${v.restarts}" + if (v.running) "" else "  (중지)")
            OutlinedButton(onClick = onRestart, enabled = !state.isDriving) { Text(stringResource(R.string.diag_vs_action_restart)) }
        }
    }
}

/** T21 — 위치 체인(활성 소스·가용성·전환)·마지막 위치(마스킹)·위치 계산 속도·권한. 버튼으로 권한 요청·재탐색 */
@Composable
private fun LocationCard(state: DiagnosticsUiState, onAction: (LocationDebugAction) -> Unit) {
    Card {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.diag_section_location), style = MaterialTheme.typography.titleMedium)
            HorizontalDivider()
            val l = state.location
            val c = l.chain
            val order = l.sourceOrder.joinToString(" → ") { it.name }
            val running = if (l.running) "" else "  (중지)"
            LabelRow(stringResource(R.string.diag_loc_active), (c.active?.name ?: "-") + (if (order.isNotEmpty()) "   [$order]" else "") + running)
            LabelRow(stringResource(R.string.diag_loc_availability), if (c.availability.isEmpty()) "-" else c.availability.entries.joinToString("   ") { e -> e.key.name + "=" + (if (e.value) "가능" else "불가") })
            val lastStop = c.lastStop?.let { s -> "   마지막 중단: $s" } ?: ""
            LabelRow(stringResource(R.string.diag_loc_counts), "fix ${c.fixCount} / 전환 ${c.switches} / restarts ${l.restarts}$lastStop")
            val age = l.lastFixTs?.let { ts -> "${(System.currentTimeMillis() - ts) / 1000}s 전" } ?: ""
            val fixHeading = l.lastHeading?.let { h -> "heading=$h" } ?: "heading=?"
            val fixAcc = l.lastAccuracy?.let { a -> "acc=$a" } ?: "acc=?"
            val fixSrc = l.lastSource?.name ?: "?"
            LabelRow(stringResource(R.string.diag_loc_last_fix), l.lastFixMasked?.let { f -> "$f  src=$fixSrc  $fixHeading  $fixAcc  $age" } ?: "-")
            LabelRow(stringResource(R.string.diag_loc_speed), l.speedKph?.let { s -> "%.1f km/h".format(s) } ?: "-")
            fun g(b: Boolean) = if (b) "승인" else "미승인"
            LabelRow(stringResource(R.string.diag_loc_permissions), "FINE_LOCATION=${g(l.fineLocationGranted)}   FUSED_LOCATION=${g(l.fusedLocationGranted)}   POST_NOTIFICATIONS=${g(l.notificationsGranted)}")
            LabelRow(stringResource(R.string.diag_mqtt_last_error), l.lastError ?: c.lastError ?: "-")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { onAction(LocationDebugAction.REQUEST_PERMISSION) }, enabled = !state.isDriving) { Text(stringResource(R.string.diag_loc_action_permission)) }
                OutlinedButton(onClick = { onAction(LocationDebugAction.RESTART) }, enabled = !state.isDriving) { Text(stringResource(R.string.diag_loc_action_restart)) }
            }
        }
    }
}

/** 토픽 속 `veh_…`/`grp_…` 만 마스킹 */
private fun maskTopic(topic: String): String =
    topic.split('/').joinToString("/") { seg -> if (seg.startsWith("veh_") || seg.startsWith("grp_")) TextMasking.maskVehicleId(seg) else seg }

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

/** T31 — 지연 측정: 시계 오프셋(서버-차량)·왕복, 그룹 EVENT/STATE 의 봉투 ts → 수신 지연(최근 60초 마지막/평균/최대). '지금 동기화' */
@Composable
private fun LatencyCard(state: DiagnosticsUiState, onResync: () -> Unit) {
    Card {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.diag_section_latency), style = MaterialTheme.typography.titleMedium)
            HorizontalDivider()
            val c = state.clockSync
            val l = state.latency
            val age = c.lastSyncAtMs?.let { ts -> "${(System.currentTimeMillis() - ts) / 1000}s 전" } ?: "-"
            LabelRow(stringResource(R.string.diag_lat_clock), if (c.synced) "오프셋 ${c.offsetMs} ms (서버 − 차량)   왕복 ${c.rttMs} ms   표본 ${c.samples}   동기화 ${c.syncs}회 · $age" else "동기화 전" + (c.lastError?.let { e -> "  ($e)" } ?: ""))
            fun stat(st: app.donghaeng.car.domain.latency.LatencyStat?) = st?.let { "마지막 ${it.lastMs} ms · 평균 ${it.avgMs} ms · 최대 ${it.maxMs} ms · ${it.count}건/60s" } ?: "-"
            LabelRow(stringResource(R.string.diag_lat_event), stat(l.event))
            LabelRow(stringResource(R.string.diag_lat_state), stat(l.state))
            LabelRow(stringResource(R.string.diag_mqtt_last_error), "오버레이 ${if (l.overlayEnabled) "켜짐" else "꺼짐"}   동기화 전 수신 ${l.unsynced}   음수 보정 ${l.clamped}")
            OutlinedButton(onClick = onResync) { Text(stringResource(R.string.diag_lat_action_resync)) }
        }
    }
}
