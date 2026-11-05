package app.donghaeng.car.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import app.donghaeng.car.R
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// 동적 색상(dynamicColor)은 쓰지 않는다 — 차량 화면마다 색이 달라지면 데모·캡처가 일관되지 않는다.
private val Navy = Color(0xFF0B3D91)
private val NavyContainer = Color(0xFFD9E3FF)
private val Amber = Color(0xFFFFB300)

/**
 * 색 대비 원칙(E041, WCAG AA): 글자로 쓰는 색은 바탕(#F7F8FA·#FFFFFF / 다크 #121417·#1B1E22) 대비 본문 4.5:1 이상.
 * - 주간 tertiary(강조 글자) = 진한 호박색 #8A5A00(바탕 대비 ≈ 5.6:1). 밝은 호박색 #FFB300 은 다크 전용·컨테이너 바탕용(흰 바탕 글자로는 1.7:1).
 * - 주간 secondary(연결됨 등) = #00695C(≈ 6.3:1) — 예전 #00897B 는 4.1:1.
 * - 주간 error = #B3261E — 기본 Card 바탕(surfaceContainerHighest #E1E3E8) 위 글자("초대가 만료됐어요"·이벤트 띠 ALERT)도 4.5:1 이상.
 *   예전 #D32F2F 는 카드 바탕에서 ≈ 3.9:1 이었다.
 * - 글자는 바탕·surface 뿐 아니라 기본 Card 바탕([lightCard]/[darkCard] = surfaceContainerHighest) 위에서도 4.5:1 이상.
 * - 카드 바탕(surfaceContainer*)은 M3 기본 보라 대신 무채색 회색 단계(설정·소식·진단 카드와 대시보드 카드가 같은 색 언어).
 * 대비 값은 `ThemeContrastTest` 가 고정한다.
 */
internal object DhPalette {
    val lightBackground = Color(0xFFF7F8FA)
    val lightSurface = Color.White
    val lightOnSurface = Color(0xFF1A1C1E)
    val lightOnSurfaceVariant = Color(0xFF44474E)
    val lightTertiary = Color(0xFF8A5A00)
    val lightSecondary = Color(0xFF00695C)
    val lightPrimary = Navy
    val lightError = Color(0xFFB3261E)
    /** 기본 Card 바탕(surfaceContainerHighest) — 글자 대비 검증용 */
    val lightCard = Color(0xFFE1E3E8)

    // 다크 = '밤 고속도로'(2026-11-05 디자인 개편, docs/05 §5.0): 아스팔트 바탕 · 노견 카드 · 차선 흰 글자 ·
    // 고속도로 표지 초록(주 버튼·목적지 표지) · 중앙선 호박색(무전·강조) · 브레이크등 빨강(위험). 앱 화면은 항상 이 테마.
    val darkBackground = Color(0xFF10151B)   // 아스팔트
    val darkSurface = Color(0xFF18202A)      // 노견(카드)
    val darkOnSurface = Color(0xFFEEF2F6)    // 차선 흰색
    val darkOnSurfaceVariant = Color(0xFFA6B1BE)
    val darkTertiary = Color(0xFFF4B63C)     // 중앙선 호박색
    val darkSecondary = Color(0xFF4FD49B)    // 연결됨·정상
    val darkPrimary = Color(0xFF6FD8A8)      // 표지 초록(글자용 밝은 톤)
    val darkError = Color(0xFFFF8A80)
    val darkCard = Color(0xFF222B36)
    /** 고속도로 표지 초록 — 주 버튼·목적지 표지 바탕(흰 글자 대비 ≥ 4.5:1) */
    val signGreen = Color(0xFF0D7A50)
    val onSignGreen = Color.White
    /** 카드 가장자리·구분선(노견 경계) */
    val seam = Color(0xFF2D3845)
}

private val LightColors = lightColorScheme(
    primary = DhPalette.lightPrimary,
    onPrimary = Color.White,
    primaryContainer = NavyContainer,
    onPrimaryContainer = Color(0xFF001A43),
    secondary = DhPalette.lightSecondary,
    onSecondary = Color.White,
    tertiary = DhPalette.lightTertiary,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE6A8),
    onTertiaryContainer = Color(0xFF2B1D00),
    error = DhPalette.lightError,
    background = DhPalette.lightBackground,
    onBackground = DhPalette.lightOnSurface,
    surface = DhPalette.lightSurface,
    onSurface = DhPalette.lightOnSurface,
    surfaceVariant = Color(0xFFE1E3E8),
    onSurfaceVariant = DhPalette.lightOnSurfaceVariant,
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF3F4F7),
    surfaceContainer = Color(0xFFEEF0F4),
    surfaceContainerHigh = Color(0xFFE8EAEE),
    surfaceContainerHighest = DhPalette.lightCard,
)

private val DarkColors = darkColorScheme(
    // primary = 밝은 표지 초록(글자·테두리·선택 표시 — 어두운 바탕 대비 ≥ 4.5:1). 깊은 표지 초록 바탕에 흰 글자는 주 버튼(DhPrimaryButton)·목적지 표지에만
    primary = DhPalette.darkPrimary,
    onPrimary = Color(0xFF00301C),
    primaryContainer = Color(0xFF0F3B2A),
    onPrimaryContainer = Color(0xFFB8F0D5),
    secondary = DhPalette.darkSecondary,
    onSecondary = Color(0xFF00301C),
    tertiary = DhPalette.darkTertiary,
    onTertiary = Color(0xFF241800),
    tertiaryContainer = Color(0xFF4A3608),
    onTertiaryContainer = Color(0xFFFFE2A6),
    error = DhPalette.darkError,
    errorContainer = Color(0xFF4A1715),
    onErrorContainer = Color(0xFFFFDAD6),
    background = DhPalette.darkBackground,
    onBackground = DhPalette.darkOnSurface,
    surface = DhPalette.darkSurface,
    onSurface = DhPalette.darkOnSurface,
    surfaceVariant = DhPalette.darkCard,
    onSurfaceVariant = DhPalette.darkOnSurfaceVariant,
    outline = Color(0xFF52606F),
    outlineVariant = DhPalette.seam,
    surfaceContainerLowest = Color(0xFF0C1015),
    surfaceContainerLow = Color(0xFF151C24),
    surfaceContainer = DhPalette.darkSurface,
    surfaceContainerHigh = Color(0xFF1E2731),
    surfaceContainerHighest = DhPalette.darkCard,
)

/** 글꼴: Pretendard(SIL OFL 1.1 — car-app/third_party/Pretendard-OFL.txt). 한 가족을 굵기로만 나눈다 */
val Pretendard = FontFamily(
    Font(R.font.pretendard_regular, FontWeight.Normal),
    Font(R.font.pretendard_medium, FontWeight.Medium),
    Font(R.font.pretendard_semibold, FontWeight.SemiBold),
    Font(R.font.pretendard_bold, FontWeight.Bold),
    Font(R.font.pretendard_extrabold, FontWeight.ExtraBold),
)

/** 숫자(도착 시각·거리)는 고정폭 숫자(tnum) — 값이 바뀌어도 글자 폭이 흔들리지 않게 */
const val TABULAR_NUMS = "tnum"

private fun Typography.withFamily(f: FontFamily) = Typography(
    displayLarge = displayLarge.copy(fontFamily = f), displayMedium = displayMedium.copy(fontFamily = f), displaySmall = displaySmall.copy(fontFamily = f),
    headlineLarge = headlineLarge.copy(fontFamily = f), headlineMedium = headlineMedium.copy(fontFamily = f), headlineSmall = headlineSmall.copy(fontFamily = f),
    titleLarge = titleLarge.copy(fontFamily = f), titleMedium = titleMedium.copy(fontFamily = f), titleSmall = titleSmall.copy(fontFamily = f),
    bodyLarge = bodyLarge.copy(fontFamily = f), bodyMedium = bodyMedium.copy(fontFamily = f), bodySmall = bodySmall.copy(fontFamily = f),
    labelLarge = labelLarge.copy(fontFamily = f), labelMedium = labelMedium.copy(fontFamily = f), labelSmall = labelSmall.copy(fontFamily = f),
)

/** 모서리: 칩·작은 버튼 12, 버튼 16, 카드 22, 큰 시트 28 — 한 단계 안에서는 한 가지 반경만 */
private val CarShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * 차량 화면 글자(3-1): Pleos 2560×1440 은 1 dp = 1 px 라 기본 크기가 순정 화면보다 눈에 띄게 작았다(버튼 글자 15 px vs 순정 24 px).
 * 예전 값의 약 1.35배 — bodyMedium 16→22, bodyLarge 18→24, labelLarge 18→24, titleMedium 20→26, titleLarge 24→30, headline 30→36·40→48.
 * 정차 대시보드·플로팅 패널은 sp 를 직접 지정하므로 이 값의 영향을 받지 않는다.
 */
private val CarTypography = Typography(
    displayMedium = TextStyle(fontSize = 64.sp, lineHeight = 72.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp),
    headlineLarge = TextStyle(fontSize = 48.sp, lineHeight = 56.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontSize = 36.sp, lineHeight = 44.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 24.sp, lineHeight = 32.sp),
    bodyMedium = TextStyle(fontSize = 22.sp, lineHeight = 30.sp),
    bodySmall = TextStyle(fontSize = 18.sp, lineHeight = 24.sp),
    labelLarge = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
).withFamily(Pretendard)

/** 진단 화면(개발자용 표)은 예전 크기를 지역적으로 유지한다 — 한 화면에 많은 줄을 담아 캡처 한 장으로 붙여넣기 쉽게 */
val CompactTypography = Typography(
    headlineLarge = TextStyle(fontSize = 40.sp, lineHeight = 48.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 18.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    labelLarge = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
).withFamily(Pretendard)

/**
 * 차량 기준 최소 터치 대상(E037, AAOS 주행 가이드 76 × 76 dp). M3 버튼·칩·스위치·TextButton 은 보이는 크기는 그대로 두고
 * 누를 수 있는 영역과 자리를 이 크기로 넓힌다(`minimumInteractiveComponentSize`).
 */
val MinTouchTarget = 76.dp

/**
 * 앱 화면은 항상 다크('밤 고속도로') — 시스템 주야간 설정과 무관하게 같은 모습(데모·캡처 일관성, 주행 중 눈부심 감소).
 * 순정 내비 위 플로팅 패널만 내비 주야간에 맞춰 [darkTheme] 를 넘긴다.
 */
@Composable
fun DongHaengTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = CarTypography,
        shapes = CarShapes,
    ) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides MinTouchTarget, content = content)
    }
}
