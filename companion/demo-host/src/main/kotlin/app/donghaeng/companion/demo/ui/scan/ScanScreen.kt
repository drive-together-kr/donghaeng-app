package app.donghaeng.companion.demo.ui.scan

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.donghaeng.companion.demo.BuildConfig
import app.donghaeng.companion.sdk.GroupJoinResult

/**
 * (3) 스캔 — 공식 앱의 QR 스캐너 자리. 카메라(CameraX+ML Kit)와 "테스트 QR 붙여넣기"(카메라 없는 에뮬레이터용) 둘 다
 * 같은 [ScanViewModel.onQrDetected] 로 들어가고, 경로에 따라 "그룹 합류 / 차량 등록" 확인 후 동행 모듈을 부른다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(
    onBack: () -> Unit,
    onJoined: (GroupJoinResult) -> Unit,
    viewModel: ScanViewModel = viewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()

    var hasCamera by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var permissionAsked by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCamera = granted
        permissionAsked = true
    }
    LaunchedEffect(Unit) {
        if (!hasCamera && !permissionAsked) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    var testQr by rememberSaveable { mutableStateOf("") }
    val base = BuildConfig.DH_PUBLIC_BASE_URL

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("QR 스캔") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color.Black),
            ) {
                if (hasCamera) {
                    CameraPreview(onQr = viewModel::onQrDetected, modifier = Modifier.fillMaxSize())
                } else {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("QR 을 찍으려면 카메라 권한이 필요해요", color = Color.White)
                        Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                            Text("카메라 권한 허용")
                        }
                    }
                }
                Text(
                    "차 화면의 QR 을 비추세요",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(12.dp),
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("테스트 QR 붙여넣기 (카메라 없는 에뮬레이터용)", style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(
                    value = testQr,
                    onValueChange = { testQr = it },
                    placeholder = { Text("$base/j/inv_…", style = MaterialTheme.typography.bodySmall) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(onClick = { viewModel.onQrDetected(testQr) }, enabled = testQr.isNotBlank()) {
                            Icon(Icons.Default.Check, contentDescription = "적용")
                        }
                    },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { testQr = "$base/j/inv_demo_0001" }) { Text("그룹 QR 예시") }
                    TextButton(onClick = { testQr = "$base/pair?c=KQ74MZ" }) { Text("차량 등록 QR 예시") }
                }
            }
        }
    }

    when (val s = state) {
        ScanViewModel.UiState.Scanning -> Unit

        is ScanViewModel.UiState.ConfirmJoin -> AlertDialog(
            onDismissRequest = viewModel::dismiss,
            title = { Text("그룹 합류") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("이 QR 로 대표 차량을 그룹에 합류시킬까요?")
                    Text(s.payload, style = MaterialTheme.typography.labelSmall)
                }
            },
            confirmButton = { TextButton(onClick = { viewModel.confirmJoin(s.payload) }) { Text("합류") } },
            dismissButton = { TextButton(onClick = viewModel::dismiss) { Text("취소") } },
        )

        is ScanViewModel.UiState.ConfirmPair -> {
            var nickname by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = viewModel::dismiss,
                title = { Text("차량 등록") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("차 화면의 QR 로 이 차를 내 계정에 등록합니다. 별명을 정해 주세요.")
                        OutlinedTextField(
                            value = nickname,
                            onValueChange = { nickname = it },
                            label = { Text("차 별명") },
                            singleLine = true,
                        )
                        Text(s.payload, style = MaterialTheme.typography.labelSmall)
                    }
                },
                confirmButton = { TextButton(onClick = { viewModel.confirmPair(s.payload, nickname) }) { Text("등록") } },
                dismissButton = { TextButton(onClick = viewModel::dismiss) { Text("취소") } },
            )
        }

        ScanViewModel.UiState.Working -> AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator()
                    Spacer(Modifier.width(16.dp))
                    Text("처리 중…")
                }
            },
        )

        is ScanViewModel.UiState.Joined -> AlertDialog(
            onDismissRequest = {},
            title = { Text("합류 완료") },
            text = { Text("'${s.result.name}' 그룹에 합류했어요 (멤버 ${s.result.members.size}대)") },
            confirmButton = { TextButton(onClick = { onJoined(s.result) }) { Text("그룹 보기") } },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.dismiss()
                    onBack()
                }) { Text("닫기") }
            },
        )

        is ScanViewModel.UiState.Paired -> AlertDialog(
            onDismissRequest = {},
            title = { Text("등록 완료") },
            text = { Text("'${s.result.nickname}' (${s.result.vehicleId}) 이(가) 내 차에 추가됐어요") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.dismiss()
                    onBack()
                }) { Text("확인") }
            },
        )

        is ScanViewModel.UiState.Error -> AlertDialog(
            onDismissRequest = viewModel::dismiss,
            title = { Text("스캔 실패") },
            text = { Text(s.message) },
            confirmButton = { TextButton(onClick = viewModel::dismiss) { Text("다시 스캔") } },
        )
    }
}
