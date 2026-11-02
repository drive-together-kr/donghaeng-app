package app.donghaeng.car.ui.theme

import androidx.compose.ui.graphics.Color
import app.donghaeng.car.feature.drive.DriveTone
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * 글자 색 대비(E041·3-3, WCAG AA 본문 4.5:1) — 주간 강조(tertiary)·연결됨(secondary)·대시보드 주간 톤, 다크 톤.
 * 예전 주간 tertiary #FFB300 은 바탕 대비 1.7:1, 톤 #FF9F1C·#2ECC71 은 약 2:1 이었다.
 */
class ThemeContrastTest {

    private fun channel(c: Float): Double = if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    private fun lum(c: Color): Double = 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
    private fun contrast(a: Color, b: Color): Double {
        val (hi, lo) = listOf(lum(a), lum(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun assertAA(name: String, fg: Color, bg: Color) {
        val r = contrast(fg, bg)
        assertTrue("$name 대비 ${"%.2f".format(r)} ≥ 4.5", r >= 4.5)
    }

    @Test
    fun `light theme text colors meet AA on the background and surface`() {
        listOf(DhPalette.lightBackground, DhPalette.lightSurface).forEach { bg ->
            assertAA("tertiary", DhPalette.lightTertiary, bg)
            assertAA("secondary", DhPalette.lightSecondary, bg)
            assertAA("primary", DhPalette.lightPrimary, bg)
            assertAA("error", DhPalette.lightError, bg)
            assertAA("onSurfaceVariant", DhPalette.lightOnSurfaceVariant, bg)
            assertAA("tone danger", DriveTone.light.danger, bg)
            assertAA("tone caution", DriveTone.light.caution, bg)
            assertAA("tone info", DriveTone.light.info, bg)
        }
    }

    @Test
    fun `dark theme text colors meet AA on the background and surface`() {
        listOf(DhPalette.darkBackground, DhPalette.darkSurface).forEach { bg ->
            assertAA("tertiary", DhPalette.darkTertiary, bg)
            assertAA("secondary", DhPalette.darkSecondary, bg)
            assertAA("primary", DhPalette.darkPrimary, bg)
            assertAA("onSurfaceVariant", DhPalette.darkOnSurfaceVariant, bg)
            assertAA("tone danger", DriveTone.dark.danger, bg)
            assertAA("tone caution", DriveTone.dark.caution, bg)
            assertAA("tone info", DriveTone.dark.info, bg)
        }
    }
}
