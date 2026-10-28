package app.donghaeng.car.surface

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 표면 모드 결정·패널 표시·크기/위치 규칙 (docs/05 §5.2) */
class SurfacePolicyTest {

    @Test
    fun `mode is OFF without a group, PANEL with overlay permission, CARDS_ONLY without`() {
        assertEquals(SurfaceMode.OFF, SurfacePolicy.mode(hasSession = false, canDrawOverlays = true))
        assertEquals(SurfaceMode.OFF, SurfacePolicy.mode(hasSession = false, canDrawOverlays = false))
        assertEquals(SurfaceMode.PANEL, SurfacePolicy.mode(hasSession = true, canDrawOverlays = true))
        assertEquals(SurfaceMode.CARDS_ONLY, SurfacePolicy.mode(hasSession = true, canDrawOverlays = false))
    }

    @Test
    fun `panel shows only in PANEL mode while the app screen is not in front`() {
        assertTrue(SurfacePolicy.panelVisible(SurfaceMode.PANEL, appVisible = false))
        assertFalse("동행 화면이 앞이면 대시보드가 같은 정보를 보여 준다", SurfacePolicy.panelVisible(SurfaceMode.PANEL, appVisible = true))
        assertFalse(SurfacePolicy.panelVisible(SurfaceMode.CARDS_ONLY, appVisible = false))
        assertFalse(SurfacePolicy.panelVisible(SurfaceMode.OFF, appVisible = false))
    }

    @Test
    fun `pleos landscape - navi is two thirds, panel width at most 22 percent of it and height at most 30 percent`() {
        // Pleos AVD 2560×1440, density 1.0 (1 dp = 1 px)
        assertEquals(1706, PanelLayout.naviWidthPx(2560, 1440))
        assertEquals(854, PanelLayout.naviLeftPx(2560, 1440))
        val g = PanelLayout.geometry(2560, 1440, 1f)
        assertTrue("폭 ${g.widthPx} ≤ 내비 22% (375.3)", g.widthPx <= (1706 * 0.22f).toInt())
        assertEquals(375, g.widthPx)
        assertEquals(432, g.maxHeightPx)
        assertEquals(1f, g.scale, 0.01f)
    }

    @Test
    fun `portrait uses the full width as navi and narrower displays scale the panel down but never exceed the limits`() {
        assertEquals(1440, PanelLayout.naviWidthPx(1440, 2560))
        val portrait = PanelLayout.geometry(1440, 2560, 1f)
        assertEquals((1440 * 0.22f).toInt(), portrait.widthPx)   // 316 px
        assertTrue(portrait.scale < 1f)
        // 고밀도(2x) 같은 화면: px 한도는 같고 배율만 dp 기준
        val dense = PanelLayout.geometry(2560, 1440, 2f)
        assertTrue(dense.widthPx <= (1706 * 0.22f).toInt())
        assertTrue(dense.maxHeightPx <= (1440 * 0.3f).toInt())
        assertEquals(PanelLayout.MIN_SCALE, dense.scale, 0.001f)
    }

    @Test
    fun `default top end offsets match the approved design and nearest corner follows the drop point`() {
        assertEquals(110 to 190, PanelLayout.offsetPx(PanelCorner.TOP_END, 2560, 1440, 1f))
        assertEquals(854 + 24 to 360, PanelLayout.offsetPx(PanelCorner.TOP_START, 2560, 1440, 1f))
        assertEquals(220 to 380, PanelLayout.offsetPx(PanelCorner.TOP_END, 2560, 1440, 2f))
        // 내비 가운데(854 + 853 = 1707) 기준 좌우, 디스플레이 가운데(720) 기준 위아래
        assertEquals(PanelCorner.TOP_END, PanelLayout.nearestCorner(2300f, 300f, 2560, 1440))
        assertEquals(PanelCorner.BOTTOM_END, PanelLayout.nearestCorner(1800f, 1000f, 2560, 1440))
        assertEquals(PanelCorner.TOP_START, PanelLayout.nearestCorner(1200f, 200f, 2560, 1440))
        assertEquals(PanelCorner.BOTTOM_START, PanelLayout.nearestCorner(1000f, 900f, 2560, 1440))
    }

    @Test
    fun `corner parsing accepts names and short forms`() {
        assertEquals(PanelCorner.BOTTOM_START, PanelCorner.parse("bottom_start"))
        assertEquals(PanelCorner.TOP_END, PanelCorner.parse("TR"))
        assertEquals(PanelCorner.BOTTOM_END, PanelCorner.parse("bottom_right"))
        assertNull(PanelCorner.parse("middle"))
        assertNull(PanelCorner.parse(null))
        val store = InMemoryPanelPositionStore()
        assertEquals(PanelCorner.TOP_END, store.load())
        store.save(PanelCorner.BOTTOM_END)
        assertEquals(PanelCorner.BOTTOM_END, store.load())
    }
}
