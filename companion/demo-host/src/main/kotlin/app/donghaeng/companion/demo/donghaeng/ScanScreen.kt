package app.donghaeng.companion.demo.donghaeng

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
 * 카메라로 찍을 수 없을 때(에뮬레이터·권한 거부)를 위해 **"코드·주소 직접 입력"** 카드를 본문에 늘 보인다(D027·D039):
 * 차 화면의 코드(KQ7-4MZ)·초대 토큰·QR 문자열(초대 URL / verifyUri) 무엇이든 넣으면 같은 흐름을 탄다([ManualQrInput]).
 * 같은 QR 이 프레임마다 다시 잡혀 닫은 시트가 다시 뜨거나 토스트가 쌓이지 않게 [QrScanGate] 로 거른다(D081).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScanScreen(
    onBack: () -> Unit,
    onJoined: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mode by DongHaengStore.mode.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val gate = remember { QrScanGate() }

    var hasCamera by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var permissionAsked by rememberSaveable { mutableStateOf(false) }
    /** 권한은 있는데 카메라를 열지 못함(카메라 없는 PC 의 에뮬레이터 등) — 안내와 [사진에서 QR 읽기]로 바꾼다 */
    var cameraUnavailable by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCamera = granted
        permissionAsked = true
    }
    LaunchedEffect(Unit) {
        if (!hasCamera && !permissionAsked) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    var joinPayload by rememberSaveable { mutableStateOf<String?>(null) }
    var pairPayload by rememberSaveable { mutableStateOf<String?>(null) }
    var manual by rememberSaveable { mutableStateOf("") }
    var manualError by remember { mutableStateOf<String?>(null) }
    var pairNickname by rememberSaveable { mutableStateOf("") }
    var pairBusy by remember { mutableStateOf(false) }
    var pairError by remember { mutableStateOf<String?>(null) }
    val base = BuildConfig.DH_PUBLIC_BASE_URL

    /** QR 문자열 처리. [fromCamera] 면 같은 값 반복을 거른다(직접 입력은 사용자의 명시적 동작이라 거르지 않는다) */
    fun handle(payload: String, fromCamera: Boolean) {
        if (joinPayload != null || pairPayload != null) return
        val trimmed = payload.trim()
        if (trimmed.isEmpty()) return
        if (fromCamera && !gate.accept(trimmed)) return
        when (QrPathRule.classify(trimmed, base)) {
            DemoQrKind.JOIN -> joinPayload = trimmed
            DemoQrKind.PAIR -> {
                pairError = null
                pairPayload = trimmed
            }
            DemoQrKind.UNKNOWN -> demoToast(context, "동행 QR 이 아니에요")
        }
    }

    fun onQr(payload: String) = handle(payload, fromCamera = true)

    // 카메라 없이: 사진(스크린샷)에서 QR 읽기
    var photoBusy by remember { mutableStateOf(false) }
    val photoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        photoBusy = true
        scope.launch {
            val payload = PhotoQr.decode(context, uri)
            photoBusy = false
            if (payload == null) demoToast(context, "사진에서 QR 을 찾지 못했어요") else handle(payload, fromCamera = false)
        }
    }
    fun pickPhoto() = photoLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))

    // 링크(메신저로 받은 초대·등록 주소)로 열렸으면 그 주소를 바로 처리
    LaunchedEffect(Unit) { IncomingLink.take()?.let { handle(it, fromCamera = false) } }

    fun applyManual() {
        val payload = ManualQrInput.toQrPayload(manual, base)
        if (payload == null) {
            manualError = "차 화면의 코드 6자(예: KQ7-4MZ)나 QR 주소를 넣어 주세요"
            return
        }
        manualError = null
        handle(payload, fromCamera = false)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(KiaColors.Bg),
    ) {
        KiaTopBar(title = "QR 스캔", onBack = onBack)
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
                if (hasCamera && cameraUnavailable) {
                    Column(
                        Modifier.align(Alignment.Center).padding(horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        MsIcon(R.drawable.ms_photo_camera, null, tint = KiaColors.TextSecondary, size = 40.dp)
                        Text("이 기기에서는 카메라를 쓸 수 없어요", color = Color.White, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "차 화면 QR 을 찍은 사진이 있으면 사진에서 읽고, 없으면 아래에 차 화면의 코드·주소를 넣으세요.",
                            color = KiaColors.TextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                        KiaButton(if (photoBusy) "사진 읽는 중…" else "사진에서 QR 읽기", modifier = Modifier.padding(horizontal = 40.dp)) { if (!photoBusy) pickPhoto() }
                    }
                } else if (hasCamera) {
                    CameraPreview(onQr = ::onQr, modifier = Modifier.fillMaxSize(), onUnavailable = { cameraUnavailable = true })
                } else {
                    Column(
                        Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        MsIcon(R.drawable.ms_photo_camera, null, tint = KiaColors.TextSecondary, size = 40.dp)
                        Text("QR 을 찍으려면 카메라 권한이 필요해요", color = KiaColors.TextSecondary)
                        KiaButton("카메라 권한 허용", modifier = Modifier.padding(horizontal = 60.dp)) { permissionLauncher.launch(Manifest.permission.CAMERA) }
                        Text("카메라 없이: 아래 '코드·주소 직접 입력'", style = MaterialTheme.typography.bodySmall, color = KiaColors.TextSecondary)
                    }
                }
                // 스캔 프레임(라임 모서리) — 카메라를 쓸 수 있을 때만
                if (!(hasCamera && cameraUnavailable)) Box(
                    Modifier
                        .align(Alignment.Center)
                        .size(220.dp)
                        .border(2.dp, KiaColors.Accent.copy(alpha = 0.9f), RoundedCornerShape(20.dp)),
                )
                if (!(hasCamera && cameraUnavailable)) Text(
                    "차량 화면의 동행 QR 또는 차량 등록 QR 을 비추세요",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(14.dp),
                )
            }

            // 카메라로 찍을 수 없을 때 — 차 화면의 코드(또는 QR 문자열)를 직접 넣는다(D027·D039). 늘 보인다
            Spacer(Modifier.height(16.dp))
            Column(
                Modifier
                    .padding(horizontal = 20.dp)
                    .fillMaxWidth()
                    .background(KiaColors.Card, RoundedCornerShape(24.dp))
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("카메라로 찍을 수 없나요?", style = MaterialTheme.typography.titleMedium)
                AccentButton(if (photoBusy) "사진 읽는 중…" else "사진에서 QR 읽기", enabled = !photoBusy) { pickPhoto() }
                Text(
                    "차 화면의 QR 을 찍은 사진이나 받은 QR 이미지를 고르면 같은 흐름으로 이어져요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = KiaColors.TextSecondary,
                )
                Spacer(Modifier.height(6.dp))
                Text("또는 코드·주소 직접 입력", style = MaterialTheme.typography.titleMedium)
                Text(
                    "차량 등록: 차 화면 QR 아래의 코드 6자(예: KQ7-4MZ). 그룹 합류: 선두차 화면 QR 아래의 초대 주소. QR 문자열(주소)을 붙여넣어도 돼요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = KiaColors.TextSecondary,
                )
                OutlinedTextField(
                    value = manual,
                    onValueChange = {
                        manual = it
                        manualError = null
                    },
                    placeholder = { Text("KQ7-4MZ 또는 $base/j/inv_…", style = MaterialTheme.typography.bodySmall) },
                    singleLine = true,
                    isError = manualError != null,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = KiaColors.Accent, unfocusedBorderColor = KiaColors.SubCard, cursorColor = KiaColors.Accent),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                manualError?.let { Text(it, color = KiaColors.Dot, style = MaterialTheme.typography.bodySmall) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    KiaChip("클립보드 붙여넣기", onClick = {
                        val text = clipboard.getText()?.text.orEmpty().trim()
                        if (text.isEmpty()) demoToast(context, "클립보드가 비어 있어요") else {
                            manual = text
                            manualError = null
                        }
                    })
                    // 예시는 시연 모드에서만 — 실서버에서는 만료·없는 코드라 실패만 보인다(D027)
                    if (mode == DhMode.FAKE) {
                        KiaChip("그룹 QR 예시", onClick = { manual = "$base/j/inv_demo0001" })
                        KiaChip("등록 코드 예시", onClick = { manual = "KQ7-4MZ" })
                    }
                }
                AccentButton("적용", enabled = manual.isNotBlank()) { applyManual() }
            }

            Spacer(Modifier.height(20.dp))
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text("이 QR 로 할 수 있는 것", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text("• 동행 그룹 합류 — 선두차 화면의 그룹 QR", style = MaterialTheme.typography.bodyMedium, color = KiaColors.TextSecondary)
                Text("• 차량 등록 — 차량 화면의 연결 QR 또는 코드(계정에 차가 없을 때)", style = MaterialTheme.typography.bodyMedium, color = KiaColors.TextSecondary)
                Text("• 그 밖의 QR — 공식 앱의 기존 기능(시연용 화면)", style = MaterialTheme.typography.bodyMedium, color = KiaColors.TextSecondary)
            }
            Spacer(Modifier.height(16.dp))
            Disclaimer()
        }
    }

    joinPayload?.let { payload ->
        JoinGroupSheet(
            qrPayload = payload,
            onDismiss = {
                gate.markHandled(payload) // 닫은 뒤 같은 QR 이 아직 화면에 있어도 바로 다시 뜨지 않게
                joinPayload = null
            },
            onJoined = {
                gate.markHandled(payload)
                joinPayload = null
                demoToast(context, "'${it.name}' 그룹에 합류했어요")
                onJoined()
            },
        )
    }

    pairPayload?.let { payload ->
        fun closePair() {
            gate.markHandled(payload)
            pairPayload = null
            pairError = null
        }
        AlertDialog(
            onDismissRequest = { if (!pairBusy) closePair() },
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
                            closePair()
                            pairNickname = ""
                            manual = ""
                            demoToast(context, "'${result.nickname}' 이(가) 내 차에 추가됐어요")
                        } catch (e: DongHaengException) {
                            pairError = e.toUserText()
                        } finally {
                            pairBusy = false
                        }
                    }
                }) { Text(if (pairBusy) "등록 중…" else "등록", color = KiaColors.Accent) }
            },
            dismissButton = { TextButton(enabled = !pairBusy, onClick = { closePair() }) { Text("취소", color = KiaColors.TextSecondary) } },
        )
    }
}
