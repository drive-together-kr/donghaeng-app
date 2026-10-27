package app.donghaeng.companion.demo.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.donghaeng.companion.demo.R

/**
 * 기아 앱 재현용 디자인 토큰 (스크린샷에서 추출, 2026-10-27).
 * 브랜드 자산(Kia Signature 폰트·로고·렌더 이미지)은 쓰지 않는다 — 폰트는 Pretendard(OFL), 아이콘은 Material Symbols Outlined.
 */
object KiaColors {
    /** 배경 — 아주 어두운 네이비 */
    val Bg = Color(0xFF08131B)

    /** 카드 */
    val Card = Color(0xFF18222B)

    /** 카드 안 서브카드 · 구분선 */
    val SubCard = Color(0xFF2A343D)
    val Divider = Color(0xFF2A343D)

    /** 포인트 라임 그린 */
    val Accent = Color(0xFF8CC63F)

    /** 포인트 색의 어두운 배경(태그·지점 칩) */
    val AccentDim = Color(0xFF1B3A25)

    /** 진행 바 녹색(빌트인 캠 녹화 바) */
    val ProgressGreen = Color(0xFF1FB36B)

    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xFF8A949C)
    val TextTertiary = Color(0xFF5E6871)

    /** 알림 빨간 점 */
    val Dot = Color(0xFFE5484D)

    /** 지도 위 파란 마커(내 차) */
    val MapBlue = Color(0xFF2B7FFF)

    /** 베이지 배너(이벤트 카드) */
    val Beige = Color(0xFFB9A68C)
    val BeigeText = Color(0xFF1E1A14)
}

val PretendardFamily: FontFamily = FontFamily(
    Font(R.font.pretendard_regular, FontWeight.Normal),
    Font(R.font.pretendard_medium, FontWeight.Medium),
    Font(R.font.pretendard_semibold, FontWeight.SemiBold),
    Font(R.font.pretendard_bold, FontWeight.Bold),
)

private fun style(size: Int, weight: FontWeight, lineHeight: Int = (size * 1.4).toInt(), letterSpacing: Float = 0f) = TextStyle(
    fontFamily = PretendardFamily,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = letterSpacing.sp,
    color = KiaColors.TextPrimary,
)

/** Material3 Typography 에 기아 앱의 크기 체계를 입힌다 */
val KiaTypography = Typography(
    // 홈 주행가능거리 "999" 같은 초대형 숫자
    displayLarge = style(64, FontWeight.Bold, 68, -1.5f),
    displayMedium = style(44, FontWeight.Bold, 48, -1f),
    // 화면 제목(상단바 "쏘렌토", "마이카")
    headlineMedium = style(26, FontWeight.Bold, 32, -0.5f),
    headlineSmall = style(22, FontWeight.Bold, 28, -0.3f),
    // 섹션 제목("서비스 바로가기", "Driving Insight")
    titleLarge = style(19, FontWeight.Bold, 26, -0.2f),
    // 카드 제목(리스트 행 제목)
    titleMedium = style(17, FontWeight.SemiBold, 24, -0.1f),
    titleSmall = style(15, FontWeight.SemiBold, 22),
    bodyLarge = style(16, FontWeight.Normal, 24),
    bodyMedium = style(14, FontWeight.Normal, 20),
    bodySmall = style(13, FontWeight.Normal, 18),
    labelLarge = style(15, FontWeight.Medium, 20),
    labelMedium = style(13, FontWeight.Medium, 18),
    labelSmall = style(11, FontWeight.Medium, 14),
)

val KiaShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** 카드 모서리(약 24dp), 서브카드 16dp, 칩 pill */
object KiaDimens {
    val ScreenPadding = 20.dp
    val CardRadius = 24.dp
    val SubCardRadius = 16.dp
    val CardPadding = 20.dp
    val SectionGap = 28.dp
    val CardGap = 12.dp
}

private val KiaColorScheme: ColorScheme = darkColorScheme(
    primary = KiaColors.Accent,
    onPrimary = Color(0xFF0B1A08),
    secondary = KiaColors.Accent,
    onSecondary = Color(0xFF0B1A08),
    background = KiaColors.Bg,
    onBackground = KiaColors.TextPrimary,
    surface = KiaColors.Bg,
    onSurface = KiaColors.TextPrimary,
    surfaceVariant = KiaColors.Card,
    onSurfaceVariant = KiaColors.TextSecondary,
    surfaceContainer = KiaColors.Card,
    surfaceContainerHigh = KiaColors.SubCard,
    surfaceContainerHighest = KiaColors.SubCard,
    surfaceContainerLow = KiaColors.Card,
    outline = KiaColors.Divider,
    outlineVariant = KiaColors.Divider,
    error = KiaColors.Dot,
    onError = Color.White,
)

val LocalKiaColors = staticCompositionLocalOf { KiaColors }

@Composable
fun KiaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = KiaColorScheme,
        typography = KiaTypography,
        shapes = KiaShapes,
        content = content,
    )
}
