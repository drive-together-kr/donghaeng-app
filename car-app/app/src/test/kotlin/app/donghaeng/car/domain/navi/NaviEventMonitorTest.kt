package app.donghaeng.car.domain.navi

import app.donghaeng.car.adapter.navi.FakeNaviAdapter
import app.donghaeng.car.adapter.navi.LatLon
import app.donghaeng.car.adapter.navi.NaviAdapter
import app.donghaeng.car.domain.model.NaviEvent
import app.donghaeng.car.domain.model.Place
import app.donghaeng.car.domain.model.WaypointIndex
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NaviEventMonitorTest {

    private val route = listOf(LatLon(37.0, 127.0), LatLon(37.0090, 127.0))
    private val dest = Place(lat = 37.0090, lon = 127.0, name = "종점", poiId = null, address = null)

    @Test
    fun `counts events per type and keeps masked last fix`() = runTest {
        val navi = FakeNaviAdapter(tickMillis = 1_000, route = route, speedMps = 100.0, destination = dest)
        val monitor = NaviEventMonitor(navi).apply { configureForTest(StandardTestDispatcher(testScheduler), restartDelayMs = 1_000) }
        val shared = mutableListOf<NaviEvent>()
        val sub = monitor.events.onEach { shared += it }.launchIn(this)
        monitor.start()
        advanceTimeBy(15_000); runCurrent()

        val s = monitor.state.value
        assertTrue(s.running)
        assertEquals(0, s.restarts)
        assertEquals(1, s.counts["NavigationStatus"])
        assertEquals(16, s.counts["CurrentLocation"])
        assertEquals(1, s.counts["DestinationArrived"])
        assertTrue((s.counts["TripInfo"] ?: 0) >= 2)
        assertEquals("목적지 종점", s.lastArrival)
        assertEquals("ENDED", s.lastRouteState)
        assertEquals("FREE_DRIVE guiding=false", s.lastStatus)
        assertNotNull(s.lastFixMasked)
        assertFalse("좌표 원문 금지: ${s.lastFixMasked}", s.lastFixMasked!!.contains("37.009"))
        assertEquals("0 m · 0초", s.lastTrip)
        assertEquals(s.totalEvents, shared.size)

        // 요청 이벤트도 같은 스트림으로 흐른다
        navi.addWaypoint(Place(lat = 37.0, lon = 127.0, name = "추가", poiId = null, address = null), WaypointIndex.LAST)
        runCurrent()
        assertEquals(1, monitor.state.value.waypointCount)
        monitor.stop(); sub.cancel()
        assertFalse(monitor.state.value.running)
    }

    @Test
    fun `resubscribes after the stream completes or fails`() = runTest {
        var attempts = 0
        val flaky = object : NaviAdapter {
            override fun events(): Flow<NaviEvent> = flow {
                attempts++
                emit(NaviEvent.NavigationStatus("INACTIVE", false))
                if (attempts == 1) throw IllegalStateException("boom")
                // 2번째: 정상 종료, 3번째 이후: 계속 살아 있음
                if (attempts >= 3) kotlinx.coroutines.awaitCancellation()
            }
            override suspend fun requestRoute(dest: Place) = Unit
            override suspend fun requestRouteWithWaypoints(dest: Place, waypoints: List<Place>) = Unit
            override suspend fun addWaypoint(place: Place, index: WaypointIndex) = Unit
            override suspend fun currentDestination(): Place? = null
            override suspend fun showNavigation() = Unit
        }
        val monitor = NaviEventMonitor(flaky).apply { configureForTest(StandardTestDispatcher(testScheduler), restartDelayMs = 1_000) }
        monitor.start()
        runCurrent()
        assertEquals(1, attempts)
        assertTrue(monitor.state.value.lastError!!.contains("boom"))
        advanceTimeBy(1_001); runCurrent()
        assertEquals(2, attempts)
        advanceTimeBy(1_001); runCurrent()
        assertEquals(3, attempts)
        assertEquals(2, monitor.state.value.restarts)
        assertEquals(3, monitor.state.value.counts["NavigationStatus"])
        assertTrue(monitor.state.value.running)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(3, attempts)   // 살아 있는 동안은 재구독 안 함
        monitor.stop()
    }
}
