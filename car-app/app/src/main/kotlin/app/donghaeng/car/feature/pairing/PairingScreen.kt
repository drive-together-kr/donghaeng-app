package app.donghaeng.car.feature.pairing

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.LocalTextStyle
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.graphics.Color
import app.donghaeng.car.ui.components.DhIcons
import app.donghaeng.car.ui.components.DhPrimaryButton
import app.donghaeng.car.ui.components.DhSecondaryButton
import app.donghaeng.car.ui.theme.DhPalette
import app.donghaeng.car.ui.theme.TABULAR_NUMS
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.donghaeng.car.BuildConfig
import app.donghaeng.car.R
import app.donghaeng.car.core.common.TextMasking
import app.donghaeng.car.ui.components.DestructiveConfirmDialog
import app.donghaeng.car.ui.theme.DongHaengTheme
import kotlinx.coroutines.delay

/** 페어링 화면 진입점. 새로 연결되면 [onPaired] (홈으로 자동 복귀). */
@Composable
fun PairingRoute(
    onBack: () -> Unit,
    onPaired: () -> Unit,
    onStartGroup: () -> Unit = onBack,
    onOpenGroup: () -> Unit = onBack,
    onOpenTour: () -> Unit = {},
    viewModel: PairingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val isDriving by viewModel.isDriving.collectAsStateWithLifecycle()
    val hasGroup by viewModel.hasGroup.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            if (event is PairingEvent.Paired) onPaired()
        }
    }
    PairingScreen(
        state = state,
        onBack = onBack,
        onRetry = viewModel::retry,
        onRepair = viewModel::disconnectAndRepair,
        isDriving = isDriving,
        hasGroup = hasGroup,
        onPrimaryConnected = if (hasGroup) onOpenGroup else onStartGroup,
        onOpenTour = onOpenTour,
    )
}

/**
 * 페어링 화면 (T12). 서버 `POST /v1/device/code` 응답의 verifyUri 를 QR 로, userCode 를 `KQ7-4MZ` 로 크게 보여 주고
 * interval 마다 `/device/token` 을 폴링한다. QR 문자열은 서버 응답 그대로(docs/05 §7).
 * 카메라가 없으면 공식 앱(demo-host) 스캔 화면의 '코드·주소 직접 입력'에 이 코드(KQ7-4MZ)만 넣어도 되고, 아래 작은 글자의 주소(verifyUri)를 넣어도 된다(D039).
 * 연결됨 화면의 주 버튼은 다음 할 일 — 그룹이 있으면 [그룹 보기], 없으면 [같이 가기 시작](1-1).
 * T33: '연결 해제 후 다시 연결' 은 확인 창 뒤로, 주행 중에는 비활성(docs/10 R13). 시도 횟수·예외 원문은 debug 빌드에서만(1-5).
 */
@Composable
fun PairingScreen(
    state: PairingState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onRepair: () -> Unit,
    isDriving: Boolean = false,
    hasGroup: Boolean = false,
    onPrimaryConnected: () -> Unit = onBack,
    onOpenTour: () -> Unit = {},
) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 48.dp, vertical = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onBack, modifier = Modifier.heightIn(min = 76.dp), shape = MaterialTheme.shapes.medium) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.pairing_back), color = MaterialTheme.colorScheme.onSurface)
                }
                Spacer(Modifier.width(24.dp))
                Text(stringResource(R.string.pairing_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(20.dp))
            when (state) {
                PairingState.Idle, PairingState.Checking -> ProgressSection(stringResource(R.string.pairing_checking))
                PairingState.RequestingCode -> ProgressSection(stringResource(R.string.pairing_requesting))
                is PairingState.CodeIssued -> CodeIssuedSection(state)
                is PairingState.Connected -> ConnectedSection(state, onPrimary = onPrimaryConnected, primaryIsGroup = hasGroup, onRepair = onRepair, isDriving = isDriving)
                is PairingState.Error -> ErrorSection(state, onRetry = onRetry, onBack = onBack)
            }
            // 폰(공식 앱)이 없으면 여기서 막힌다 — 예시 데이터로 그룹 주행 화면을 둘러본다(2-1). 연결됨 화면에서는 필요 없다
            if (state !is PairingState.Connected) {
                Spacer(Modifier.height(28.dp))
                DhSecondaryButton(stringResource(R.string.home_btn_tour), onOpenTour, enabled = !isDriving, icon = DhIcons.navigation)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.home_tour_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** QR 은 흰 판 위에 — 어두운 화면에서도 카메라가 바로 읽게 넉넉한 흰 여백 */
@Composable
private fun QrTile(content: String, size: androidx.compose.ui.unit.Dp) {
    Surface(color = Color.White, shape = RoundedCornerShape(24.dp)) {
        QrCodeImage(content = content, modifier = Modifier.padding(18.dp).size(size))
    }
}

@Composable
private fun ProgressSection(message: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 40.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(40.dp))
        Spacer(Modifier.width(20.dp))
        Text(message, style = MaterialTheme.typography.titleLarge)
    }
}

/** 넓은 화면: 좌 QR / 우 코드·안내. 좁은 화면: 세로로 쌓는다. */
@Composable
private fun CodeIssuedSection(state: PairingState.CodeIssued) {
    // 남은 시간은 1초마다 다시 계산한다 (만료 판정 자체는 ViewModel 이 한다)
    var now by remember(state.expiresAtMillis) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.expiresAtMillis) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    val remaining = PairingFormat.formatRemaining(state.expiresAtMillis - now)

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val wide = maxWidth >= 840.dp
        val qrSize = if (wide) 420.dp else 320.dp
        if (wide) {
            Row(horizontalArrangement = Arrangement.spacedBy(48.dp), verticalAlignment = Alignment.CenterVertically) {
                QrTile(state.verifyUri, qrSize)
                Column(modifier = Modifier.weight(1f)) { CodeDetails(state, remaining) }
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                QrTile(state.verifyUri, qrSize)
                Spacer(Modifier.height(24.dp))
                CodeDetails(state, remaining)
            }
        }
    }
}

@Composable
private fun CodeDetails(state: PairingState.CodeIssued, remaining: String) {
    Text(
        stringResource(R.string.pairing_scan_hint),
        style = MaterialTheme.typography.titleLarge,
    )
    Spacer(Modifier.height(16.dp))
    Text(stringResource(R.string.pairing_or_code), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    // 코드 판 — 표지 초록 바탕의 큰 글자(폰 '코드·주소 직접 입력'에 그대로 옮겨 적는다). 고정폭 숫자로 글자 폭이 흔들리지 않게
    Surface(color = DhPalette.signGreen, shape = MaterialTheme.shapes.medium, modifier = Modifier.padding(vertical = 8.dp)) {
        Text(
            PairingFormat.formatUserCode(state.userCode),
            fontSize = 76.sp,
            fontWeight = FontWeight.ExtraBold,
            style = LocalTextStyle.current.copy(fontFeatureSettings = TABULAR_NUMS),
            letterSpacing = 8.sp,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 6.dp),
        )
    }
    // QR 내용(서버 verifyUri) — 카메라 없는 폰에서 '코드·주소 직접 입력'에 넣을 수 있게 작은 글자로(D039, 그룹 초대 화면의 초대 주소와 같은 방식)
    Text(stringResource(R.string.pairing_verify_uri, state.verifyUri), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(12.dp))
    Text(stringResource(R.string.pairing_remaining, remaining), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.pairing_auto_hint), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (BuildConfig.DH_SHOW_DEV_INFO) {
        Text(
            stringResource(R.string.pairing_attempt, state.attempt, PairingViewModel.MAX_CODE_REQUESTS, state.intervalSec),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ConnectedSection(state: PairingState.Connected, onPrimary: () -> Unit, primaryIsGroup: Boolean, onRepair: () -> Unit, isDriving: Boolean) {
    var confirmRepair by rememberSaveable { mutableStateOf(false) }
    if (confirmRepair) {
        DestructiveConfirmDialog(
            title = stringResource(R.string.t33_disconnect_confirm_title),
            message = stringResource(R.string.t33_disconnect_confirm_message),
            confirmText = stringResource(R.string.t33_repair_confirm_ok),
            isDriving = isDriving,
            onConfirm = { confirmRepair = false; onRepair() },
            onDismiss = { confirmRepair = false },
        )
    }
    Text(
        stringResource(R.string.pairing_connected, state.nickname),
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.secondary,
    )
    Text(
        "${stringResource(R.string.home_vehicle_id)} ${TextMasking.maskVehicleId(state.vehicleId)}",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.pairing_connected_hint), style = MaterialTheme.typography.bodyLarge)
    Spacer(Modifier.height(24.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        // 다음 할 일이 주 버튼 — 그룹 보기는 주행 중에도, 같이 가기 시작은 정차 중에만(규칙 6)
        DhPrimaryButton(
            stringResource(if (primaryIsGroup) R.string.home_btn_open_group else R.string.home_btn_start_group),
            onPrimary,
            Modifier.widthIn(min = 420.dp),
            enabled = primaryIsGroup || !isDriving,
            icon = if (primaryIsGroup) DhIcons.group else DhIcons.arrowUp,
        )
        DhSecondaryButton(stringResource(R.string.pairing_btn_repair), { confirmRepair = true }, enabled = !isDriving)
    }
    if (isDriving) {
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.t33_driving_disabled), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
    }
}

@Composable
private fun ErrorSection(state: PairingState.Error, onRetry: () -> Unit, onBack: () -> Unit) {
    val message = when (state.reason) {
        PairingErrorReason.NETWORK -> stringResource(R.string.pairing_error_network)
        PairingErrorReason.SERVER -> stringResource(R.string.pairing_error_server)
        PairingErrorReason.VEHICLE_ID_UNAVAILABLE -> stringResource(R.string.pairing_error_vehicle_id)
        PairingErrorReason.TOO_MANY_CODES -> stringResource(R.string.pairing_error_too_many)
        PairingErrorReason.STORAGE_UNAVAILABLE -> stringResource(R.string.t33_pairing_error_storage)
    }
    Text(message, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.error)
    // 예외 원문("network ConnectException")은 개발용 — release 에는 사유 문구만(1-5). 원문은 로그·진단에 남는다
    if (BuildConfig.DH_SHOW_DEV_INFO && state.detail != null) {
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.pairing_error_detail, state.detail),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(24.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        if (state.retryable) {
            Button(onClick = onRetry, modifier = Modifier.heightIn(min = 76.dp)) { Text(stringResource(R.string.pairing_btn_retry)) }
        }
        OutlinedButton(onClick = onBack, modifier = Modifier.heightIn(min = 76.dp)) { Text(stringResource(R.string.pairing_back)) }
    }
}

@Preview(name = "코드 발급 가로", widthDp = 1280, heightDp = 720)
@Composable
private fun PairingCodeIssuedPreview() {
    DongHaengTheme {
        PairingScreen(
            state = PairingState.CodeIssued(
                userCode = "KQ74MZ",
                verifyUri = "http://10.0.2.2:8080/pair?c=KQ74MZ",
                expiresAtMillis = System.currentTimeMillis() + 598_000L,
                intervalSec = 3,
                attempt = 1,
            ),
            onBack = {}, onRetry = {}, onRepair = {},
        )
    }
}

@Preview(name = "오류 세로", widthDp = 480, heightDp = 800)
@Composable
private fun PairingErrorPreview() {
    DongHaengTheme {
        Box { PairingScreen(state = PairingState.Error(PairingErrorReason.NETWORK, retryable = true, detail = "network ConnectException"), onBack = {}, onRetry = {}, onRepair = {}) }
    }
}
