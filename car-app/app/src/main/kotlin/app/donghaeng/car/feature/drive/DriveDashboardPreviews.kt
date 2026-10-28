package app.donghaeng.car.feature.drive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.donghaeng.car.ui.theme.DongHaengTheme

/**
 * 시안 Compose Preview (Android Studio) — 가로 주행 / 세로 주행 / 정차 + 무전 상태 4종.
 * 기기에서 같은 화면을 보려면: `am broadcast -n app.donghaeng.car/.debug.DemoControlReceiver --es action open --es screen design --es variant <이름>` (debug 빌드).
 */

/** 시안 호스트 — 변형 이름으로 샘플을 고르고, 세로 변형은 폭 620 dp 로 좁혀 1창(세로) 배치를 보인다 */
@Composable
fun DesignPreviewHost(variant: String) {
    val dark = variant != "drive_light"
    DongHaengTheme(darkTheme = dark) {
        if (variant == "drive_portrait") {
            Box(Modifier.fillMaxHeight(), contentAlignment = Alignment.Center) {
                DriveDashboardScreen(DriveDashboardSamples.of(variant), Modifier.width(620.dp).fillMaxHeight())
            }
        } else {
            DriveDashboardScreen(DriveDashboardSamples.of(variant))
        }
    }
}

@Preview(name = "가로 주행", widthDp = 1700, heightDp = 1250, showBackground = true)
@Composable
private fun PreviewDriveLandscape() = DesignPreviewHost("drive_landscape")

@Preview(name = "세로 주행(1창)", widthDp = 620, heightDp = 1250, showBackground = true)
@Composable
private fun PreviewDrivePortrait() = DongHaengTheme(darkTheme = true) { DriveDashboardScreen(DriveDashboardSamples.of("drive_portrait")) }

@Preview(name = "정차", widthDp = 1700, heightDp = 1250, showBackground = true)
@Composable
private fun PreviewParked() = DesignPreviewHost("parked")

@Preview(name = "무전 녹음", widthDp = 1700, heightDp = 1250, showBackground = true)
@Composable
private fun PreviewPttRecording() = DesignPreviewHost("ptt_recording")

@Preview(name = "무전 확인 대기", widthDp = 1700, heightDp = 1250, showBackground = true)
@Composable
private fun PreviewPttConfirm() = DesignPreviewHost("ptt_confirm")

@Preview(name = "무전 전송됨", widthDp = 1700, heightDp = 1250, showBackground = true)
@Composable
private fun PreviewPttSent() = DesignPreviewHost("ptt_sent")

@Preview(name = "무전 수신 재생", widthDp = 1700, heightDp = 1250, showBackground = true)
@Composable
private fun PreviewPttPlaying() = DesignPreviewHost("ptt_playing")

@Preview(name = "주간 모드", widthDp = 1700, heightDp = 1250, showBackground = true)
@Composable
private fun PreviewDriveLight() = DesignPreviewHost("drive_light")
