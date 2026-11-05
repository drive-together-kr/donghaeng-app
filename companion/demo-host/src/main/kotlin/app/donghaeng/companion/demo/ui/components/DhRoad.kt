package app.donghaeng.companion.demo.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.donghaeng.companion.demo.R
import app.donghaeng.companion.demo.ui.theme.KiaColors
import app.donghaeng.companion.demo.ui.theme.PretendardFamily

/*
 * 동행 디자인 언어 '밤 고속도로'의 폰 쪽 부품 — 차량 앱(car-app ui.components.DhDesign)과 같은 모양을 폰 크기로.
 * 목적지 = 고속도로 표지([RoadSign]), 목적지 사진([DestinationBanner] — 로컬 이미지 모델로 새로 만든 그림),
 * 그룹 = 도로 띠([RoadStrip] — 노면·흰 가장자리선·호박색 중앙 점선 위에 차 표시).
 */

/** 추천 목적지 이름 → 그림(차량 앱 DESTINATION_PRESETS 와 같은 5곳). 없으면 null(표지만) */
object DestinationArt {
    @DrawableRes
    fun forName(name: String?): Int? = when (name?.trim()) {
        "경포해변" -> R.drawable.dest_gyeongpo
        "부산역" -> R.drawable.dest_busan
        "광주송정역" -> R.drawable.dest_gwangju
        "대전역" -> R.drawable.dest_daejeon
        "서울시청" -> R.drawable.dest_seoul
        else -> null
    }
}

private val ArrowUp: ImageVector by lazy {
    ImageVector.Builder(name = "dh.ArrowUp", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(pathData = addPathNodes("M4,12l1.41,1.41L11,7.83V20h2V7.83l5.58,5.59L20,12l-8,-8 -8,8z"), fill = SolidColor(Color.Black))
        .build()
}

/** 고속도로 표지 모양 목적지 판 — 초록 바탕, 안쪽 흰 테두리, 위쪽 화살표. [muted] = 목적지 없음(회색) */
@Composable
fun RoadSign(title: String, modifier: Modifier = Modifier, subtitle: String? = null, muted: Boolean = false) {
    val bg = if (muted) KiaColors.SubCard else KiaColors.SignGreen
    val fg = if (muted) KiaColors.TextSecondary else Color.White
    Row(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .padding(5.dp)
            .border(BorderStroke(2.dp, fg.copy(alpha = 0.9f)), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(ArrowUp, contentDescription = null, tint = fg, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Column {
            Text(title, color = fg, fontFamily = PretendardFamily, fontWeight = FontWeight.Bold, fontSize = 19.sp, lineHeight = 24.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, color = fg.copy(alpha = 0.85f), fontFamily = PretendardFamily, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 목적지 사진(있으면) 위 왼쪽 아래에 고속도로 표지. 사진이 없으면 표지만 */
@Composable
fun DestinationBanner(name: String, modifier: Modifier = Modifier, subtitle: String? = "목적지", height: Dp = 132.dp) {
    val art = DestinationArt.forName(name)
    if (art == null) {
        RoadSign(name.ifBlank { "목적지" }, modifier, subtitle = subtitle, muted = name.isBlank())
        return
    }
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(16.dp))) {
        Image(painterResource(art), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.55f))))
        RoadSign(name, Modifier.align(Alignment.BottomStart).padding(10.dp), subtitle = subtitle)
    }
}

/** 도로 띠 위의 차 하나 — [position] 0(뒤) ~ 1(앞, 목적지 쪽) */
data class RoadCar(val name: String, val color: Color, val isMine: Boolean, val position: Float)

/** 도로 띠 — 노면·흰 가장자리선·호박색 중앙 점선 위에 차(색 원 + 이름). 내 차는 흰 테두리와 후광 */
@Composable
fun RoadStrip(cars: List<RoadCar>, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier.fillMaxWidth().height(76.dp)) {
        val bandH = 30.dp.toPx()
        val cy = size.height - bandH / 2f - 4.dp.toPx()
        val top = cy - bandH / 2f
        drawRoundRect(Color(0xFF0A0E13), Offset(0f, top), Size(size.width, bandH), CornerRadius(bandH / 2f, bandH / 2f))
        val inset = 5.dp.toPx()
        listOf(top + inset, top + bandH - inset).forEach { y ->
            drawLine(Color(0xFFDDE3EA).copy(alpha = 0.5f), Offset(bandH / 2f, y), Offset(size.width - bandH / 2f, y), 1.5.dp.toPx(), StrokeCap.Round)
        }
        drawLine(
            KiaColors.CenterAmber, Offset(bandH / 2f, cy), Offset(size.width - bandH / 2f, cy), 3.dp.toPx(), StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(16.dp.toPx(), 12.dp.toPx())),
        )
        val r = 10.dp.toPx()
        val x0 = bandH / 2f + r
        val x1 = size.width - bandH / 2f - r
        val style = TextStyle(fontFamily = PretendardFamily, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = KiaColors.TextPrimary)
        val placed = cars.sortedBy { it.isMine }
        placed.forEachIndexed { i, car ->
            val x = x0 + (x1 - x0) * car.position.coerceIn(0f, 1f)
            if (car.isMine) drawCircle(car.color.copy(alpha = 0.3f), r + 6.dp.toPx(), Offset(x, cy))
            drawCircle(car.color, r, Offset(x, cy))
            drawCircle(Color.White, r, Offset(x, cy), style = Stroke(if (car.isMine) 2.5.dp.toPx() else 1.dp.toPx()))
            val t = measurer.measure(car.name, style)
            // 가까운 차끼리 이름이 겹치지 않게 번갈아 한 줄 위로
            val lift = if (i % 2 == 1 && placed.size > 2) 16.dp.toPx() else 0f
            drawText(t, topLeft = Offset((x - t.size.width / 2f).coerceIn(0f, size.width - t.size.width), top - t.size.height - 4.dp.toPx() - lift))
        }
    }
}
