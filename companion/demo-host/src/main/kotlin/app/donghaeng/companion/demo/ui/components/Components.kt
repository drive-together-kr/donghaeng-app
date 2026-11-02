package app.donghaeng.companion.demo.ui.components

import android.content.Context
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.donghaeng.companion.demo.R
import app.donghaeng.companion.demo.ui.theme.KiaColors
import app.donghaeng.companion.demo.ui.theme.KiaDimens

/*
 * 기아 앱 재현 화면의 공용 조각. 모든 탭(홈·지도·제어·스토어·마이카)과 동행 UI 가 같은 조각을 쓴다.
 * 아이콘은 res/drawable/ms_*.xml (Material Symbols Outlined 변환본)을 [MsIcon] 으로 그린다.
 */

/** Material Symbols 아이콘 (ms_*.xml). 기본 흰색, 24dp */
@Composable
fun MsIcon(
    @DrawableRes id: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = KiaColors.TextPrimary,
    size: Dp = 24.dp,
) {
    Icon(painter = painterResource(id), contentDescription = contentDescription, tint = tint, modifier = modifier.size(size))
}

/** 큰 라운드(24dp) 카드. [onClick] 이 있으면 눌린다 */
@Composable
fun KiaCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    color: Color = KiaColors.Card,
    shape: Shape = RoundedCornerShape(KiaDimens.CardRadius),
    contentPadding: PaddingValues = PaddingValues(KiaDimens.CardPadding),
    content: @Composable ColumnScope.() -> Unit,
) {
    val clickable = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(color)
            .then(clickable)
            .padding(contentPadding),
        content = content,
    )
}

/** 카드 안의 서브카드(16dp) */
@Composable
fun SubCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) = KiaCard(
    modifier = modifier,
    onClick = onClick,
    color = KiaColors.SubCard,
    shape = RoundedCornerShape(KiaDimens.SubCardRadius),
    contentPadding = contentPadding,
    content = content,
)

/** 섹션 제목 + 오른쪽 액션("더 보기 >", "편집") */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    actionChevron: Boolean = true,
    onAction: (() -> Unit)? = null,
    titleStyle: TextStyle = MaterialTheme.typography.titleLarge,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = titleStyle, modifier = Modifier.weight(1f))
        if (action != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable(enabled = onAction != null) { onAction?.invoke() },
            ) {
                Text(action, style = MaterialTheme.typography.bodyMedium, color = KiaColors.TextSecondary)
                if (actionChevron) MsIcon(R.drawable.ms_chevron_right, null, tint = KiaColors.TextSecondary, size = 18.dp)
            }
        }
    }
}

/**
 * 리스트 행: [아이콘 박스] 제목 / 부제 ····· [trailing 또는 >]. 기아 앱의 정비·인카페이먼트 행 모양.
 * @param iconBoxed true 면 아이콘을 둥근 서브카드 박스(52dp) 안에 그린다
 */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    @DrawableRes icon: Int? = null,
    iconBoxed: Boolean = true,
    iconTint: Color = KiaColors.TextPrimary,
    showChevron: Boolean = true,
    titleStyle: TextStyle = MaterialTheme.typography.titleMedium,
    verticalPadding: Dp = 16.dp,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val clickable = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(clickable)
            .padding(vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            if (iconBoxed) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(KiaColors.SubCard),
                    contentAlignment = Alignment.Center,
                ) { MsIcon(icon, null, tint = iconTint, size = 26.dp) }
                Spacer(Modifier.width(16.dp))
            } else {
                MsIcon(icon, null, tint = iconTint, size = 22.dp)
                Spacer(Modifier.width(12.dp))
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = titleStyle)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = KiaColors.TextSecondary, modifier = Modifier.padding(top = 2.dp))
            }
        }
        if (trailing != null) {
            trailing()
        } else if (showChevron) {
            MsIcon(R.drawable.ms_chevron_right, null, tint = KiaColors.TextSecondary, size = 22.dp)
        }
    }
}

/** 카드 안 행 사이 구분선 */
@Composable
fun RowDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(KiaColors.Divider),
    )
}

/**
 * 칩(즐겨찾기·카테고리·프리셋). [selected] 면 흰 배경에 검은 글자(지도 카테고리 선택), 아니면 카드색.
 * [accent] 면 라임 테두리/글자(동행 칩).
 */
@Composable
fun KiaChip(
    text: String,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    selected: Boolean = false,
    accent: Boolean = false,
    color: Color = KiaColors.Card,
    onClick: (() -> Unit)? = null,
) {
    val bg = when {
        selected -> KiaColors.TextPrimary
        accent -> KiaColors.AccentDim
        else -> color
    }
    val fg = when {
        selected -> KiaColors.Bg
        accent -> KiaColors.Accent
        else -> KiaColors.TextPrimary
    }
    val border = if (accent) Modifier.border(1.dp, KiaColors.Accent, RoundedCornerShape(12.dp)) else Modifier
    val clickable = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .then(border)
            .then(clickable)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) MsIcon(icon, null, tint = fg, size = 18.dp)
        Text(text, style = MaterialTheme.typography.labelLarge, color = fg)
    }
}

/** 작은 태그("대표 차량", "내 차") — 어두운 녹색 바탕에 라임 글자 */
@Composable
fun KiaTag(text: String, modifier: Modifier = Modifier, color: Color = KiaColors.Accent, background: Color = KiaColors.AccentDim) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

/** 페이저 점(현재 흰색, 나머지 회색) */
@Composable
fun Dots(count: Int, selected: Int, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { i ->
            Box(
                Modifier
                    .size(if (i == selected) 7.dp else 5.dp)
                    .clip(CircleShape)
                    .background(if (i == selected) KiaColors.TextPrimary else KiaColors.TextTertiary),
            )
        }
    }
}

/** 녹색 진행 바(빌트인 캠 "주차 중 녹화") */
@Composable
fun GreenProgress(fraction: Float, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(KiaColors.SubCard),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(KiaColors.ProgressGreen),
        )
    }
}

/** 라임 밑줄 슬라이더(홈 주행가능거리 아래, Driving Insight) — 장식용 */
@Composable
fun AccentSlider(fraction: Float, modifier: Modifier = Modifier, trackColor: Color = KiaColors.SubCard) {
    Box(modifier.height(14.dp), contentAlignment = Alignment.CenterStart) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(trackColor),
        )
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0.02f, 1f))
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(KiaColors.Accent),
        )
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0.02f, 1f)),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Box(
                Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(KiaColors.Accent)
                    .border(3.dp, KiaColors.Bg, CircleShape),
            )
        }
    }
}

/** 큰 기본 버튼(카드색 바탕, 흰 글자) — "시승 예약", "e hi-pass 신청" */
@Composable
fun KiaButton(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = KiaColors.SubCard,
    textColor: Color = KiaColors.TextPrimary,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (enabled) color else color.copy(alpha = 0.5f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = textColor)
    }
}

/** 포인트(라임) 버튼 — 동행 "함께 가기", "보내기" */
@Composable
fun AccentButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) =
    KiaButton(text, modifier, color = KiaColors.Accent, textColor = Color(0xFF0B1A08), enabled = enabled, onClick = onClick)

/** 외곽선 작은 버튼("시작하기", "+ 등록") */
@Composable
fun OutlinePill(text: String, modifier: Modifier = Modifier, @DrawableRes icon: Int? = null, onClick: () -> Unit) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, KiaColors.TextSecondary, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) MsIcon(icon, null, size = 16.dp)
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** 화면 맨 아래 고지 문구(모든 탭) */
@Composable
fun Disclaimer(modifier: Modifier = Modifier) {
    Text(
        stringResource(R.string.disclaimer),
        style = MaterialTheme.typography.labelSmall,
        color = KiaColors.TextTertiary,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
    )
}

/** 빨간 알림 점을 아이콘 오른쪽 위에 */
@Composable
fun BoxScope.RedDot(visible: Boolean) {
    if (visible) {
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = 2.dp, end = 2.dp)
                .size(7.dp)
                .clip(CircleShape)
                .background(KiaColors.Dot),
        )
    }
}

/** 정적 화면을 누르면 "시연용 화면입니다" */
fun demoToast(context: Context, what: String? = null) {
    val base = context.getString(R.string.demo_tap)
    Toast.makeText(context, if (what == null) base else "$what — $base", Toast.LENGTH_SHORT).show()
}
