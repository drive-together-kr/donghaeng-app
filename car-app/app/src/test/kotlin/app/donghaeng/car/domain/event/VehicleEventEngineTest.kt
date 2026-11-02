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

    @Test
    fun `A054 - an invite reissue (same group, new session object) does not announce the arrival again, A090 - no group name in logs`() = runTest {
        val e = engine(testScheduler)
        e.start()
        try {
            fix = Fix(lat = 37.6, lon = 127.0, heading = null, accuracy = 5f, ts = 0L, source = LocSource.NAVI)
            vehicle.value = VehicleSignalState(speedKph = 0f)
            session.value = groupSession.copy(name = "홍길동 차와 같이 가기")
            advanceTimeBy(2_000); runCurrent()
            assertEquals(1, published.count { it.second == "DESTINATION_ARRIVED" })
            // 정차 중 초대 재발급 → 같은 그룹·같은 목적지, 세션 객체만 바뀜
            session.value = session.value!!.copy(invite = app.donghaeng.car.domain.session.GroupInvite("inv", "u", 1L))
            advanceTimeBy(5_000); runCurrent()
            assertEquals("다시 알리지 않는다", 1, published.count { it.second == "DESTINATION_ARRIVED" })
            assertTrue(app.donghaeng.car.core.common.DhLog.recent(300).none { it.contains("홍길동") })
        } finally {
            e.stop()
        }
    }

    @Test
    fun `A059 - a navi arrival waits while a tick is judging (detector access is serialized)`() {
        val inTick = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val tickThread = "a059-tick"
        val out = java.util.Collections.synchronizedList(mutableListOf<String>())
        val engine = VehicleEventEngine(
            session = session,
            vehicleId = { "veh_T" },
            latestFix = {
                if (Thread.currentThread().name == tickThread) {
                    inTick.countDown()
                    release.await(5, java.util.concurrent.TimeUnit.SECONDS)
                }
                null
            },
            fallbackSpeedKph = { null },
            vehicle = vehicle,
            naviEvents = navi,
            publish = { _, type, _ -> out += type; true },
            intervalMs = 3_600_000L,
            dispatcher = kotlinx.coroutines.Dispatchers.Default,
        )
        session.value = groupSession
        engine.start()
        try {
            Thread.sleep(300)   // 첫 틱(세션 시작)이 지나가게
            val t = Thread({ engine.tick() }, tickThread).apply { start() }
            assertTrue(inTick.await(5, java.util.concurrent.TimeUnit.SECONDS))
            check(navi.tryEmit(NaviEvent.DestinationArrived(null, null)))
            Thread.sleep(300)
            assertFalse("틱이 판정 중인 동안 내비 도착은 기다린다", out.contains("DESTINATION_ARRIVED"))
            release.countDown()
            t.join(5_000)
            val deadline = System.currentTimeMillis() + 5_000
            while (!out.contains("DESTINATION_ARRIVED") && System.currentTimeMillis() < deadline) Thread.sleep(20)
            assertEquals(1, out.count { it == "DESTINATION_ARRIVED" })
        } finally {
            release.countDown()
            engine.stop()
        }
    }
}
