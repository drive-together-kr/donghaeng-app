package app.donghaeng.car.domain.telemetry

import app.donghaeng.car.domain.model.Fix
import app.donghaeng.car.domain.model.Gear
import app.donghaeng.car.domain.navi.NaviMonitorState
import app.donghaeng.car.domain.session.GroupSession
import app.donghaeng.car.domain.vehicle.VehicleSignalState
import app.donghaeng.protocol.GroupTopics
import app.donghaeng.protocol.LocSource
import app.donghaeng.protocol.Place
import app.donghaeng.protocol.Telemetry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TelemetryCollectorTest {

    private val session = MutableStateFlow<GroupSession?>(null)
    private val vehicle = MutableStateFlow(VehicleSignalState())
    private val navi = MutableStateFlow(NaviMonitorState())
    private var fix: Fix? = null
    /** true 면 latestFix 가 항상 '지금' 시각의 Fix 를 돌려준다(위치 신선도 검사를 통과) */
    private var freshen = false
    private var publishOk = true
    private val published = mutableListOf<Pair<String, Telemetry>>()

    private fun collector(scheduler: kotlinx.coroutines.test.TestCoroutineScheduler) = TelemetryCollector(
        session = session,
        vehicleId = { "veh_TEST0001" },
        latestFix = { fix?.let { f -> if (freshen) f.copy(ts = scheduler.currentTime) else f } },
        fallbackSpeedKph = { 33f },
        vehicle = vehicle,
        navi = navi,
        publish = { topic, t -> published += topic to t; publishOk },
        dispatcher = StandardTestDispatcher(scheduler),
        clock = { scheduler.currentTime },
    )

    private fun groupSession() = GroupSession(
        groupId = "grp_T", name = "테스트 그룹", leaderVehicleId = "veh_L", destination = Place(37.0, 127.0, "목적지"),
        topics = GroupTopics(state = "dh/v1/group/grp_T/state", event = "dh/v1/group/grp_T/event"), joinedAtMs = 0L,
    )

    private fun freshFix(now: Long) = Fix(lat = 37.5, lon = 127.0, heading = 90f, accuracy = 5f, ts = now, source = LocSource.NAVI)

    @Test
    fun `publishes at 1Hz only while a group is active and a fresh fix exists`() = runTest {
        val c = collector(testScheduler)
        c.start()
        try {
        advanceTimeBy(3_000); runCurrent()
        assertTrue(published.isEmpty())
        assertFalse(c.state.value.active)

        session.value = groupSession()
        runCurrent()
        assertTrue(c.state.value.active)
        assertEquals(1, c.state.value.skippedNoFix)       // 위치 없음 → 건너뜀
        fix = freshFix(testScheduler.currentTime)
        advanceTimeBy(5_000); runCurrent()
        assertEquals(5, published.size)
        assertEquals("dh/v1/vehicle/veh_TEST0001/telemetry", published.first().first)
        val t = published.first().second
        assertEquals(37.5, t.lat, 1e-9)
        assertEquals(LocSource.NAVI, t.locSource)
        assertEquals(33f, t.speedKph)                     // 차량 속도 없음 → 위치 계산값
        assertNull(t.gear)
        assertNull(t.eta)
        assertNull(t.energy)

        // 오래된 위치 → 건너뜀
        advanceTimeBy(15_000); runCurrent()
        val before = published.size
        advanceTimeBy(3_000); runCurrent()
        assertEquals(before, published.size)
        assertTrue(c.state.value.skippedNoFix > 1)

        session.value = null
        runCurrent()
        assertFalse(c.state.value.active)
        } finally { c.stop() }
    }

    @Test
    fun `vehicle speed and gear win over fallback, energy only on change or every 60s, eta every 30s or on change`() = runTest {
        val c = collector(testScheduler)
        freshen = true
        session.value = groupSession()
        vehicle.value = VehicleSignalState(speedKph = 55f, gear = Gear.D, fuelPercent = 40)
        navi.value = NaviMonitorState(lastRemainDistanceM = 10_000, lastRemainTimeS = 600, lastTripTs = testScheduler.currentTime)
        c.start(); runCurrent()
        try {
        fix = freshFix(testScheduler.currentTime)
        advanceTimeBy(1_000); runCurrent()                 // 첫 발행(t=1s)
        val first = published.last().second
        assertEquals(55f, first.speedKph)
        assertEquals("D", first.gear)
        assertNotNull(first.energy); assertEquals(40, first.energy!!.fuelPct)
        assertNotNull(first.eta); assertEquals(10_000, first.eta!!.remainM); assertEquals(600, first.eta!!.remainS)
        assertEquals(navi.value.lastTripTs!! + 600_000L, first.eta!!.destTs)

        // 변화 없음 → 다음 틱들은 eta/energy 생략
        advanceTimeBy(5_000); runCurrent()
        assertTrue(published.drop(1).all { it.second.energy == null && it.second.eta == null })
        assertEquals(1, c.state.value.energySentCount)
        assertEquals(1, c.state.value.etaSentCount)

        // 연료 1%p 변화 → 바로 포함
        vehicle.value = vehicle.value.copy(fuelPercent = 39)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(39, published.last().second.energy?.fuelPct)
        assertEquals(2, c.state.value.energySentCount)

        // ETA 거리 500 m 이상 변화 → 포함
        navi.value = navi.value.copy(lastRemainDistanceM = 9_400, lastTripTs = testScheduler.currentTime)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(9_400, published.last().second.eta?.remainM)
        assertEquals(2, c.state.value.etaSentCount)

        // 30초 경과 → 변화 없어도 ETA 재전송, 60초 → 에너지 재전송
        navi.value = navi.value.copy(lastTripTs = testScheduler.currentTime)   // 신선도 유지
        advanceTimeBy(30_000); runCurrent()
        assertTrue(c.state.value.etaSentCount >= 3)
        advanceTimeBy(31_000); runCurrent()
        assertTrue(c.state.value.energySentCount >= 3)

        // 오래된 TripInfo(120s 초과) → ETA 제외
        navi.value = navi.value.copy(lastTripTs = testScheduler.currentTime - 200_000L)
        advanceTimeBy(31_000); runCurrent()
        assertNull(published.last().second.eta)
        } finally { c.stop() }
    }

    @Test
    fun `publish failures are counted and markers are not advanced`() = runTest {
        val c = collector(testScheduler)
        freshen = true
        session.value = groupSession()
        vehicle.value = VehicleSignalState(fuelPercent = 50)
        publishOk = false
        c.start(); runCurrent()
        try {
        fix = freshFix(testScheduler.currentTime)
        advanceTimeBy(3_000); runCurrent()
        assertEquals(0, c.state.value.sentCount)
        assertEquals(3, c.state.value.failedCount)
        assertTrue(published.all { it.second.energy != null })   // 성공 전까지는 매번 에너지 포함
        publishOk = true
        advanceTimeBy(1_000); runCurrent()
        assertEquals(1, c.state.value.sentCount)
        assertEquals(1, c.state.value.energySentCount)
        } finally { c.stop() }
    }
}
