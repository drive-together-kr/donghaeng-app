package app.donghaeng.car.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.donghaeng.car.R
import app.donghaeng.car.ui.theme.DhPalette

/**
 * 추천 목적지 사진(로컬 이미지 모델로 새로 만든 그림 — 실제 사진·타사 이미지 아님, docs/05 §5.0). 목적지 이름 → 그림. 없으면 null(표지만).
 */
object DestinationArt {
    @DrawableRes
    fun forName(name: String?): Int? = when (name) {
        "경포해변" -> R.drawable.dest_gyeongpo
        "부산역" -> R.drawable.dest_busan
        "광주송정역" -> R.drawable.dest_gwangju
        "대전역" -> R.drawable.dest_daejeon
        "서울시청" -> R.drawable.dest_seoul
        else -> null
    }
}

/**
 * 목적지 사진 카드 — 사진 위 아래쪽 어둡게, 이름(굵게)·보조 줄. 고르면 표지 초록 테두리와 체크 표시.
 * [enabled] = false(주행 중)면 흐리게 하고 누를 수 없다.
 */
@Composable
fun DestinationPhotoCard(
    name: String,
    @DrawableRes image: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.alpha(if (enabled) 1f else 0.5f),
        shape = MaterialTheme.shapes.large,
        border = if (selected) BorderStroke(4.dp, DhPalette.darkPrimary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Box(Modifier.fillMaxSize()) {
            Image(painterResource(image), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.82f))),
            )
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp)) {
                Text(name, color = Color.White, fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) {
                    Text(subtitle, color = Color.White.copy(alpha = 0.8f), fontSize = 17.sp, lineHeight = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (selected) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                        .size(40.dp)
                        .background(DhPalette.signGreen, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
                }
            }
        }
    }
}

/** 목적지 배너 — 사진(있으면) 위 왼쪽 아래에 고속도로 표지. 사진이 없으면 표지만 */
@Composable
fun DestinationBanner(name: String, subtitle: String?, modifier: Modifier = Modifier, height: androidx.compose.ui.unit.Dp = 220.dp) {
    val art = DestinationArt.forName(name)
    if (art != null) {
        Box(modifier.height(height).clip(MaterialTheme.shapes.large)) {
            Image(painterResource(art), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            RoadSign(name, subtitle = subtitle, modifier = Modifier.align(Alignment.BottomStart).padding(18.dp))
        }
    } else {
        RoadSign(name, subtitle = subtitle, modifier = modifier)
    }
}
