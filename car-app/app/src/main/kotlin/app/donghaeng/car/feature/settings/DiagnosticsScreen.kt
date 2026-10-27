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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.donghaeng.car.R
import app.donghaeng.car.adapter.sdk.ProbeLine
import app.donghaeng.car.core.common.LocationPermissions
import app.donghaeng.car.core.common.TextMasking
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
        onRouteSyncAction = viewModel::onRouteSyncAction,
    )
}

/**
 * 진단 화면 (T11, docs/09 §3 체크리스트를 화면으로). 표: 항목 | 지원 | 상세 | 출처 — `adb logcat -s DH_CAP` 과 같은 내용.
 * 사용자가 결과를 캡처해 붙여넣기만 하면 되게 만든 확인용 화면(CLAUDE.md 작업 방식).
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
                OutlinedButton(onClick = onBack, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.diag_back)) }
                Spacer(Modifier.width(20.dp))
                Text(stringResource(R.string.diag_title), style = MaterialTheme.typography.headlineMedium)
            }

            VehicleCard(state)
            CapabilityCard(state.report?.capabilities)
            ProbeCard(state.probe)
            MqttCard(state)
            NaviCard(state, onNaviAction)
            LocationCard(state, onLocationAction)
            VehicleSignalCard(state, onRestartVehicleSignals)
            TelemetryCard(state)
            EventEngineCard(state)
            VoiceCard(state, onVoiceAction)
            RouteSyncCard(state, onRouteSyncAction)
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

/** T16 — MQTT 연결 상태·그룹 세션·마지막 cmd. 브로커 재시작 뒤 reconnects 가 늘고 state 가 CONNECTED 로 돌아오면 자동 복구가 된 것 */
@Composable
private fun MqttCard(state: DiagnosticsUiState) {
    Card {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.diag_section_mqtt), style = MaterialTheme.typography.titleMedium)
            HorizontalDivider()
            val m = state.mqtt
            LabelRow(stringResource(R.string.diag_mqtt_state), m.state.name + if (m.tls) "  (TLS)" else "")
            LabelRow(stringResource(R.string.diag_mqtt_broker), m.brokerUri ?: "-")
            LabelRow(stringResource(R.string.diag_mqtt_reconnects), "${m.reconnects}")
            LabelRow(stringResource(R.string.diag_mqtt_traffic), "in ${m.inboundCount} / out ${m.outboundCount}")
            LabelRow(stringResource(R.string.diag_mqtt_subscribed), if (m.subscribed.isEmpty()) "-" else m.subscribed.joinToString(separator = "\n") { topic -> maskTopic(topic) })
            LabelRow(stringResource(R.string.diag_mqtt_last_error), m.lastError ?: "-")
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
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { onNaviAction(NaviDebugAction.REQUEST_ROUTE) }, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.diag_navi_action_route)) }
                OutlinedButton(onClick = { onNaviAction(NaviDebugAction.QUERY_DESTINATION) }, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.diag_navi_action_dest)) }
                OutlinedButton(onClick = { onNaviAction(NaviDebugAction.SHOW_NAVIGATION) }, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.diag_navi_action_show)) }
            }
            LabelRow(stringResource(R.string.diag_navi_last_action), state.naviActionResult ?: "-")
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
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { onAction(RouteSyncDebugAction.PUBLISH_TEST) }, enabled = state.groupSession?.isLeader == true, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.diag_rs_action_publish)) }
                OutlinedButton(onClick = { onAction(RouteSyncDebugAction.ACCEPT) }, enabled = r.pending != null, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.drive_proposal_accept)) }
                OutlinedButton(onClick = { onAction(RouteSyncDebugAction.DECLINE) }, enabled = r.pending != null, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.drive_proposal_decline)) }
                OutlinedButton(onClick = { onAction(RouteSyncDebugAction.TOGGLE_AUTO) }, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.diag_rs_action_auto)) }
            }
        }
    }
}

/** T27 — 음성 안내: TTS 엔진 상태(Pleos AVD 는 엔진 없음 → 로그 폴백), 큐·읽음/생략 수, 마지막 문장(종류·길이만). 테스트 발화·켜기/끄기 */
@Composable
private fun VoiceCard(state: DiagnosticsUiState, onAction: (VoiceDebugAction) -> Unit) {
    Card {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.diag_section_voice), style = MaterialTheme.typography.titleMedium)
            HorizontalDivider()
            val v = state.voice
            val t = state.tts
            val ttsText = when (t.ready) {
                null -> "초기화 중"
                true -> "준비 (${t.engine ?: "?"})"
                false -> "없음 → 로그 폴백"
            } + (t.detail?.let { d -> "  $d" } ?: "")
            LabelRow(stringResource(R.string.diag_voice_tts), ttsText)
            LabelRow(stringResource(R.string.diag_voice_state), (if (v.enabled) "켜짐" else "꺼짐") + (if (v.running) "" else "  (중지)") + "   큐 ${v.queued}")
            LabelRow(stringResource(R.string.diag_voice_counts), "읽음 ${v.spoken}   생략: 오래됨 ${v.skippedStale} · 내 차 ${v.skippedOwn} · 중복 ${v.skippedDuplicate} · 꺼짐 ${v.skippedDisabled} · 문구 없음 ${v.skippedNoSpeech}")
            LabelRow(stringResource(R.string.diag_voice_tts_counts), "speak ${t.spokenCount} / 폴백 ${t.fallbackCount} / 실패 ${t.failedCount}   포커스 ${if (t.focusGranted) "보유" else "-"}")
            val age = v.lastSpokenAtMs?.let { ts -> "${(System.currentTimeMillis() - ts) / 1000}s 전  " } ?: ""
            LabelRow(stringResource(R.string.diag_voice_last), v.lastSpokenType?.let { ty -> "$age$ty ${v.lastSpokenPriority} (len=${v.lastSpokenLen})" } ?: "-")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { onAction(VoiceDebugAction.SPEAK_TEST) }, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.diag_voice_action_test)) }
                OutlinedButton(onClick = { onAction(VoiceDebugAction.TOGGLE) }, modifier = Modifier.heightIn(min = 56.dp)) { Text(if (v.enabled) stringResource(R.string.diag_voice_action_off) else stringResource(R.string.diag_voice_action_on)) }
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
private fun TelemetryCard(state: DiagnosticsUiState) {
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
            LabelRow(stringResource(R.string.diag_tm_service), if (state.driveServiceRunning) "실행 중" else "없음")
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
            OutlinedButton(onClick = onRestart, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.diag_vs_action_restart)) }
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
                OutlinedButton(onClick = { onAction(LocationDebugAction.REQUEST_PERMISSION) }, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.diag_loc_action_permission)) }
                OutlinedButton(onClick = { onAction(LocationDebugAction.RESTART) }, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.diag_loc_action_restart)) }
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
