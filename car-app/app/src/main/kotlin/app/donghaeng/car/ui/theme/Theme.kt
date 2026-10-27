package app.donghaeng.car.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// 동적 색상(dynamicColor)은 쓰지 않는다 — 차량 화면마다 색이 달라지면 데모·캡처가 일관되지 않는다.
private val Navy = Color(0xFF0B3D91)
private val NavyContainer = Color(0xFFD9E3FF)
private val Teal = Color(0xFF00897B)
private val Amber = Color(0xFFFFB300)
private val Red = Color(0xFFD32F2F)

private val LightColors = lightColorScheme(
    primary = Navy,
    onPrimary = Color.White,
    primaryContainer = NavyContainer,
    onPrimaryContainer = Color(0xFF001A43),
    secondary = Teal,
    onSecondary = Color.White,
    tertiary = Amber,
    error = Red,
    background = Color(0xFFF7F8FA),
    onBackground = Color(0xFF1A1C1E),
    surface = Color.White,
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFE1E3E8),
    onSurfaceVariant = Color(0xFF44474E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFAFC6FF),
    onPrimary = Color(0xFF002E6A),
    primaryContainer = Color(0xFF0B3D91),
    onPrimaryContainer = NavyContainer,
    secondary = Color(0xFF4DB6AC),
    onSecondary = Color(0xFF003731),
    tertiary = Amber,
    error = Color(0xFFFFB4AB),
    background = Color(0xFF121417),
    onBackground = Color(0xFFE2E2E6),
    surface = Color(0xFF1B1E22),
    onSurface = Color(0xFFE2E2E6),
    surfaceVariant = Color(0xFF2C2F35),
    onSurfaceVariant = Color(0xFFC4C6CF),
)

// 차량 화면은 멀리서 보므로 기본보다 한 단계 크게
private val CarTypography = Typography(
    headlineLarge = TextStyle(fontSize = 40.sp, lineHeight = 48.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 18.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    labelLarge = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun DongHaengTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = CarTypography,
        content = content,
    )
}
