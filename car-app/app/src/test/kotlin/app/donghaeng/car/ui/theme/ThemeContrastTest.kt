package app.donghaeng.car.ui.theme

import androidx.compose.ui.graphics.Color
import app.donghaeng.car.feature.drive.DriveTone
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * 글자 색 대비(E041·3-3, WCAG AA 본문 4.5:1) — 주간 강조(tertiary)·연결됨(secondary)·대시보드 주간 톤, 다크 톤.
 * 바탕·surface 와 기본 Card 바탕(surfaceContainerHighest) 둘 다 본다.
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

    /** 3:1 — 아이콘 같은 글자가 아닌 요소(WCAG 1.4.11) */
    private fun assertNonText(name: String, fg: Color, bg: Color) {
        val r = contrast(fg, bg)
        assertTrue("$name 대비 ${"%.2f".format(r)} ≥ 3", r >= 3.0)
    }

    @Test
    fun `text colors also meet AA on the default card background`() {
        // 기본 Card 바탕(surfaceContainerHighest) — 그룹 관리 초대 카드의 "초대가 만료됐어요"(error)·이벤트 띠 ALERT 줄 등
        val light = DhPalette.lightCard
        assertAA("light error on card", DhPalette.lightError, light)
        assertAA("light tertiary on card", DhPalette.lightTertiary, light)
        assertAA("light secondary on card", DhPalette.lightSecondary, light)
        assertAA("light primary on card", DhPalette.lightPrimary, light)
        assertAA("light onSurfaceVariant on card", DhPalette.lightOnSurfaceVariant, light)
        assertAA("light onSurface on card", DhPalette.lightOnSurface, light)
        val dark = DhPalette.darkCard
        assertAA("dark error on card", DhPalette.darkError, dark)
        assertAA("dark tertiary on card", DhPalette.darkTertiary, dark)
        assertAA("dark secondary on card", DhPalette.darkSecondary, dark)
        assertAA("dark primary on card", DhPalette.darkPrimary, dark)
        assertAA("dark onSurfaceVariant on card", DhPalette.darkOnSurfaceVariant, dark)
        assertAA("dark onSurface on card", DhPalette.darkOnSurface, dark)
        // 대시보드 톤은 카드 바탕(소식 화면)에서 아이콘으로만 쓴다 — 3:1
        listOf(DriveTone.light to light, DriveTone.dark to dark).forEach { (t, bg) ->
            assertNonText("tone danger icon on card", t.danger, bg)
            assertNonText("tone caution icon on card", t.caution, bg)
            assertNonText("tone info icon on card", t.info, bg)
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
