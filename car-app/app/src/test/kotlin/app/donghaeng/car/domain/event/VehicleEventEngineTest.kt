package app.donghaeng.car.domain.event

import app.donghaeng.car.domain.model.Fix
import app.donghaeng.car.domain.model.NaviEvent
import app.donghaeng.car.domain.session.GroupSession
import app.donghaeng.car.domain.vehicle.VehicleSignalState
import app.donghaeng.protocol.GroupTopics
import app.donghaeng.protocol.LocSource
import app.donghaeng.protocol.Place
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VehicleEventEngineTest {

    private val session = MutableStateFlow<GroupSession?>(null)
    private val vehicle = MutableStateFlow(VehicleSignalState())
    private val navi = MutableSharedFlow<NaviEvent>(extraBufferCapacity = 8)
    private val published = mutableListOf<Triple<String, String, JsonObject>>()
    private var fix: Fix? = null

    private fun engine(scheduler: kotlinx.coroutines.test.TestCoroutineScheduler) = VehicleEventEngine(
        session = session,
        vehicleId = { "veh_T" },
        latestFix = { fix?.copy(ts = scheduler.currentTime) },
        fallbackSpeedKph = { null },
        vehicle = vehicle,
        naviEvents = navi,
        publish = { topic, type, payload -> published += Triple(topic, type, payload); true },
        dispatcher = StandardTestDispatcher(scheduler),
        clock = { scheduler.currentTime },
    )

    private val groupSession = GroupSession("grp_1", "귀성길", "veh_L", Place(37.6, 127.0, "휴게소"), GroupTopics("s", "e"), 0L)

    @Test
    fun `publishes detected events only while a group is active`() = runTest {
        val e = engine(testScheduler)
        e.start()
        try {
            vehicle.value = VehicleSignalState(evSoc = 12, speedKph = 0f)
            advanceTimeBy(3_000); runCurrent()
            assertTrue("no group → nothing", published.isEmpty())
            assertFalse(e.state.value.active)

            session.value = groupSession
            runCurrent()
            assertTrue(e.state.value.active)
            assertEquals(1, published.size)
            val (topic, type, payload) = published.first()
            assertEquals("dh/v1/vehicle/veh_T/event", topic)
            assertEquals("LOW_ENERGY", type)
            assertEquals("EV", payload["kind"]!!.jsonPrimitive.content)
            assertEquals("12", payload["pct"]!!.jsonPrimitive.content)
            assertEquals(1, e.state.value.detectedCounts["LOW_ENERGY"])
            assertEquals(12, e.state.value.energyAlertPct)

            // 급제동: 60 → 0 + 브레이크
            vehicle.value = vehicle.value.copy(speedKph = 60f)
            advanceTimeBy(2_000); runCurrent()
            vehicle.value = vehicle.value.copy(speedKph = 0f, brakePedal = true)
            advanceTimeBy(1_000); runCurrent()
            assertEquals("HARD_BRAKE", published.last().second)
            assertEquals("60.0", published.last().third["speedBeforeKph"]!!.jsonPrimitive.content)

            // 내비 도착 → 그룹 목적지 이름으로 DESTINATION_ARRIVED
            navi.emit(NaviEvent.DestinationArrived(null, null))
            runCurrent()
            assertEquals("DESTINATION_ARRIVED", published.last().second)
            assertEquals("휴게소", published.last().third["name"]!!.jsonPrimitive.content)
            assertEquals(3, e.state.value.publishedCount)

            session.value = null
            runCurrent()
            assertFalse(e.state.value.active)
        } finally {
            e.stop()
        }
    }
}
