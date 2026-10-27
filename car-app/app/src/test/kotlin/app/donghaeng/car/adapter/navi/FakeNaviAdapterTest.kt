package app.donghaeng.car.adapter.navi

import app.donghaeng.car.domain.model.NaviEvent
import app.donghaeng.car.domain.model.Place
import app.donghaeng.car.domain.model.RouteState
import app.donghaeng.car.domain.model.WaypointIndex
import app.donghaeng.protocol.LocSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FakeNaviAdapterTest {

    /** 북쪽으로 1 km 직선 (약 1 km / 100 m·s⁻¹ = 10 틱) */
    private val route = listOf(LatLon(37.0, 127.0), LatLon(37.0045, 127.0), LatLon(37.0090, 127.0))
    private val dest = Place(lat = 37.0090, lon = 127.0, name = "종점", poiId = null, address = null)

    @Test
    fun `playback moves along the route at the configured speed and arrives once`() = runTest {
        val navi = FakeNaviAdapter(tickMillis = 1_000, route = route, speedMps = 100.0, destination = dest)
        val events = mutableListOf<NaviEvent>()
        val job = navi.events().onEach { events += it }.launchIn(this)
        runCurrent()

        assertEquals(NaviEvent.NavigationStatus("FREE_DRIVE", false), events.first())
        val first = events.filterIsInstance<NaviEvent.CurrentLocation>().single().fix
        assertEquals(37.0, first.lat, 1e-9)
        assertEquals(LocSource.NAVI, first.source)
        assertEquals(0f, first.heading!!, 1.0f)     // 북쪽

        advanceTimeBy(5_000); runCurrent()
        val fixes = events.filterIsInstance<NaviEvent.CurrentLocation>()
        assertEquals(6, fixes.size)
        assertEquals(37.0045, fixes.last().fix.lat, 0.0003) // 5초 × 100 m ≈ 500 m 북쪽
        assertTrue(events.filterIsInstance<NaviEvent.DestinationArrived>().isEmpty())

        advanceTimeBy(20_000); runCurrent()
        val arrived = events.filterIsInstance<NaviEvent.DestinationArrived>()
        assertEquals(1, arrived.size)
        assertEquals("종점", arrived.single().name)
        assertEquals(dest, arrived.single().destination)
        assertEquals(1, events.filterIsInstance<NaviEvent.RouteStateInfo>().count { it.state == RouteState.ENDED })
        // 종점에 머문다
        val last = events.filterIsInstance<NaviEvent.CurrentLocation>().last().fix
        assertEquals(37.0090, last.lat, 1e-6)
        val trips = events.filterIsInstance<NaviEvent.TripInfo>()
        assertTrue(trips.isNotEmpty())
        assertEquals(0, trips.last().remainDistanceM)
        assertTrue(trips.first().remainDistanceM!! in 950..1050)
        job.cancel()
    }

    @Test
    fun `requestRoute and waypoints emit navi style events and waypoint arrival fires on pass`() = runTest {
        val navi = FakeNaviAdapter(tickMillis = 1_000, route = route, speedMps = 100.0, destination = null)
        val events = mutableListOf<NaviEvent>()
        val job = navi.events().onEach { events += it }.launchIn(this)
        runCurrent()
        assertEquals(null, navi.currentDestination())
        assertTrue(events.filterIsInstance<NaviEvent.TripInfo>().isEmpty())

        val wp = Place(lat = 37.0045, lon = 127.0, name = "중간", poiId = null, address = null)
        navi.requestRouteWithWaypoints(dest, listOf(wp))
        runCurrent()
        assertEquals(dest, navi.currentDestination())
        assertEquals(NaviEvent.DestinationChanged(dest), events.filterIsInstance<NaviEvent.DestinationChanged>().single())
        assertEquals(listOf(wp), events.filterIsInstance<NaviEvent.WaypointChanged>().single().waypoints)
        assertEquals(RouteState.STARTED, events.filterIsInstance<NaviEvent.RouteStateInfo>().single().state)

        advanceTimeBy(8_000); runCurrent()
        val arrived = events.filterIsInstance<NaviEvent.WaypointArrived>().single()
        assertEquals(wp, arrived.waypoint)
        assertEquals(0, arrived.index)
        assertEquals("중간", arrived.name)

        navi.addWaypoint(Place(lat = 37.0, lon = 127.0, name = "추가", poiId = null, address = null), WaypointIndex.LAST)
        runCurrent()
        assertEquals(listOf("중간", "추가"), events.filterIsInstance<NaviEvent.WaypointChanged>().last().waypoints.map { it.name })
        job.cancel()
    }

    @Test
    fun `without a route it drifts from the start point`() = runTest {
        val navi = FakeNaviAdapter(tickMillis = 1_000, route = emptyList(), startLat = 36.35, startLon = 127.38)
        val events = mutableListOf<NaviEvent>()
        val job = navi.events().onEach { events += it }.launchIn(this)
        advanceTimeBy(3_000); runCurrent()
        val fixes = events.filterIsInstance<NaviEvent.CurrentLocation>()
        assertEquals(4, fixes.size)
        assertNotNull(fixes.last().fix.heading)
        assertTrue(fixes.last().fix.lat > fixes.first().fix.lat)
        job.cancel()
    }
}
