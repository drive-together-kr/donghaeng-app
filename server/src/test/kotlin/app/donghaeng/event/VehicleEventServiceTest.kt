package app.donghaeng.event

import app.donghaeng.group.GroupMembershipApi
import app.donghaeng.group.GroupRoster
import app.donghaeng.identity.IdentityApi
import app.donghaeng.identity.VehicleInfo
import app.donghaeng.protocol.GroupEvent
import app.donghaeng.protocol.GroupEventType
import app.donghaeng.protocol.Severity
import app.donghaeng.protocol.VehicleEventType
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class VehicleEventServiceTest {

    private class FakeMembership(var groupOf: Map<String, String>) : GroupMembershipApi {
        override fun activeGroupIdOf(vehicleId: String): String? = groupOf[vehicleId]
        override fun rosterOf(groupId: String): GroupRoster? = null
        override fun activeRosters(): List<GroupRoster> = emptyList()
    }

    private class FakeIdentity(private val names: Map<String, String>) : IdentityApi {
        override fun vehicle(vehicleId: String): VehicleInfo? = names[vehicleId]?.let { VehicleInfo(vehicleId, "usr", it, "HYUNDAI", "GRANDEUR", true, true) }
        override fun vehicles(vehicleIds: Collection<String>): Map<String, VehicleInfo> = vehicleIds.mapNotNull { vehicle(it) }.associateBy { it.vehicleId }
        override fun activeVehiclesOf(userId: String): List<VehicleInfo> = emptyList()
    }

    private class InMemoryDedup : EventDedup {
        val seen = mutableMapOf<String, Duration>()
        override fun firstWithin(vehicleId: String, type: String, ttl: Duration): Boolean {
            val key = "$vehicleId:$type"
            if (seen.containsKey(key)) return false
            seen[key] = ttl
            return true
        }
    }

    private class RecordingSink : GroupEventSink {
        data class Rec(val groupId: String, val type: GroupEventType, val vid: String?, val severity: Severity, val speech: String?, val payload: JsonObject)
        val recorded = mutableListOf<Rec>()
        override fun record(groupId: String, type: GroupEventType, sourceVehicleId: String?, severity: Severity, speech: String?, payload: JsonObject): GroupEvent {
            recorded += Rec(groupId, type, sourceVehicleId, severity, speech, payload)
            return GroupEvent("evt_${recorded.size}", type.name, sourceVehicleId, severity, speech, payload)
        }
    }

    private val dedup = InMemoryDedup()
    private val sink = RecordingSink()
    private val service = VehicleEventService(
        FakeMembership(mapOf("veh_A" to "grp_1")),
        FakeIdentity(mapOf("veh_A" to "아빠 차")),
        dedup, SpeechComposer(), sink,
    )

    @Test
    fun `vehicle events become group events with Korean speech and severity`() {
        val low = service.handle("veh_A", VehicleEventType.LOW_ENERGY, buildJsonObject { put("kind", "FUEL"); put("pct", 12) })
        assertNotNull(low)
        assertEquals("grp_1", sink.recorded.single().groupId)
        assertEquals(GroupEventType.LOW_ENERGY, sink.recorded.single().type)
        assertEquals(Severity.WARN, low.severity)
        assertEquals("아빠 차 연료가 12% 남았어요.", low.speech)
        assertEquals(12, low.payload["pct"].toString().toInt())

        val brake = service.handle("veh_A", VehicleEventType.HARD_BRAKE, buildJsonObject { put("decelMps2", 5.6); put("speedBeforeKph", 72.0) })
        assertEquals("앞쪽 아빠 차가 급제동했어요. 주의하세요.", brake!!.speech)
        assertEquals(Severity.WARN, brake.severity)

        val wp = service.handle("veh_A", VehicleEventType.WAYPOINT_ARRIVED, buildJsonObject { put("name", "덕평휴게소"); put("lat", 37.2); put("lon", 127.4) })
        assertEquals("아빠 차가 덕평휴게소에 도착했어요.", wp!!.speech)
        assertEquals(Severity.INFO, wp.severity)

        assertEquals("아빠 차가 먼저 도착했어요.", service.handle("veh_A", VehicleEventType.DESTINATION_ARRIVED, buildJsonObject { put("name", "할머니댁") })!!.speech)
        assertEquals("아빠 차가 잠시 멈췄어요.", service.handle("veh_A", VehicleEventType.STOPPED, buildJsonObject { put("durationS", 60) })!!.speech)
        assertEquals("아빠 차가 다시 출발했어요.", service.handle("veh_A", VehicleEventType.RESUMED, buildJsonObject { put("durationS", 90) })!!.speech)
        val airbag = service.handle("veh_A", VehicleEventType.AIRBAG_DEPLOYED, buildJsonObject { })
        assertEquals(Severity.ALERT, airbag!!.severity)
        assertEquals("아빠 차 에어백이 터졌어요. 확인해 주세요.", airbag.speech)
        assertEquals(7, sink.recorded.size)
    }

    @Test
    fun `duplicates within the window, vehicles without a group and deferred types are not recorded`() {
        assertNotNull(service.handle("veh_A", VehicleEventType.LOW_ENERGY, buildJsonObject { put("kind", "EV"); put("pct", 14) }))
        assertNull(service.handle("veh_A", VehicleEventType.LOW_ENERGY, buildJsonObject { put("kind", "EV"); put("pct", 9) }), "same type within 120 s is suppressed")
        assertEquals(Duration.ofSeconds(120), dedup.seen["veh_A:LOW_ENERGY"])
        assertNotNull(service.handle("veh_A", VehicleEventType.STOPPED, buildJsonObject { }))
        assertEquals(Duration.ofSeconds(20), dedup.seen["veh_A:STOPPED"])

        assertNull(service.handle("veh_Z", VehicleEventType.HARD_BRAKE, buildJsonObject { put("decelMps2", 5.0); put("speedBeforeKph", 60.0) }), "no active group")
        assertNull(service.handle("veh_A", VehicleEventType.ROUTE_CHANGED, buildJsonObject { }), "routesync handles ROUTE_CHANGED")
        assertNull(service.handle("veh_A", VehicleEventType.PRESET_MESSAGE, buildJsonObject { put("presetId", "p1") }))
        assertEquals(2, sink.recorded.size)
    }

    @Test
    fun `unknown nickname falls back to the vehicle id and EV wording is used for batteries`() {
        val svc = VehicleEventService(FakeMembership(mapOf("veh_B" to "grp_2")), FakeIdentity(emptyMap()), InMemoryDedup(), SpeechComposer(), sink)
        val e = svc.handle("veh_B", VehicleEventType.LOW_ENERGY, buildJsonObject { put("kind", "EV"); put("pct", 8) })
        assertEquals("veh_B 배터리가 8% 남았어요.", e!!.speech)
    }
}
