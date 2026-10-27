package app.donghaeng.car.feature.pairing

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * ZXing QR 생성. 차량 앱은 QR 문자열을 만들지 않고 서버가 준 `verifyUri` / `invite.url` 을 그대로 그린다(docs/05 §7).
 */
object QrCodes {

    /**
     * 모듈 단위 [BitMatrix]. width/height 를 0 으로 주면 ZXing 이 (모듈 수 + 여백) 크기로 만들어 셀 하나가 1 픽셀이 된다 →
     * 그릴 때 셀 크기만 곱하면 된다. 오류 정정 M, 여백 1 모듈.
     */
    fun encode(content: String): BitMatrix {
        val hints = mapOf(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        return QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, hints)
    }

    /** 어두운 모듈 수(테스트·빈 QR 판정용). */
    fun darkModules(matrix: BitMatrix): Int {
        var count = 0
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) if (matrix.get(x, y)) count++
        return count
    }
}

/**
 * [content] 를 QR 로 그린다. 정사각형, 흰 바탕(다크 테마에서도 스캔되게 고정), 검은 모듈.
 * 인코딩에 실패하면(빈 문자열 등) 흰 사각형만 보인다 — 크래시 금지.
 */
@Composable
fun QrCodeImage(content: String, modifier: Modifier = Modifier) {
    val matrix = remember(content) { runCatching { QrCodes.encode(content) }.getOrNull() }
    Canvas(
        modifier = modifier
            .aspectRatio(1f)
            .background(Color.White)
            .padding(8.dp),
    ) {
        val m = matrix ?: return@Canvas
        val cell = size.minDimension / m.width
        val offsetX = (size.width - cell * m.width) / 2f
        val offsetY = (size.height - cell * m.height) / 2f
        // 셀 경계의 안티에일리어싱 틈을 막기 위해 0.5px 겹쳐 그린다
        val cellSize = Size(cell + 0.5f, cell + 0.5f)
        for (y in 0 until m.height) {
            for (x in 0 until m.width) {
                if (m.get(x, y)) {
                    drawRect(Color.Black, topLeft = Offset(offsetX + x * cell, offsetY + y * cell), size = cellSize)
                }
            }
        }
    }
}
