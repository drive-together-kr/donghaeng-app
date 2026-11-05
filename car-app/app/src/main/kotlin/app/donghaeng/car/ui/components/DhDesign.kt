package app.donghaeng.car.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.donghaeng.car.ui.theme.DhPalette
import app.donghaeng.car.ui.theme.Pretendard

/*
 * 동행 디자인 언어 '밤 고속도로'(2026-11-05, docs/05 §5.0) — 화면마다 같은 부품을 쓴다.
 * - 주 버튼 = 고속도로 표지 초록 바탕에 흰 글자([DhPrimaryButton]). 화면에 하나.
 * - 카드 = 노견 색 바탕 + 가는 경계선([DhCard]). 카드 안에 카드를 두지 않는다.
 * - 목적지 = 고속도로 표지 모양([RoadSign]): 초록 판, 안쪽 흰 테두리, 위쪽 화살표.
 * - 대열 = 도로 띠([RoadStrip]): 아스팔트 띠, 흰 가장자리선, 호박색 중앙 점선 위에 차 표시 — 동행의 서명 요소.
 */

/** 도로 띠 색 — 바탕(아스팔트)보다 한 단계 어두운 노면, 가장자리 흰 선, 중앙 호박색 점선 */
object RoadColors {
    val surface = Color(0xFF0A0E13)
    val edge = Color(0xFFDDE3EA)
    val centerLine = DhPalette.darkTertiary
}

@Composable
fun DhPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    minHeight: Dp = 88.dp,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = minHeight),
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(
            containerColor = DhPalette.signGreen,
            contentColor = DhPalette.onSignGreen,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        border = if (enabled) BorderStroke(2.dp, Color.White.copy(alpha = 0.18f)) else null,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(14.dp))
        }
        Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun DhSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 76.dp),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.5.dp, if (enabled) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outlineVariant),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 노견 색 카드 — 경계선 1 dp. [content] 는 안쪽 여백 [padding] 안에 세로로 쌓인다 */
@Composable
fun DhCard(
    modifier: Modifier = Modifier,
    padding: Dp = 28.dp,
    color: Color = MaterialTheme.colorScheme.surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = color,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(padding), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

/**
 * 고속도로 표지 모양의 목적지 판 — 초록 바탕, 안쪽 흰 테두리, 위쪽 화살표, 이름(굵게)과 보조 줄.
 * [large] = 홈·그룹 화면의 큰 표지, 아니면 줄 안에 들어가는 작은 표지.
 */
@Composable
fun RoadSign(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    large: Boolean = true,
    muted: Boolean = false,
) {
    val bg = if (muted) MaterialTheme.colorScheme.surfaceVariant else DhPalette.signGreen
    val fg = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else Color.White
    Surface(modifier = modifier, shape = RoundedCornerShape(if (large) 16.dp else 12.dp), color = bg) {
        Row(
            Modifier
                .padding(if (large) 8.dp else 6.dp)
                .border(BorderStroke(if (large) 3.dp else 2.dp, fg.copy(alpha = 0.9f)), RoundedCornerShape(if (large) 10.dp else 8.dp))
                .padding(horizontal = if (large) 22.dp else 14.dp, vertical = if (large) 14.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(DhIcons.arrowUp, contentDescription = null, tint = fg, modifier = Modifier.size(if (large) 40.dp else 28.dp))
            Spacer(Modifier.width(if (large) 16.dp else 10.dp))
            Column {
                Text(
                    title,
                    color = fg,
                    fontSize = if (large) 34.sp else 24.sp,
                    lineHeight = if (large) 40.sp else 30.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(subtitle, color = fg.copy(alpha = 0.85f), fontSize = if (large) 20.sp else 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** 도로 띠 위의 차 하나 — [position] 0(뒤) ~ 1(앞, 목적지 쪽) */
data class RoadCar(val name: String, val color: Color, val isMe: Boolean, val position: Float)

/**
 * 서명 요소: 도로 띠. 아스팔트 띠에 흰 가장자리선과 호박색 중앙 점선, 그 위에 차(색 원 — 내 차는 흰 테두리·후광)와 이름.
 * [moving] = 중앙 점선이 천천히 흘러 '달리는 중' 느낌(정차 화면 장식용 — 주행 중 화면에서는 끈다).
 * 오른쪽 끝에 [trailing](보통 [RoadSign])을 붙이면 도로가 표지로 이어진다.
 */
@Composable
fun RoadStrip(
    cars: List<RoadCar>,
    modifier: Modifier = Modifier,
    moving: Boolean = false,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val measurer = rememberTextMeasurer()
    val phase by if (moving) {
        rememberInfiniteTransition(label = "road").animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "dash",
        )
    } else {
        androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    }
    val labelColor = MaterialTheme.colorScheme.onSurface
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).fillMaxSize()) {
            Canvas(Modifier.fillMaxSize()) {
                val bandH = (size.height * 0.40f).coerceIn(64.dp.toPx(), 120.dp.toPx())
                val top = size.height * 0.60f - bandH / 2f
                val cy = top + bandH / 2f
                drawRoundRect(RoadColors.surface, Offset(0f, top), Size(size.width, bandH), CornerRadius(bandH / 2f, bandH / 2f))
                val edgeInset = 10.dp.toPx()
                val edgeW = 3.dp.toPx()
                drawLine(RoadColors.edge.copy(alpha = 0.55f), Offset(bandH / 2f, top + edgeInset), Offset(size.width - edgeInset, top + edgeInset), edgeW, StrokeCap.Round)
                drawLine(RoadColors.edge.copy(alpha = 0.55f), Offset(bandH / 2f, top + bandH - edgeInset), Offset(size.width - edgeInset, top + bandH - edgeInset), edgeW, StrokeCap.Round)
                val dash = 34.dp.toPx()
                val gap = 26.dp.toPx()
                drawLine(
                    RoadColors.centerLine, Offset(bandH / 2f, cy), Offset(size.width - edgeInset, cy), 5.dp.toPx(), StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, gap), -(dash + gap) * phase),
                )
                val r = 22.dp.toPx()
                val x0 = bandH / 2f + r
                val x1 = size.width - r - 24.dp.toPx()
                val nameStyle = TextStyle(fontFamily = Pretendard, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = labelColor)
                cars.sortedBy { it.isMe }.forEach { car ->
                    val x = x0 + (x1 - x0) * car.position.coerceIn(0f, 1f)
                    if (car.isMe) drawCircle(car.color.copy(alpha = 0.28f), r + 12.dp.toPx(), Offset(x, cy))
                    drawCircle(car.color, r, Offset(x, cy))
                    drawCircle(Color.White, r, Offset(x, cy), style = Stroke(if (car.isMe) 4.dp.toPx() else 2.dp.toPx()))
                    val t = measurer.measure(car.name, nameStyle)
                    drawText(t, topLeft = Offset((x - t.size.width / 2f).coerceIn(0f, size.width - t.size.width), top - t.size.height - 10.dp.toPx()))
                }
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

/** 부품용 아이콘 — material-icons-core 에 없는 것만 경로로(Material Icons, Apache 2.0) */
object DhIcons {
    private fun icon(name: String, path: String): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black))
            .build()

    val arrowUp by lazy { icon("dh.ArrowUp", "M4,12l1.41,1.41L11,7.83V20h2V7.83l5.58,5.59L20,12l-8,-8 -8,8z") }
    val car by lazy {
        icon(
            "dh.Car",
            "M18.92,6.01C18.72,5.42 18.16,5 17.5,5h-11c-0.66,0 -1.21,0.42 -1.42,1.01L3,12v8c0,0.55 0.45,1 1,1h1c0.55,0 1,-0.45 1,-1v-1h12v1c0,0.55 0.45,1 1,1h1c0.55,0 1,-0.45 1,-1v-8l-2.08,-5.99zM6.5,16c-0.83,0 -1.5,-0.67 -1.5,-1.5S5.67,13 6.5,13s1.5,0.67 1.5,1.5S7.33,16 6.5,16zM17.5,16c-0.83,0 -1.5,-0.67 -1.5,-1.5s0.67,-1.5 1.5,-1.5 1.5,0.67 1.5,1.5 -0.67,1.5 -1.5,1.5zM5,11l1.5,-4.5h11L19,11H5z",
        )
    }
    val qr by lazy {
        icon(
            "dh.Qr",
            "M3,11h8V3H3V11zM5,5h4v4H5V5zM3,21h8v-8H3V21zM5,15h4v4H5V15zM13,3v8h8V3H13zM19,9h-4V5h4V9zM19,19h2v2h-2V19zM13,13h2v2h-2V13zM15,15h2v2h-2V15zM13,17h2v2h-2V17zM15,19h2v2h-2V19zM17,17h2v2h-2V17zM17,13h2v2h-2V13zM19,15h2v2h-2V15z",
        )
    }
    val phone by lazy { icon("dh.Phone", "M17,1.01L7,1c-1.1,0 -2,0.9 -2,2v18c0,1.1 0.9,2 2,2h10c1.1,0 2,-0.9 2,-2V3c0,-1.1 -0.9,-1.99 -2,-1.99zM17,19H7V5h10v14z") }
    val navigation by lazy { icon("dh.Navigation", "M12,2L4.5,20.29l0.71,0.71L12,18l6.79,3 0.71,-0.71z") }
    val mic by lazy {
        icon("dh.Mic", "M12,14c1.66,0 2.99,-1.34 2.99,-3L15,5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6c0,1.66 1.34,3 3,3zM17.3,11c0,3 -2.54,5.1 -5.3,5.1S6.7,14 6.7,11L5,11c0,3.41 2.72,6.23 6,6.72L11,21h2v-3.28c3.28,-0.48 6,-3.3 6,-6.72h-1.7z")
    }
    val group by lazy {
        icon(
            "dh.Group",
            "M16,11c1.66,0 2.99,-1.34 2.99,-3S17.66,5 16,5c-1.66,0 -3,1.34 -3,3s1.34,3 3,3zM8,11c1.66,0 2.99,-1.34 2.99,-3S9.66,5 8,5C6.34,5 5,6.34 5,8s1.34,3 3,3zM8,13c-2.33,0 -7,1.17 -7,3.5V19h14v-2.5c0,-2.33 -4.67,-3.5 -7,-3.5zM16,13c-0.29,0 -0.62,0.02 -0.97,0.05 1.16,0.84 1.97,1.97 1.97,3.45V19h6v-2.5c0,-2.33 -4.67,-3.5 -7,-3.5z",
        )
    }
    val settings by lazy {
        icon(
            "dh.Settings",
            "M19.14,12.94c0.04,-0.3 0.06,-0.61 0.06,-0.94c0,-0.32 -0.02,-0.64 -0.07,-0.94l2.03,-1.58c0.18,-0.14 0.23,-0.41 0.12,-0.61l-1.92,-3.32c-0.12,-0.22 -0.37,-0.29 -0.59,-0.22l-2.39,0.96c-0.5,-0.38 -1.03,-0.7 -1.62,-0.94L14.4,2.81c-0.04,-0.24 -0.24,-0.41 -0.48,-0.41h-3.84c-0.24,0 -0.43,0.17 -0.47,0.41L9.25,5.35C8.66,5.59 8.12,5.92 7.63,6.29L5.24,5.33c-0.22,-0.08 -0.47,0 -0.59,0.22L2.74,8.87C2.62,9.08 2.66,9.34 2.86,9.48l2.03,1.58C4.84,11.36 4.8,11.69 4.8,12s0.02,0.64 0.07,0.94l-2.03,1.58c-0.18,0.14 -0.23,0.41 -0.12,0.61l1.92,3.32c0.12,0.22 0.37,0.29 0.59,0.22l2.39,-0.96c0.5,0.38 1.03,0.7 1.62,0.94l0.36,2.54c0.05,0.24 0.24,0.41 0.48,0.41h3.84c0.24,0 0.44,-0.17 0.47,-0.41l0.36,-2.54c0.59,-0.24 1.13,-0.56 1.62,-0.94l2.39,0.96c0.22,0.08 0.47,0 0.59,-0.22l1.92,-3.32c0.12,-0.22 0.07,-0.47 -0.12,-0.61L19.14,12.94zM12,15.6c-1.98,0 -3.6,-1.62 -3.6,-3.6s1.62,-3.6 3.6,-3.6s3.6,1.62 3.6,3.6S13.98,15.6 12,15.6z",
        )
    }
}
