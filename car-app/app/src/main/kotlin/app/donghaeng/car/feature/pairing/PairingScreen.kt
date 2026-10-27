package app.donghaeng.car.feature.pairing

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
import androidx.compose.runtime.remember
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
import app.donghaeng.car.R
import app.donghaeng.car.core.common.TextMasking
import app.donghaeng.car.ui.theme.DongHaengTheme
import kotlinx.coroutines.delay

/** 페어링 화면 진입점. 새로 연결되면 [onPaired] (홈으로 자동 복귀). */
@Composable
fun PairingRoute(
    onBack: () -> Unit,
    onPaired: () -> Unit,
    viewModel: PairingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
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
    )
}

/**
 * 페어링 화면 (T12). 서버 `POST /v1/device/code` 응답의 verifyUri 를 QR 로, userCode 를 `KQ7-4MZ` 로 크게 보여 주고
 * interval 마다 `/device/token` 을 폴링한다. QR 문자열은 서버 응답 그대로(docs/05 §7).
 * 주행 중 입력 제한(T29)은 아직 없다 — 버튼은 그대로 둔다.
 */
@Composable
fun PairingScreen(
    state: PairingState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onRepair: () -> Unit,
) {
    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp, vertical = 20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onBack, modifier = Modifier.heightIn(min = 56.dp)) {
                    Text(stringResource(R.string.pairing_back))
                }
                Spacer(Modifier.width(20.dp))
                Text(stringResource(R.string.pairing_title), style = MaterialTheme.typography.headlineMedium)
            }
            Spacer(Modifier.height(20.dp))
            when (state) {
                PairingState.Idle, PairingState.Checking -> ProgressSection(stringResource(R.string.pairing_checking))
                PairingState.RequestingCode -> ProgressSection(stringResource(R.string.pairing_requesting))
                is PairingState.CodeIssued -> CodeIssuedSection(state)
                is PairingState.Connected -> ConnectedSection(state, onBack = onBack, onRepair = onRepair)
                is PairingState.Error -> ErrorSection(state, onRetry = onRetry, onBack = onBack)
            }
        }
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
            Row(horizontalArrangement = Arrangement.spacedBy(40.dp), verticalAlignment = Alignment.CenterVertically) {
                QrCodeImage(content = state.verifyUri, modifier = Modifier.size(qrSize))
                Column(modifier = Modifier.weight(1f)) { CodeDetails(state, remaining) }
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                QrCodeImage(content = state.verifyUri, modifier = Modifier.size(qrSize))
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
    Text(
        PairingFormat.formatUserCode(state.userCode),
        fontSize = 72.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
        letterSpacing = 6.sp,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(12.dp))
    Text(stringResource(R.string.pairing_remaining, remaining), style = MaterialTheme.typography.titleMedium)
    Text(
        stringResource(R.string.pairing_attempt, state.attempt, PairingViewModel.MAX_CODE_REQUESTS, state.intervalSec),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ConnectedSection(state: PairingState.Connected, onBack: () -> Unit, onRepair: () -> Unit) {
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
        Button(onClick = onBack, modifier = Modifier.heightIn(min = 64.dp)) { Text(stringResource(R.string.pairing_back)) }
        OutlinedButton(onClick = onRepair, modifier = Modifier.heightIn(min = 64.dp)) { Text(stringResource(R.string.pairing_btn_repair)) }
    }
}

@Composable
private fun ErrorSection(state: PairingState.Error, onRetry: () -> Unit, onBack: () -> Unit) {
    val message = when (state.reason) {
        PairingErrorReason.NETWORK -> stringResource(R.string.pairing_error_network)
        PairingErrorReason.SERVER -> stringResource(R.string.pairing_error_server)
        PairingErrorReason.VEHICLE_ID_UNAVAILABLE -> stringResource(R.string.pairing_error_vehicle_id)
        PairingErrorReason.TOO_MANY_CODES -> stringResource(R.string.pairing_error_too_many)
    }
    Text(message, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.error)
    if (state.detail != null) {
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
            Button(onClick = onRetry, modifier = Modifier.heightIn(min = 64.dp)) { Text(stringResource(R.string.pairing_btn_retry)) }
        }
        OutlinedButton(onClick = onBack, modifier = Modifier.heightIn(min = 64.dp)) { Text(stringResource(R.string.pairing_back)) }
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
