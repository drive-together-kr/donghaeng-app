package app.donghaeng.presence

import app.donghaeng.common.config.DhProperties
import app.donghaeng.group.GroupRoster
import app.donghaeng.group.RosterMember
import app.donghaeng.protocol.Energy
import app.donghaeng.protocol.LocSource
import app.donghaeng.protocol.MemberStatus
import app.donghaeng.protocol.Place
import app.donghaeng.protocol.Telemetry
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 테스트용 메모리 저장소 — 상태를 직접 넣는다 */
class InMemoryPresenceStore : PresenceStore {
    val states = mutableMapOf<String, VehicleState>()
    val online = mutableMapOf<String, Boolean>()
    override fun recordTelemetry(vehicleId: String, telemetry: Telemetry, tsMs: Long) = Unit
    override fun recordGeo(groupId: String, vehicleId: String, lat: Double, lon: Double) = Unit
    override fun recordPresence(vehicleId: String, online: Boolean, tsMs: Long) { this.online[vehicleId] = online }
    override fun stateOf(vehicleId: String): VehicleState? = states[vehicleId]
    override fun onlineOf(vehicleId: String): Boolean? = online[vehicleId]
}

class SnapshotBuilderTest {

    private val now = 1_000_000_000L
    private val store = InMemoryPresenceStore()
    private val props = DhProperties(publicBaseUrl = "http://x", mqtt = DhProperties.Mqtt(uri = "tcp://x", publicUri = "tcp://x"))
    private val builder = SnapshotBuilder(store, props, Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC))
    private val dest = Place(lat = 37.6000, lon = 127.0000, name = "목적지")
    private val roster = GroupRoster(
        groupId = "grp_T", name = "테스트", leaderVehicleId = "veh_A", destination = dest,
        members = listOf(RosterMember("veh_A", "아빠 차", "#111111", "LEADER", 0), RosterMember("veh_B", "엄마 차", "#222222", "MEMBER", 0)),
    )

    private fun state(lat: Double, lon: Double, speed: Float?, ageMs: Long = 0, stoppedForMs: Long? = null, fuel: Int? = null) = VehicleState(
        lat = lat, lon = lon, heading = 90f, speedKph = speed, gear = "D", locSource = LocSource.NAVI, ts = now - ageMs,
        eta = null, etaTs = null, energy = fuel?.let { Energy(fuelPct = it) }, stoppedSinceMs = stoppedForMs?.let { now - it },
    )

    @Test
    fun `members without any state are offline with null position`() {
        val s = builder.build(roster)
        assertEquals("grp_T", s.groupId)
        assertEquals(2, s.members.size)
        s.members.forEach { m ->
            assertEquals(MemberStatus.OFFLINE, m.status)
            assertFalse(m.online)
            assertNull(m.lat); assertNull(m.ageMs); assertNull(m.distanceToLeaderM)
        }
        assertEquals(listOf("아빠 차", "엄마 차"), s.members.map { it.nickname })
    }

    @Test
    fun `fresh telemetry drives, distance to leader is haversine and leader is zero`() {
        store.states["veh_A"] = state(37.5000, 127.0, 80f, fuel = 40)
        store.states["veh_B"] = state(37.5090, 127.0, 60f, ageMs = 820)     // 약 1 km 북쪽
        val s = builder.build(roster)
        val a = s.members.first { it.vehicleId == "veh_A" }
        val b = s.members.first { it.vehicleId == "veh_B" }
        assertEquals(MemberStatus.DRIVING, a.status); assertTrue(a.online); assertEquals(0, a.distanceToLeaderM); assertEquals(40, a.energy?.fuelPct); assertEquals(0L, a.ageMs)
        assertEquals(MemberStatus.DRIVING, b.status); assertEquals(820L, b.ageMs)
        assertTrue(b.distanceToLeaderM!! in 950..1050, "distance=${b.distanceToLeaderM}")
    }

    @Test
    fun `presence false, stale position or old telemetry without presence make a member offline`() {
        store.states["veh_A"] = state(37.5, 127.0, 80f)
        store.online["veh_A"] = false
        assertEquals(MemberStatus.OFFLINE, builder.build(roster).members.first().status)

        store.online.remove("veh_A")
        store.states["veh_A"] = state(37.5, 127.0, 80f, ageMs = 61_000)     // presence 없음 + 61초 전 → offline
        val m = builder.build(roster).members.first()
        assertFalse(m.online); assertEquals(MemberStatus.OFFLINE, m.status); assertEquals(61_000L, m.ageMs)

        store.online["veh_A"] = true
        store.states["veh_A"] = state(37.5, 127.0, 80f, ageMs = 61_000)     // presence 는 online 이어도 위치가 60초 넘게 없으면 OFFLINE
        assertEquals(MemberStatus.OFFLINE, builder.build(roster).members.first().status)

        store.online.remove("veh_A")
        store.states["veh_A"] = state(37.5, 127.0, 80f, ageMs = 20_000)     // presence 없음 + 20초 전(15초 초과) → online=false → OFFLINE
        assertEquals(MemberStatus.OFFLINE, builder.build(roster).members.first().status)
    }

    @Test
    fun `stopped for a minute is STOPPED, at the destination it is ARRIVED`() {
        store.states["veh_A"] = state(37.5, 127.0, 0f, stoppedForMs = 59_000)
        assertEquals(MemberStatus.DRIVING, builder.build(roster).members.first().status)
        store.states["veh_A"] = state(37.5, 127.0, 0f, stoppedForMs = 60_000)
        assertEquals(MemberStatus.STOPPED, builder.build(roster).members.first().status)

        store.states["veh_A"] = state(37.6005, 127.0, 0.5f, stoppedForMs = 5_000)   // 목적지 56 m 앞, 거의 정지
        assertEquals(MemberStatus.ARRIVED, builder.build(roster).members.first().status)
        store.states["veh_A"] = state(37.6005, 127.0, 40f)                           // 목적지 근처지만 달리는 중 → DRIVING
        assertEquals(MemberStatus.DRIVING, builder.build(roster).members.first().status)

        // 목적지가 없는 그룹은 ARRIVED 판정이 없다
        store.states["veh_A"] = state(37.6005, 127.0, 0.5f, stoppedForMs = 5_000)
        assertEquals(MemberStatus.DRIVING, builder.build(roster.copy(destination = null)).members.first().status)
    }
}
