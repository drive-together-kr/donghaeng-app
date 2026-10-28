package app.donghaeng.car.surface

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 패널 유지(재시작·백오프), 오버레이 권한 주기 확인(그룹 있을 때만), [내비 보기] 경로 선택 */
@OptIn(ExperimentalCoroutinesApi::class)
class SurfaceSignalsTest {

    @Test
    fun `keeper starts the panel after a short delay and stops it when it should hide`() = runTest {
        val visible = MutableStateFlow(false)
        val shown = MutableStateFlow(false)
        var starts = 0
        var stops = 0
        val keeper = PanelKeeper(visible, shown, start = { starts++ }, stop = { stops++ })
        backgroundScope.launch { keeper.run() }
        runCurrent()
        assertEquals(0, starts)

        visible.value = true; runCurrent()
        advanceTimeBy(PanelKeeper.SHOW_DELAY_MS - 1); runCurrent()
        assertEquals("깜빡임 방지 지연 전", 0, starts)
        advanceTimeBy(1); runCurrent()
        assertEquals(1, starts)
        shown.value = true; runCurrent()
        advanceTimeBy(60_000); runCurrent()
        assertEquals("창이 붙으면 다시 시작하지 않는다", 1, starts)

        val stopsBefore = stops
        visible.value = false; runCurrent()
        assertTrue(stops > stopsBefore)
        shown.value = false; runCurrent()
        advanceTimeBy(60_000); runCurrent()
        assertEquals("숨김 상태에선 시작하지 않는다", 1, starts)
    }

    @Test
    fun `keeper retries with backoff while the window never attaches and records failures`() = runTest {
        val visible = MutableStateFlow(true)
        val shown = MutableStateFlow(false)
        var starts = 0
        val keeper = PanelKeeper(visible, shown, start = { starts++; if (starts == 1) throw IllegalStateException("background start not allowed") }, stop = {})
        backgroundScope.launch { keeper.run() }
        advanceTimeBy(PanelKeeper.SHOW_DELAY_MS); runCurrent()
        assertEquals(1, starts)
        assertEquals("start IllegalStateException", keeper.status.value.lastFailure)

        advanceTimeBy(PanelKeeper.RETRY_BASE_MS); runCurrent()
        assertEquals("3초 뒤 재시도", 2, starts)
        advanceTimeBy(PanelKeeper.RETRY_BASE_MS * 2 - 1); runCurrent()
        assertEquals("다음은 6초 뒤", 2, starts)
        advanceTimeBy(1); runCurrent()
        assertEquals(3, starts)
        assertEquals(2, keeper.status.value.retries)
        assertTrue(keeper.status.value.lastFailure!!.startsWith("not-shown"))

        // 서비스가 붙었다가 시스템이 정리해 떨어지면 다시 시작한다
        shown.value = true; runCurrent()
        advanceTimeBy(120_000); runCurrent()
        assertEquals(3, starts)
        shown.value = false; runCurrent()
        advanceTimeBy(PanelKeeper.SHOW_DELAY_MS); runCurrent()
        assertEquals(4, starts)

        // 백오프 상한: 충분히 지난 뒤에는 30초마다 한 번
        repeat(10) { advanceTimeBy(PanelKeeper.RETRY_MAX_MS); runCurrent() }
        val before = starts
        advanceTimeBy(PanelKeeper.RETRY_MAX_MS); runCurrent()
        assertEquals(before + 1, starts)
    }

    @Test
    fun `overlay permission is polled every 5 s only while a group exists, foreground rechecks always work`() = runTest {
        val active = MutableStateFlow(false)
        var allowed = false
        val m = OverlayPermissionMonitor(check = { allowed }, active = active, dispatcher = StandardTestDispatcher(testScheduler))
        m.start(); runCurrent()
        try {
            assertEquals("시작 때 한 번", 1, m.checks)
            advanceTimeBy(60_000); runCurrent()
            assertEquals("그룹 없으면 주기 확인 안 함", 1, m.checks)
            m.recheck()
            assertEquals("포그라운드 복귀 확인은 항상", 2, m.checks)

            active.value = true; runCurrent()
            assertEquals("그룹이 생기면 바로 한 번", 3, m.checks)
            allowed = true
            advanceTimeBy(OverlayPermissionMonitor.INTERVAL_MS); runCurrent()
            assertEquals(4, m.checks)
            assertTrue(m.canDraw.value)
            advanceTimeBy(OverlayPermissionMonitor.INTERVAL_MS * 2); runCurrent()
            assertEquals(6, m.checks)

            active.value = false; runCurrent()
            advanceTimeBy(60_000); runCurrent()
            assertEquals(6, m.checks)
        } finally { m.stop() }
    }

    @Test
    fun `navi button prefers the stock navi and never falls into another maps app or a chooser on pleos`() {
        val stock = NaviLauncher.STOCK_NAVI_PACKAGES.first()
        assertEquals(NaviLauncher.Route.STOCK_PACKAGE, NaviLauncher.choose(stockLaunchable = true, mapsResolvedPackage = "com.other.maps", pleosBuild = true))
        assertEquals(NaviLauncher.Route.MAPS_SELECTOR, NaviLauncher.choose(stockLaunchable = false, mapsResolvedPackage = stock, pleosBuild = true))
        assertEquals("pleos 에서 다른 지도 앱 → NaviHelper", NaviLauncher.Route.NAVI_HELPER, NaviLauncher.choose(false, "com.other.maps", pleosBuild = true))
        assertEquals(NaviLauncher.Route.NAVI_HELPER, NaviLauncher.choose(false, null, pleosBuild = true))
        assertEquals("fake 빌드(일반 에뮬레이터)는 한 앱으로 풀리면 그 지도 앱", NaviLauncher.Route.MAPS_SELECTOR, NaviLauncher.choose(false, "com.google.android.apps.maps", pleosBuild = false))
        assertEquals("선택 창(ResolverActivity) → 폴백", NaviLauncher.Route.NAVI_HELPER, NaviLauncher.choose(false, "android", pleosBuild = false))
        assertEquals(NaviLauncher.Route.NAVI_HELPER, NaviLauncher.choose(false, null, pleosBuild = false))
        assertFalse(NaviLauncher.STOCK_NAVI_PACKAGES.isEmpty())
    }
}
