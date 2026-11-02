package app.donghaeng.car.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
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

    val darkBackground = Color(0xFF121417)
    val darkSurface = Color(0xFF1B1E22)
    val darkOnSurface = Color(0xFFE2E2E6)
    val darkOnSurfaceVariant = Color(0xFFC4C6CF)
    val darkTertiary = Amber
    val darkSecondary = Color(0xFF4DB6AC)
    val darkPrimary = Color(0xFFAFC6FF)
    val darkError = Color(0xFFFFB4AB)
    val darkCard = Color(0xFF2C2F35)
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
    primary = DhPalette.darkPrimary,
    onPrimary = Color(0xFF002E6A),
    primaryContainer = Color(0xFF0B3D91),
    onPrimaryContainer = NavyContainer,
    secondary = DhPalette.darkSecondary,
    onSecondary = Color(0xFF003731),
    tertiary = DhPalette.darkTertiary,
    onTertiary = Color(0xFF2B1D00),
    tertiaryContainer = Color(0xFF5C4100),
    onTertiaryContainer = Color(0xFFFFE6A8),
    error = DhPalette.darkError,
    background = DhPalette.darkBackground,
    onBackground = DhPalette.darkOnSurface,
    surface = DhPalette.darkSurface,
    onSurface = DhPalette.darkOnSurface,
    surfaceVariant = Color(0xFF2C2F35),
    onSurfaceVariant = DhPalette.darkOnSurfaceVariant,
    surfaceContainerLowest = Color(0xFF0E1013),
    surfaceContainerLow = Color(0xFF16191D),
    surfaceContainer = Color(0xFF1B1E22),
    surfaceContainerHigh = Color(0xFF23262B),
    surfaceContainerHighest = DhPalette.darkCard,
)

/**
 * 차량 화면 글자(3-1): Pleos 2560×1440 은 1 dp = 1 px 라 기본 크기가 순정 화면보다 눈에 띄게 작았다(버튼 글자 15 px vs 순정 24 px).
 * 예전 값의 약 1.35배 — bodyMedium 16→22, bodyLarge 18→24, labelLarge 18→24, titleMedium 20→26, titleLarge 24→30, headline 30→36·40→48.
 * 정차 대시보드·플로팅 패널은 sp 를 직접 지정하므로 이 값의 영향을 받지 않는다.
 */
private val CarTypography = Typography(
    headlineLarge = TextStyle(fontSize = 48.sp, lineHeight = 56.sp, fontWeight = FontWeight.Bold),
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
)

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
)

/**
 * 차량 기준 최소 터치 대상(E037, AAOS 주행 가이드 76 × 76 dp). M3 버튼·칩·스위치·TextButton 은 보이는 크기는 그대로 두고
 * 누를 수 있는 영역과 자리를 이 크기로 넓힌다(`minimumInteractiveComponentSize`).
 */
val MinTouchTarget = 76.dp

@Composable
fun DongHaengTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = CarTypography,
    ) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides MinTouchTarget, content = content)
    }
}
