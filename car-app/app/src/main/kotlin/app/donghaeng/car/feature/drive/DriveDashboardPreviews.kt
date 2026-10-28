package app.donghaeng.car.feature.drive

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.Color
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

/**
 * 시안 호스트 — 변형 이름으로 샘플을 고르고, 세로 변형은 폭 620 dp 로 좁혀 1창(세로) 배치를 보인다.
 * `panel_dark`/`panel_light`/`panel_full`: 플로팅 패널 단독(내비 지도 색 바탕의 오른쪽 위, 실제 서비스와 같은 여백 110/190 dp).
 */
@Composable
fun DesignPreviewHost(variant: String) {
    if (variant.startsWith("panel_")) {
        PanelPreviewHost(light = variant == "panel_light", ui = if (variant == "panel_full") FloatingPanelSamples.full else FloatingPanelSamples.base)
        return
    }
    val dark = variant != "drive_light"
    DongHaengTheme(darkTheme = dark) {
        if (variant == "drive_portrait") {
            // 세로 1창 시안: 폭 620 dp(세로형 헤드유닛 가정)를 어두운 배경 가운데 둔다 — 캡처는 이 열만 잘라 쓴다
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
                DriveDashboardScreen(DriveDashboardSamples.of(variant), Modifier.width(620.dp).fillMaxHeight())
            }
        } else {
            DriveDashboardScreen(DriveDashboardSamples.of(variant))
        }
    }
}

/** 플로팅 패널 단독 미리보기 — 바탕은 순정 내비 지도 색(주간 흰 지도 / 야간 짙은 지도) */
@Composable
private fun PanelPreviewHost(light: Boolean, ui: FloatingPanelUi) {
    DongHaengTheme(darkTheme = !light) {
        Box(Modifier.fillMaxSize().background(if (light) Color(0xFFF1F2F4) else Color(0xFF1E232B)), contentAlignment = Alignment.TopEnd) {
            FloatingPanel(ui, light = light, modifier = Modifier.padding(end = 110.dp, top = 190.dp))
        }
    }
}

@Preview(name = "플로팅 패널 야간", widthDp = 1707, heightDp = 1210)
@Composable
private fun PreviewPanelDark() = PanelPreviewHost(light = false, ui = FloatingPanelSamples.base)

@Preview(name = "플로팅 패널 주간", widthDp = 1707, heightDp = 1210)
@Composable
private fun PreviewPanelLight() = PanelPreviewHost(light = true, ui = FloatingPanelSamples.base)

@Preview(name = "플로팅 패널 5대(외 1대)", widthDp = 1707, heightDp = 1210)
@Composable
private fun PreviewPanelFull() = PanelPreviewHost(light = false, ui = FloatingPanelSamples.full)

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
