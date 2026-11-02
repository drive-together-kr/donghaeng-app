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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import app.donghaeng.car.BuildConfig
import app.donghaeng.car.R
import app.donghaeng.car.adapter.sdk.ProbeLine
import app.donghaeng.car.core.common.TokenClearReason
import app.donghaeng.car.core.network.MqttAddressDisplay
import app.donghaeng.car.ui.components.formatShortTime
import app.donghaeng.car.ui.theme.DongHaengTheme

/** 홈 화면 진입점 — ViewModel 연결. */
@Composable
fun HomeRoute(
    onPairPhone: () -> Unit,
    onStartGroup: () -> Unit,
    onOpenGroup: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenTour: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    HomeScreen(
        state = state,
        onPrimary = {
            when (state.primaryAction) {
                HomeAction.PAIR_PHONE -> onPairPhone()
                HomeAction.START_GROUP -> onStartGroup()
                HomeAction.OPEN_GROUP -> onOpenGroup()
            }
        },
        onOpenSettings = onOpenSettings,
        onOpenDiagnostics = onOpenDiagnostics,
        onOpenTour = onOpenTour,
        onRetryStore = viewModel::retryTokenStore,
    )
}

/**
 * 홈 화면. 세로·가로 모두 지원:
 * - 넓은 화면(가로, Pleos 2560x1440)에서는 좌 = 상태·주 버튼, 우 = '동행은 이렇게 써요'(그룹 없을 때) / 빌드 정보(debug 빌드만)
 * - 좁은 화면(세로)에서는 한 단으로 쌓고 스크롤
 * 상태별 주 버튼은 하나(2-2·E117): 연결 안 됨 [내 폰과 연결하기] / 연결됨 [같이 가기 시작] / 그룹 있음 [그룹 보기]. 다시 연결은 설정 '연결 해제'로.
 * 개발 정보(빌드 변형·서버·MQTT 주소·SDK 클래스명)는 release 홈에 두지 않고(E040) 한 줄 상태 칩 "차량 SDK 5/5 · 실시간 연결됨 ›"(누르면 진단)으로 줄였다.
 * 폰이 없어도 [예시 화면 둘러보기](2-1)로 그룹 주행 화면을 예시 데이터로 볼 수 있다.
 */
@Composable
fun HomeScreen(
    state: HomeUiState,
    onPrimary: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenTour: () -> Unit = {},
    onRetryStore: () -> Unit = {},
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
                .padding(horizontal = 40.dp, vertical = 28.dp)

            if (twoPane) {
                Row(modifier = contentModifier, horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        HeaderSection(state, onOpenDiagnostics)
                        StatusBanner(state, onRetryStore)
                        Spacer(Modifier.height(28.dp))
                        ActionSection(state, onPrimary, onOpenSettings, onOpenTour)
                    }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (state.groupSession == null) HowToCard() else DrivingHowToCard()
                        if (BuildConfig.DEBUG) BuildInfoCard(state)
                    }
                }
            } else {
                Column(modifier = contentModifier) {
                    HeaderSection(state, onOpenDiagnostics)
                    StatusBanner(state, onRetryStore)
                    Spacer(Modifier.height(28.dp))
                    ActionSection(state, onPrimary, onOpenSettings, onOpenTour)
                    if (state.groupSession == null) {
                        Spacer(Modifier.height(24.dp))
                        HowToCard()
                    }
                    if (BuildConfig.DEBUG) {
                        Spacer(Modifier.height(16.dp))
                        BuildInfoCard(state)
                    }
                }
            }
        }
    }
}

@Composable
private fun HeaderSection(state: HomeUiState, onOpenDiagnostics: () -> Unit) {
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
        VehicleConnection.UNAVAILABLE -> stringResource(R.string.t33_home_connection_unavailable)
        VehicleConnection.CONNECTED ->
            if (state.nickname.isNullOrBlank()) stringResource(R.string.home_connection_connected)
            else stringResource(R.string.home_connection_connected_nick, state.nickname)
    }
    // '폰 계정 연결'(페어링)이 됐어도 서버 실시간 연결이 끊긴 배너가 있으면 초록으로 칠하지 않는다(E122 — 한 화면에 '연결됨'과 '끊김')
    val good = state.connection == VehicleConnection.CONNECTED && state.banner !is HomeBanner.ServerDisconnected
    Text(
        "${stringResource(R.string.home_vehicle_connection)}: $connectionText",
        style = MaterialTheme.typography.titleLarge,
        color = if (good) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface,
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
    Spacer(Modifier.height(12.dp))
    StatusChip(state, onOpenDiagnostics)
}

/** 한 줄 상태 칩 — "차량 SDK 5/5 · 실시간 연결됨 ›"(fake 는 "가상 차량 신호"). 누르면 진단(읽기 전용 화면이라 주행 중에도 열 수 있다) */
@Composable
private fun StatusChip(state: HomeUiState, onOpenDiagnostics: () -> Unit) {
    val vehicle = when {
        !state.showSdkProbe -> stringResource(R.string.home_chip_fake)
        state.sdkAvailable == null || state.sdkTotal == null -> stringResource(R.string.home_chip_sdk_pending)
        else -> stringResource(R.string.home_chip_sdk, state.sdkAvailable!!, state.sdkTotal!!)
    }
    val realtime = when (state.realtime) {
        Realtime.CONNECTED -> stringResource(R.string.home_chip_realtime_on)
        Realtime.CONNECTING -> stringResource(R.string.home_chip_realtime_connecting)
        Realtime.OFF -> stringResource(R.string.home_chip_realtime_off)
    }
    AssistChip(
        onClick = onOpenDiagnostics,
        label = { Text("$vehicle · $realtime", style = MaterialTheme.typography.bodyLarge) },
        trailingIcon = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.home_chip_desc), modifier = Modifier.padding(end = 4.dp)) },
        colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.heightIn(min = 56.dp),
    )
}

/**
 * T33 상태 배너 — 저장소 오류('다시 시도') / 다시 연결 필요 / 서버 실시간 연결 끊김(30초 이상). 주행 중엔 버튼 없이 문구만.
 * 원인은 사람 말로 분류해 보이고(시간 초과·주소 설정·네트워크·인증), 예외 원문은 진단 화면에만 둔다(E119). 경과는 60초부터 'N분째'.
 */
@Composable
private fun StatusBanner(state: HomeUiState, onRetryStore: () -> Unit) {
    val banner = state.banner ?: return
    val (title, body, action) = when (banner) {
        // 세 번째 = 버튼 동작(없으면 null)
        is HomeBanner.StorageUnavailable -> Triple<String, String, (() -> Unit)?>(
            stringResource(R.string.t33_banner_storage_title),
            if (banner.exhausted) stringResource(R.string.t33_banner_storage_exhausted)
            else stringResource(R.string.t33_banner_storage_body, banner.attempts, banner.maxAttempts),
            onRetryStore,
        )
        is HomeBanner.RepairNeeded -> Triple<String, String, (() -> Unit)?>(
            stringResource(R.string.t33_banner_repair_title),
            if (banner.reason == TokenClearReason.TOKEN_REVOKED_CMD) stringResource(R.string.t33_banner_repair_revoked) else stringResource(R.string.t33_banner_repair_rejected),
            null,
        )
        is HomeBanner.RepairSoon -> Triple<String, String, (() -> Unit)?>(
            stringResource(R.string.t33_banner_repair_title),
            banner.expiresAtMs?.let { stringResource(R.string.t33_banner_repair_soon, formatShortTime(it)) } ?: stringResource(R.string.t33_banner_repair_soon_unknown),
            null,
        )
        is HomeBanner.ServerDisconnected -> {
            val elapsed = if (banner.seconds >= 60) stringResource(R.string.t33_banner_server_body_min, (banner.seconds / 60).toInt())
            else stringResource(R.string.t33_banner_server_body, banner.seconds.toInt())
            val reason = when (banner.reason) {
                HomeBanner.ServerReason.AUTH -> stringResource(R.string.t33_banner_server_auth)
                HomeBanner.ServerReason.TIMEOUT -> stringResource(R.string.t33_banner_server_timeout)
                HomeBanner.ServerReason.REFUSED -> stringResource(R.string.t33_banner_server_refused)
                HomeBanner.ServerReason.UNREACHABLE -> stringResource(R.string.t33_banner_server_unreachable)
                HomeBanner.ServerReason.OTHER -> ""
            }
            Triple<String, String, (() -> Unit)?>(stringResource(R.string.t33_banner_server_title), listOf(elapsed, reason).filter { it.isNotEmpty() }.joinToString(" "), null)
        }
    }
    Spacer(Modifier.height(16.dp))
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(body, style = MaterialTheme.typography.bodyLarge)
            if (action != null && !state.isDriving) {
                Button(onClick = action, modifier = Modifier.heightIn(min = 76.dp)) { Text(stringResource(R.string.t33_banner_retry)) }
            }
        }
    }
}

@Composable
private fun ActionSection(
    state: HomeUiState,
    onPrimary: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTour: () -> Unit,
) {
    val label = when (state.primaryAction) {
        HomeAction.PAIR_PHONE -> stringResource(R.string.home_btn_pair_phone)
        HomeAction.START_GROUP -> stringResource(R.string.home_btn_start_group)
        HomeAction.OPEN_GROUP -> stringResource(R.string.home_btn_open_group)
    }
    Button(
        onClick = onPrimary,
        enabled = state.primaryEnabled,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 84.dp),
    ) {
        Text(label, style = MaterialTheme.typography.titleLarge)
    }
    Spacer(Modifier.height(10.dp))
    if (state.isDriving) {
        Text(stringResource(R.string.home_driving_hint), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.tertiary)
    } else if (state.primaryAction == HomeAction.PAIR_PHONE) {
        // 첫 단계 안내는 연결 전에만(연결된 뒤에도 '연결된 뒤 쓸 수 있어요'가 남아 헷갈렸다, E117)
        Text(
            stringResource(R.string.home_buttons_hint),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(20.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = onOpenSettings, modifier = Modifier.weight(1f).heightIn(min = 76.dp)) {
            Text(stringResource(R.string.home_btn_settings))
        }
        // 폰(공식 앱)이 없어도 그룹 주행 화면을 예시 데이터로 본다(2-1) — 그룹이 없을 때만, 정차 중에만
        if (state.groupSession == null) {
            OutlinedButton(onClick = onOpenTour, enabled = !state.isDriving, modifier = Modifier.weight(1f).heightIn(min = 76.dp)) {
                Text(stringResource(R.string.home_btn_tour))
            }
        }
    }
    if (state.groupSession == null && state.primaryAction == HomeAction.PAIR_PHONE) {
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.home_tour_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 그룹이 있을 때 홈 오른쪽 — 주행 중 동행이 어떻게 알려 주는지(앞 화면은 순정 내비, 패널·음성·핸들 무전) */
@Composable
private fun DrivingHowToCard() {
    Card(modifier = Modifier.fillMaxWidth().widthIn(max = 960.dp)) {
        Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            HowToStep(R.string.home_howto_3_title, R.string.home_howto_3_body)
            Text(stringResource(R.string.dash_ptt_hint), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** '동행은 이렇게 써요' 3단계 — 그룹이 없을 때 홈 오른쪽(또는 아래) */
@Composable
private fun HowToCard() {
    Card(modifier = Modifier.fillMaxWidth().widthIn(max = 960.dp)) {
        Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.home_howto_title), style = MaterialTheme.typography.titleLarge)
            HorizontalDivider()
            HowToStep(R.string.home_howto_1_title, R.string.home_howto_1_body)
            HowToStep(R.string.home_howto_2_title, R.string.home_howto_2_body)
            HowToStep(R.string.home_howto_3_title, R.string.home_howto_3_body)
        }
    }
}

@Composable
private fun HowToStep(titleRes: Int, bodyRes: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(titleRes), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Text(stringResource(bodyRes), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 개발용 빌드 정보 — debug 빌드 홈에만(release 는 진단 화면의 빌드 카드에서 본다, E040) */
@Composable
private fun BuildInfoCard(state: HomeUiState) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            InfoRow(stringResource(R.string.home_label_flavor), state.flavor)
            InfoRow(stringResource(R.string.home_label_version), state.versionName)
            InfoRow(stringResource(R.string.home_label_base_url), if (state.baseUrlOverridden) stringResource(R.string.t32_address_override, state.publicBaseUrl) else state.publicBaseUrl)
            InfoRow(stringResource(R.string.home_label_mqtt_uri), mqttAddressText(state.mqttAddress))
        }
    }
}

/** T32 리뷰: 'MQTT 주소' = 실제로 쓰는 주소와 출처(사용자 지정 / 서버가 준 주소 / 페어링 전) — 빌드 기본 MQTT 주소는 접속에 쓰이지 않아 보이지 않는다 */
@Composable
internal fun mqttAddressText(display: MqttAddressDisplay): String = when (display.source) {
    MqttAddressDisplay.Source.OVERRIDE -> stringResource(R.string.t32_address_override, display.uri.orEmpty())
    MqttAddressDisplay.Source.SERVER -> stringResource(R.string.t32_address_from_server, display.uri.orEmpty())
    MqttAddressDisplay.Source.UNPAIRED -> stringResource(R.string.t32_mqtt_address_unpaired)
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(160.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyLarge)
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
                canStartGroup = true,
                realtime = Realtime.CONNECTED,
                sdkProbe = listOf(
                    ProbeLine("Vehicle 2.3.2", true, "ai.pleos.playground.vehicle.Vehicle"),
                    ProbeLine("NaviHelper 3.0.5", false, "ClassNotFoundException"),
                ),
            ),
            onPrimary = {},
            onOpenSettings = {},
            onOpenDiagnostics = {},
        )
    }
}
