package app.donghaeng.car.feature.pairing

import androidx.compose.runtime.Composable
import app.donghaeng.car.ui.components.PlaceholderScreen

/**
 * 페어링 화면 — T12.
 * 서버 `POST /v1/device/code` 응답의 verifyUri 를 ZXing 으로 QR 표시 + userCode 표시, interval 마다 `/device/token` 폴링.
 * 차량 앱은 QR 문자열을 직접 만들지 않는다(서버 응답 그대로, docs/05 §7).
 */
@Composable
fun PairingScreen() {
    PlaceholderScreen(title = "내 폰과 연결하기", taskId = "T12")
}
