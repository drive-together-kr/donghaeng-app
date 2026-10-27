package app.donghaeng.companion.demo.donghaeng

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.donghaeng.companion.demo.BuildConfig
import app.donghaeng.companion.demo.DemoQrKind
import app.donghaeng.companion.demo.QrPathRule
import app.donghaeng.companion.demo.R
import app.donghaeng.companion.demo.ui.components.AccentButton
import app.donghaeng.companion.demo.ui.components.Disclaimer
import app.donghaeng.companion.demo.ui.components.KiaButton
import app.donghaeng.companion.demo.ui.components.KiaChip
import app.donghaeng.companion.demo.ui.components.KiaTopBar
import app.donghaeng.companion.demo.ui.components.MsIcon
import app.donghaeng.companion.demo.ui.components.demoToast
import app.donghaeng.companion.demo.ui.theme.KiaColors
import app.donghaeng.companion.sdk.DongHaengException
import kotlinx.coroutines.launch

/**
 * 삽입 지점 A — 상단 QR 스캔 아이콘이 여는 공식 앱의 스캐너 자리.
 * 동행 QR(`/j/{token}`)이면 [JoinGroupSheet], 차량 등록 QR(`/pair?c=`)이면 등록 대화상자, 그 밖의 QR 은 기존 동작 흉내(토스트).
 * 카메라 없는 에뮬레이터용 "QR 문자열 붙여넣기"는 디버그 메뉴로 아래에 남겨 둔다.
 */
@Composable
fun ScanScreen(
    onBack: () -> Unit,
    onJoined: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var hasCamera by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var permissionAsked by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCamera = granted
        permissionAsked = true
    }
    LaunchedEffect(Unit) {
        if (!hasCamera && !permissionAsked) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    var joinPayload by rememberSaveable { mutableStateOf<String?>(null) }
    var pairPayload by rememberSaveable { mutableStateOf<String?>(null) }
    var debugOpen by rememberSaveable { mutableStateOf(false) }
    var pasted by rememberSaveable { mutableStateOf("") }
    var pairNickname by rememberSaveable { mutableStateOf("") }
    var pairBusy by remember { mutableStateOf(false) }
    var pairError by remember { mutableStateOf<String?>(null) }
    val base = BuildConfig.DH_PUBLIC_BASE_URL

    fun onQr(payload: String) {
        if (joinPayload != null || pairPayload != null) return
        val trimmed = payload.trim()
        if (trimmed.isEmpty()) return
        when (QrPathRule.classify(trimmed, base)) {
            DemoQrKind.JOIN -> joinPayload = trimmed
            DemoQrKind.PAIR -> pairPayload = trimmed
            DemoQrKind.UNKNOWN -> demoToast(context, "동행 QR 이 아니에요")
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(KiaColors.Bg),
    ) {
        KiaTopBar(title = "QR 스캔", onBack = onBack, onSettings = { debugOpen = !debugOpen })
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(420.dp)
                    .padding(horizontal = 20.dp)
                    .background(Color.Black, RoundedCornerShape(24.dp)),
            ) {
                if (hasCamera) {
                    CameraPreview(onQr = ::onQr, modifier = Modifier.fillMaxSize())
                } else {
                    Column(
                        Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        MsIcon(R.drawable.ms_photo_camera, null, tint = KiaColors.TextSecondary, size = 40.dp)
                        Text("QR 을 찍으려면 카메라 권한이 필요해요", color = KiaColors.TextSecondary)
                        KiaButton("카메라 권한 허용", modifier = Modifier.padding(horizontal = 60.dp)) { permissionLauncher.launch(Manifest.permission.CAMERA) }
                    }
                }
                // 스캔 프레임(라임 모서리)
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .size(220.dp)
                        .border(2.dp, KiaColors.Accent.copy(alpha = 0.9f), RoundedCornerShape(20.dp)),
                )
                Text(
                    "차량 화면의 동행 QR 또는 차량 등록 QR 을 비추세요",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(14.dp),
                )
            }

            Spacer(Modifier.height(20.dp))
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text("이 QR 로 할 수 있는 것", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text("• 동행 그룹 합류 — 선두차 화면의 그룹 QR", style = MaterialTheme.typography.bodyMedium, color = KiaColors.TextSecondary)
                Text("• 차량 등록 — 차량 화면의 연결 QR(계정에 차가 없을 때)", style = MaterialTheme.typography.bodyMedium, color = KiaColors.TextSecondary)
                Text("• 그 밖의 QR — 공식 앱의 기존 기능(시연용 화면)", style = MaterialTheme.typography.bodyMedium, color = KiaColors.TextSecondary)
            }

            if (debugOpen) {
                Spacer(Modifier.height(20.dp))
                Column(
                    Modifier
                        .padding(horizontal = 20.dp)
                        .fillMaxWidth()
                        .background(KiaColors.Card, RoundedCornerShape(24.dp))
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("디버그 — QR 문자열 붙여넣기", style = MaterialTheme.typography.titleMedium)
                    Text("카메라 없는 에뮬레이터용. 차 화면 QR 의 문자열(초대 URL / verifyUri)을 그대로 넣으세요.", style = MaterialTheme.typography.bodySmall, color = KiaColors.TextSecondary)
                    OutlinedTextField(
                        value = pasted,
                        onValueChange = { pasted = it },
                        placeholder = { Text("$base/j/inv_…", style = MaterialTheme.typography.bodySmall) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = KiaColors.Accent, unfocusedBorderColor = KiaColors.SubCard, cursorColor = KiaColors.Accent),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        KiaChip("그룹 QR 예시", onClick = { pasted = "$base/j/inv_demo0001" })
                        KiaChip("차량 등록 QR 예시", onClick = { pasted = "$base/pair?c=KQ74MZ" })
                    }
                    AccentButton("적용", enabled = pasted.isNotBlank()) { onQr(pasted) }
                }
            }
            Spacer(Modifier.height(16.dp))
            Disclaimer()
        }
    }

    joinPayload?.let { payload ->
        JoinGroupSheet(
            qrPayload = payload,
            onDismiss = { joinPayload = null },
            onJoined = {
                joinPayload = null
                demoToast(context, "'${it.name}' 그룹에 합류했어요")
                onJoined()
            },
        )
    }

    pairPayload?.let { payload ->
        AlertDialog(
            onDismissRequest = { if (!pairBusy) pairPayload = null },
            containerColor = KiaColors.Card,
            titleContentColor = KiaColors.TextPrimary,
            textContentColor = KiaColors.TextSecondary,
            title = { Text("차량 등록") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("차량 화면의 QR 로 이 차를 내 계정에 등록합니다. 별명을 정해 주세요.")
                    OutlinedTextField(
                        value = pairNickname,
                        onValueChange = { pairNickname = it },
                        label = { Text("차 별명") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = KiaColors.Accent, unfocusedBorderColor = KiaColors.SubCard, cursorColor = KiaColors.Accent),
                    )
                    pairError?.let { Text(it, color = KiaColors.Dot, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                TextButton(enabled = !pairBusy && pairNickname.isNotBlank(), onClick = {
                    pairBusy = true
                    pairError = null
                    scope.launch {
                        try {
                            val result = DongHaengStore.pairVehicle(payload, pairNickname.trim())
                            DongHaengStore.refreshVehicles()
                            pairPayload = null
                            demoToast(context, "'${result.nickname}' 이(가) 내 차에 추가됐어요")
                        } catch (e: DongHaengException) {
                            pairError = e.toUserText()
                        } finally {
                            pairBusy = false
                        }
                    }
                }) { Text(if (pairBusy) "등록 중…" else "등록", color = KiaColors.Accent) }
            },
            dismissButton = { TextButton(enabled = !pairBusy, onClick = { pairPayload = null }) { Text("취소", color = KiaColors.TextSecondary) } },
        )
    }
}
