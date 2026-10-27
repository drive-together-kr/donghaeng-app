package app.donghaeng.companion.demo.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.donghaeng.companion.demo.ui.theme.KiaColors

/*
 * 차량 그림 — 실제 렌더 이미지는 쓰지 않고(브랜드 자산) 단순 벡터 실루엣으로 대신한다.
 * SUV 옆모습(홈·마이카·스토어), 윗모습(제어), 작은 주차 아이콘(내 차 위치 카드).
 */

/** SUV 옆모습(앞쪽이 오른쪽). 폭에 맞춰 비율 유지(가로:세로 ≈ 2.4:1) */
@Composable
fun CarSideSilhouette(
    modifier: Modifier = Modifier,
    height: Dp = 120.dp,
    body: Color = Color(0xFFE8ECEF),
    shade: Color = Color(0xFFB8C0C8),
    glass: Color = Color(0xFF2A343D),
    wheel: Color = Color(0xFF0D161D),
    /** 앞쪽이 왼쪽(기아 앱 렌더와 같은 방향). false 면 오른쪽 */
    facingLeft: Boolean = true,
) {
    Canvas(modifier = modifier.height(height)) {
        val w = size.width
        val h = size.height
        // 차체 크기를 캔버스 안에 맞춘다
        val carW = minOf(w, h * 2.4f)
        val carH = carW / 2.4f
        val ox = (w - carW) / 2f
        val oy = (h - carH) / 2f
        fun x(fx: Float) = ox + (if (facingLeft) 1f - fx else fx) * carW
        fun y(fy: Float) = oy + fy * carH

        // 그림자
        drawOval(
            brush = Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent)),
            topLeft = Offset(x(0.05f), y(0.78f)),
            size = Size(carW * 0.9f, carH * 0.22f),
        )
        // 차체
        val bodyPath = Path().apply {
            moveTo(x(0.04f), y(0.70f))
            lineTo(x(0.04f), y(0.52f))
            quadraticTo(x(0.05f), y(0.44f), x(0.14f), y(0.42f))
            lineTo(x(0.26f), y(0.40f))
            // 보닛에서 A 필러로
            quadraticTo(x(0.34f), y(0.22f), x(0.46f), y(0.16f))
            lineTo(x(0.70f), y(0.14f))
            quadraticTo(x(0.86f), y(0.14f), x(0.92f), y(0.34f))
            lineTo(x(0.96f), y(0.50f))
            quadraticTo(x(0.97f), y(0.66f), x(0.92f), y(0.70f))
            close()
        }
        drawPath(bodyPath, brush = Brush.verticalGradient(listOf(body, shade), startY = y(0.1f), endY = y(0.75f)))
        // 유리창
        val glassPath = Path().apply {
            moveTo(x(0.30f), y(0.40f))
            quadraticTo(x(0.37f), y(0.26f), x(0.47f), y(0.21f))
            lineTo(x(0.69f), y(0.19f))
            quadraticTo(x(0.80f), y(0.20f), x(0.85f), y(0.36f))
            lineTo(x(0.86f), y(0.40f))
            close()
        }
        drawPath(glassPath, color = glass)
        // B 필러
        drawLine(body, Offset(x(0.60f), y(0.20f)), Offset(x(0.60f), y(0.40f)), strokeWidth = carH * 0.03f)
        // 바퀴
        wheelAt(x(0.24f), y(0.70f), carH * 0.17f, wheel, shade)
        wheelAt(x(0.78f), y(0.70f), carH * 0.17f, wheel, shade)
        // 헤드램프(앞)·테일램프(뒤) — 좌우 반전 시 왼쪽 모서리를 다시 계산
        fun lamp(color: Color, fx: Float, fy: Float, fw: Float, fh: Float) {
            val left = if (facingLeft) x(fx + fw) else x(fx)
            drawRoundRect(color, Offset(left, y(fy)), Size(carW * fw, carH * fh))
        }
        lamp(KiaColors.Accent.copy(alpha = 0.9f), 0.90f, 0.47f, 0.05f, 0.06f)
        lamp(Color(0xFFE5484D).copy(alpha = 0.8f), 0.045f, 0.50f, 0.03f, 0.07f)
    }
}

private fun DrawScope.wheelAt(cx: Float, cy: Float, r: Float, wheel: Color, rim: Color) {
    drawCircle(wheel, r, Offset(cx, cy))
    drawCircle(rim, r * 0.55f, Offset(cx, cy), style = Stroke(width = r * 0.18f))
    drawCircle(rim, r * 0.12f, Offset(cx, cy))
}

/** SUV 윗모습(제어 탭). 앞쪽이 위 */
@Composable
fun CarTopSilhouette(
    modifier: Modifier = Modifier,
    height: Dp = 180.dp,
    body: Color = Color(0xFF9AA4AD),
    shade: Color = Color(0xFF5E6871),
    glass: Color = Color(0xFF1B252E),
    lockIcon: @Composable (() -> Unit)? = null,
) {
    Box(modifier = modifier.height(height)) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val carH = minOf(h, w * 1.9f)
            val carW = carH / 1.9f
            val ox = (w - carW) / 2f
            val oy = (h - carH) / 2f
            fun x(fx: Float) = ox + fx * carW
            fun y(fy: Float) = oy + fy * carH

            // 바퀴 4개(차체 뒤에)
            val wheelW = carW * 0.16f
            val wheelH = carH * 0.12f
            listOf(0.02f to 0.18f, 0.82f to 0.18f, 0.02f to 0.68f, 0.82f to 0.68f).forEach { (fx, fy) ->
                drawRoundRect(Color(0xFF0D161D), Offset(x(fx), y(fy)), Size(wheelW, wheelH), androidx.compose.ui.geometry.CornerRadius(wheelW * 0.3f))
            }
            // 차체
            val bodyPath = Path().apply {
                moveTo(x(0.12f), y(0.98f))
                lineTo(x(0.12f), y(0.14f))
                quadraticTo(x(0.12f), y(0.02f), x(0.30f), y(0.02f))
                lineTo(x(0.70f), y(0.02f))
                quadraticTo(x(0.88f), y(0.02f), x(0.88f), y(0.14f))
                lineTo(x(0.88f), y(0.98f))
                close()
            }
            drawPath(bodyPath, brush = Brush.horizontalGradient(listOf(shade, body, shade), startX = x(0.12f), endX = x(0.88f)))
            // 앞유리·선루프·뒷유리
            drawRoundRect(glass, Offset(x(0.18f), y(0.18f)), Size(carW * 0.64f, carH * 0.12f), androidx.compose.ui.geometry.CornerRadius(carW * 0.06f))
            drawRoundRect(glass, Offset(x(0.30f), y(0.36f)), Size(carW * 0.40f, carH * 0.20f), androidx.compose.ui.geometry.CornerRadius(carW * 0.04f))
            drawRoundRect(glass, Offset(x(0.18f), y(0.74f)), Size(carW * 0.64f, carH * 0.10f), androidx.compose.ui.geometry.CornerRadius(carW * 0.06f))
            // 헤드램프
            drawRoundRect(KiaColors.Accent, Offset(x(0.14f), y(0.05f)), Size(carW * 0.14f, carH * 0.025f))
            drawRoundRect(KiaColors.Accent, Offset(x(0.72f), y(0.05f)), Size(carW * 0.14f, carH * 0.025f))
            // 테일램프
            drawRoundRect(Color(0xFFE5484D), Offset(x(0.14f), y(0.955f)), Size(carW * 0.72f, carH * 0.02f))
        }
        if (lockIcon != null) {
            Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) { lockIcon() }
        }
    }
}

/** "내 차 위치" 카드의 작은 주차 그림(차 + 핀 자리). 60~90dp 정도 */
@Composable
fun CarParkedArt(modifier: Modifier = Modifier, height: Dp = 72.dp) {
    Canvas(modifier = modifier.height(height)) {
        val w = size.width
        val h = size.height
        // 바닥 타일
        drawRoundRect(Color(0xFF1F2A33), Offset(0f, h * 0.55f), Size(w, h * 0.45f), androidx.compose.ui.geometry.CornerRadius(h * 0.1f))
        drawLine(Color(0xFF3A4650), Offset(w * 0.1f, h * 0.6f), Offset(w * 0.1f, h * 0.95f), strokeWidth = 2f)
        drawLine(Color(0xFF3A4650), Offset(w * 0.9f, h * 0.6f), Offset(w * 0.9f, h * 0.95f), strokeWidth = 2f)
        // 차(옆모습 축소)
        val carW = w * 0.74f
        val carH = carW / 2.4f
        val ox = (w - carW) / 2f
        val oy = h * 0.72f - carH
        fun x(fx: Float) = ox + fx * carW
        fun y(fy: Float) = oy + fy * carH
        val bodyPath = Path().apply {
            moveTo(x(0.04f), y(0.70f)); lineTo(x(0.04f), y(0.52f)); quadraticTo(x(0.05f), y(0.44f), x(0.14f), y(0.42f))
            lineTo(x(0.26f), y(0.40f)); quadraticTo(x(0.34f), y(0.22f), x(0.46f), y(0.16f)); lineTo(x(0.70f), y(0.14f))
            quadraticTo(x(0.86f), y(0.14f), x(0.92f), y(0.34f)); lineTo(x(0.96f), y(0.50f)); quadraticTo(x(0.97f), y(0.66f), x(0.92f), y(0.70f)); close()
        }
        drawPath(bodyPath, Color(0xFFE8ECEF))
        drawCircle(Color(0xFF0D161D), carH * 0.16f, Offset(x(0.24f), y(0.70f)))
        drawCircle(Color(0xFF0D161D), carH * 0.16f, Offset(x(0.78f), y(0.70f)))
    }
}
