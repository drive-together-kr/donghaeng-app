package app.donghaeng.companion.demo.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 공식 앱 느낌의 짙은 남색 — 특정 브랜드 자산은 쓰지 않는다
private val Navy = Color(0xFF0B3B74)
private val NavyLight = Color(0xFF9DBBE8)
private val Accent = Color(0xFF2E7DFF)

private val LightColors = lightColorScheme(
    primary = Navy,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E3FF),
    onPrimaryContainer = Color(0xFF001B3F),
    secondary = Accent,
    onSecondary = Color.White,
    surface = Color(0xFFFBFCFF),
    background = Color(0xFFF4F6FA),
)

private val DarkColors = darkColorScheme(
    primary = NavyLight,
    onPrimary = Color(0xFF002A5C),
    primaryContainer = Color(0xFF1E4A86),
    onPrimaryContainer = Color(0xFFD6E3FF),
    secondary = Accent,
)

@Composable
fun DemoHostTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
