package app.donghaeng.car.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.donghaeng.car.BuildConfig
import app.donghaeng.car.R
import app.donghaeng.car.adapter.sdk.ProbeLine
import app.donghaeng.car.core.common.TokenClearReason
import app.donghaeng.car.core.network.MqttAddressDisplay
import app.donghaeng.car.feature.creategroup.DESTINATION_PRESETS
import app.donghaeng.car.ui.components.DestinationArt
import app.donghaeng.car.ui.components.DestinationBanner
import app.donghaeng.car.ui.components.DestinationPhotoCard
import app.donghaeng.car.ui.components.DhCard
import app.donghaeng.car.ui.components.DhIcons
import app.donghaeng.car.ui.components.DhPrimaryButton
import app.donghaeng.car.ui.components.DhSecondaryButton
import app.donghaeng.car.ui.components.RoadCar
import app.donghaeng.car.ui.components.RoadSign
import app.donghaeng.car.ui.components.RoadStrip
import app.donghaeng.car.ui.components.formatShortTime
import app.donghaeng.car.ui.theme.DhPalette
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
    /** 홈 사진 줄에서 추천 목적지 탭 → 그 목적지를 골라 둔 그룹 만들기 */
    onPickDestination: (String) -> Unit = {},
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
        // 연결 전이면 사진을 눌러도 먼저 폰 연결로(다음에 할 일)
        onPickDestination = { name -> if (state.primaryAction == HomeAction.START_GROUP) onPickDestination(name) else if (state.primaryAction == HomeAction.PAIR_PHONE) onPairPhone() },
    )
}

/**
 * 홈 화면('밤 고속도로' 디자인, 2026-11-05 — docs/05 §5.0).
 * 맨 위 이름표·상태 칩 → 서명 요소 '도로 띠'(내 차 → 목적지 표지) → 상태별 큰 제목 한 줄·설명 한 문장·주 버튼 하나 / 오른쪽 3단계 안내(지금 단계 강조).
 * 좁은 화면(세로)에서는 한 단으로 쌓고 스크롤.
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
    onPickDestination: (String) -> Unit = {},
) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            val twoPane = maxWidth >= 840.dp
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = if (twoPane) 48.dp else 28.dp, vertical = 28.dp),
            ) {
                TopBar(state, onOpenDiagnostics, showTagline = twoPane)
                StatusBanner(state, onRetryStore)
                Spacer(Modifier.height(if (twoPane) 24.dp else 16.dp))
                HomeRoad(state, Modifier.fillMaxWidth().height(if (twoPane) 190.dp else 150.dp), compactSign = !twoPane)
                Spacer(Modifier.height(if (twoPane) 28.dp else 20.dp))
                if (twoPane) {
                    Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                        Column(Modifier.weight(1.1f)) {
                            HeroText(state)
                            Spacer(Modifier.height(28.dp))
                            ActionSection(state, onPrimary, onOpenSettings, onOpenTour)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            StepsCard(state)
                            if (BuildConfig.DH_SHOW_DEV_INFO) BuildInfoCard(state)
                        }
                    }
                    Spacer(Modifier.height(36.dp))
                    val session = state.groupSession
                    if (session == null) {
                        DestinationStrip(state, columns = 5, onPickDestination)
                    } else {
                        DestinationBanner(session.destination.name, stringResource(R.string.group_destination), Modifier.fillMaxWidth(), height = 300.dp)
                    }
                } else {
                    HeroText(state)
                    Spacer(Modifier.height(24.dp))
                    ActionSection(state, onPrimary, onOpenSettings, onOpenTour)
                    Spacer(Modifier.height(24.dp))
                    StepsCard(state)
                    if (state.groupSession == null) {
                        Spacer(Modifier.height(28.dp))
                        DestinationStrip(state, columns = 2, onPickDestination)
                    }
                    if (BuildConfig.DH_SHOW_DEV_INFO) {
                        Spacer(Modifier.height(16.dp))
                        BuildInfoCard(state)
                    }
                }
            }
        }
    }
}

/** 맨 위 줄 — 이름표(동행 + 한 줄 소개)와 오른쪽 상태 칩(누르면 진단) */
@Composable
private fun TopBar(state: HomeUiState, onOpenDiagnostics: () -> Unit, showTagline: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(16.dp))
        Box(Modifier.weight(1f)) {
            if (showTagline) {
                Text(stringResource(R.string.app_tagline), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        StatusChip(state, onOpenDiagnostics)
    }
}

/**
 * 서명 요소 — 도로 띠. 내 차가 도로 위에 있고 오른쪽 끝이 목적지 표지(그룹이 없으면 흐린 '목적지 미정' 표지).
 * 정차 중에만 중앙 점선이 천천히 흐른다(주행 중 화면 움직임 없음).
 */
@Composable
private fun HomeRoad(state: HomeUiState, modifier: Modifier, compactSign: Boolean) {
    val me = state.nickname?.takeIf { it.isNotBlank() } ?: stringResource(R.string.home_road_my_car)
    val session = state.groupSession
    RoadStrip(
        cars = listOf(RoadCar(me, MeBlue, isMe = true, position = if (session != null) 0.35f else 0.12f)),
        modifier = modifier,
        moving = !state.isDriving,
    ) {
        if (session != null) {
            RoadSign(session.destination.name, subtitle = stringResource(R.string.group_destination), large = !compactSign, modifier = Modifier.widthIn(max = 420.dp))
        } else {
            RoadSign(stringResource(R.string.home_road_no_destination), large = !compactSign, muted = true, modifier = Modifier.widthIn(max = 420.dp))
        }
    }
}

/** 상태별 큰 제목 한 줄 + 설명 한 문장 + 연결 상태 한 줄 */
@Composable
private fun HeroText(state: HomeUiState) {
    val session = state.groupSession
    val (title, body) = when {
        session != null -> session.name to stringResource(if (session.isLeader) R.string.home_hero_group_body_leader else R.string.home_hero_group_body, session.destination.name)
        state.primaryAction == HomeAction.PAIR_PHONE -> stringResource(R.string.home_hero_pair_title) to stringResource(R.string.home_buttons_hint)
        else -> stringResource(R.string.home_hero_start_title) to stringResource(R.string.home_hero_start_body)
    }
    Text(title, style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 2)
    Spacer(Modifier.height(10.dp))
    Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(18.dp))
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
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(DhIcons.phone, contentDescription = null, tint = if (good) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            "${stringResource(R.string.home_vehicle_connection)}: $connectionText",
            style = MaterialTheme.typography.titleMedium,
            color = if (good) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface,
        )
    }
    if (state.connection == VehicleConnection.CONNECTED && state.vehicleIdMasked != null) {
        Text(
            "${stringResource(R.string.home_vehicle_id)} ${state.vehicleIdMasked}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 38.dp, top = 2.dp),
        )
    }
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
        label = { Text("$vehicle · $realtime", style = MaterialTheme.typography.bodyMedium) },
        trailingIcon = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.home_chip_desc), modifier = Modifier.padding(end = 4.dp)) },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.surface,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            trailingIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        border = AssistChipDefaults.assistChipBorder(enabled = true, borderColor = MaterialTheme.colorScheme.outlineVariant),
        shape = MaterialTheme.shapes.small,
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
        shape = MaterialTheme.shapes.large,
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
    val icon = when (state.primaryAction) {
        HomeAction.PAIR_PHONE -> DhIcons.qr
        HomeAction.START_GROUP -> DhIcons.arrowUp
        HomeAction.OPEN_GROUP -> DhIcons.group
    }
    DhPrimaryButton(label, onPrimary, Modifier.fillMaxWidth(), enabled = state.primaryEnabled, icon = icon)
    if (state.isDriving) {
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.home_driving_hint), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.tertiary)
    }
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
        DhSecondaryButton(stringResource(R.string.home_btn_settings), onOpenSettings, Modifier.weight(1f), icon = DhIcons.settings)
        // 폰(공식 앱)이 없어도 그룹 주행 화면을 예시 데이터로 본다(2-1) — 그룹이 없을 때만, 정차 중에만
        if (state.groupSession == null) {
            DhSecondaryButton(stringResource(R.string.home_btn_tour), onOpenTour, Modifier.weight(1f), enabled = !state.isDriving, icon = DhIcons.navigation)
        }
    }
    if (state.groupSession == null && state.primaryAction == HomeAction.PAIR_PHONE) {
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.home_tour_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * '동행은 이렇게 써요' 3단계 — 실제 순서가 있는 과정이라 번호를 붙이고, 지금 할 단계(연결 전 1 · 그룹 전 2 · 그룹 중 3)를 표지 초록으로 강조한다.
 * 그룹 중이면 3단계 아래에 핸들 버튼 무전 안내를 더한다.
 */
@Composable
private fun StepsCard(state: HomeUiState) {
    val current = when {
        state.groupSession != null -> 3
        state.primaryAction == HomeAction.PAIR_PHONE -> 1
        else -> 2
    }
    DhCard(Modifier.fillMaxWidth().widthIn(max = 960.dp)) {
        Text(stringResource(R.string.home_howto_title), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        Step(1, DhIcons.phone, R.string.home_howto_1_title, R.string.home_howto_1_body, current)
        Step(2, DhIcons.qr, R.string.home_howto_2_title, R.string.home_howto_2_body, current)
        Step(3, DhIcons.navigation, R.string.home_howto_3_title, R.string.home_howto_3_body, current)
        if (current == 3) {
            Text(stringResource(R.string.dash_ptt_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(start = 76.dp))
        }
    }
}

@Composable
private fun Step(n: Int, icon: ImageVector, titleRes: Int, bodyRes: Int, current: Int) {
    val active = n == current
    val done = n < current
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(if (active) DhPalette.signGreen else MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = if (active) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(titleRes),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = when {
                    active -> MaterialTheme.colorScheme.primary
                    done -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
            Text(stringResource(bodyRes), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * '같이 가기 좋은 곳' — 추천 목적지 사진 줄(그룹이 없을 때). 누르면 그 목적지를 골라 둔 그룹 만들기로(연결 전이면 폰 연결로).
 * 주행 중에는 누를 수 없다(규칙 6).
 */
@Composable
private fun DestinationStrip(state: HomeUiState, columns: Int, onPick: (String) -> Unit) {
    Text(stringResource(R.string.home_places_title), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
    Text(
        stringResource(if (state.primaryAction == HomeAction.PAIR_PHONE) R.string.home_places_hint_pair else R.string.home_places_hint),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(14.dp))
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        DESTINATION_PRESETS.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { place ->
                    val art = DestinationArt.forName(place.name) ?: return@forEach
                    DestinationPhotoCard(
                        name = place.name,
                        image = art,
                        selected = false,
                        onClick = { onPick(place.name) },
                        enabled = !state.isDriving,
                        subtitle = place.address?.split(' ')?.take(2)?.joinToString(" "),
                        modifier = Modifier.weight(1f).aspectRatio(1.45f),
                    )
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** 내 차 표시 색(도로 띠) */
private val MeBlue = Color(0xFF5AA9FF)

/** 개발용 빌드 정보 — debug 빌드 홈에만(release 는 진단 화면의 빌드 카드에서 본다, E040) */
@Composable
private fun BuildInfoCard(state: HomeUiState) {
    DhCard(padding = 20.dp) {
        InfoRow(stringResource(R.string.home_label_flavor), state.flavor)
        InfoRow(stringResource(R.string.home_label_version), state.versionName)
        InfoRow(stringResource(R.string.home_label_base_url), if (state.baseUrlOverridden) stringResource(R.string.t32_address_override, state.publicBaseUrl) else state.publicBaseUrl)
        InfoRow(stringResource(R.string.home_label_mqtt_uri), mqttAddressText(state.mqttAddress))
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
