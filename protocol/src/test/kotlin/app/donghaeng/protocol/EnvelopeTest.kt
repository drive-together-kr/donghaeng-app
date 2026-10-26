package app.donghaeng.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * (5) Envelope<Telemetry> 전체 왕복(v=1 기본값 포함) 과 peek / decodeData 라우팅 흐름.
 */
class EnvelopeTest {

    private val telemetry = Telemetry(
        lat = 37.4854472, lon = 127.0363666, heading = 182.5f, speedKph = 87.0f,
        locSource = LocSource.NAVI, gear = "D",
        eta = Eta(1793003600000L, 182000, 6600),
        energy = Energy(fuelPct = 34),
    )

    private val envelope = Envelope(
        type = MessageTypes.TELEMETRY,
        vid = "veh_01H",
        ts = 1793000000000L,
        seq = 1024L,
        data = telemetry,
    )

    @Test
    fun telemetry_envelope_round_trip_with_default_version() {
        val raw = DhJson.encode(envelope)
        assertTrue(raw.startsWith("""{"v":1,"type":"TELEMETRY","vid":"veh_01H","ts":1793000000000,"seq":1024,"data":{"""), raw)
        val back = DhJson.decode<Telemetry>(raw)
        assertEquals(envelope, back)
        assertEquals(1, back.v)
    }

    @Test
    fun explicit_serializer_overloads_match_reified_ones() {
        val a = DhJson.encode(envelope)
        val b = DhJson.encode(envelope, Telemetry.serializer())
        assertEquals(a, b)
        assertEquals(DhJson.decode<Telemetry>(a), DhJson.decode(a, Telemetry.serializer()))
    }

    @Test
    fun missing_version_defaults_to_one() {
        val raw = """{"type":"TELEMETRY","vid":"veh_01H","ts":1,"seq":2,"data":{"lat":1.0,"lon":2.0,"locSource":"FAKE"}}"""
        val env = DhJson.decode<Telemetry>(raw)
        assertEquals(1, env.v)
        assertEquals(Telemetry(1.0, 2.0, null, null, LocSource.FAKE), env.data)
    }

    @Test
    fun peek_exposes_header_and_raw_data() {
        val raw = DhJson.encode(envelope)
        val head = DhJson.peek(raw)
        assertEquals(1, head.v)
        assertEquals(MessageTypes.TELEMETRY, head.type)
        assertEquals("veh_01H", head.vid)
        assertEquals(1793000000000L, head.ts)
        assertEquals(1024L, head.seq)
        assertIs<JsonObject>(head.data)
        assertEquals(37.4854472, head.data.jsonObject.getValue("lat").jsonPrimitive.double)

        val full = DhJson.decodeData<Telemetry>(head)
        assertEquals(envelope, full)
        assertEquals(full, DhJson.decodeData(head, Telemetry.serializer()))
    }

    @Test
    fun router_style_dispatch_on_type() {
        val messages = listOf(
            DhJson.encode(envelope),
            DhJson.encode(Envelope(type = MessageTypes.ACK, vid = "veh_01H", ts = 2L, seq = 1025L, data = Ack("prp_1", true))),
            DhJson.encode(Envelope(type = VehicleEventType.HARD_BRAKE.name, vid = "veh_01H", ts = 3L, seq = 1026L, data = HardBrake(4.5, 90.0))),
            DhJson.encode(Envelope(type = VehicleEventType.HAZARD_ON.name, vid = "veh_01H", ts = 4L, seq = 1027L, data = HazardOn)),
            DhJson.encode(Envelope(type = CmdType.GROUP_ENDED.name, vid = "veh_01H", ts = 5L, seq = 1L, data = GroupEnded("grp_1", "LEADER_ENDED"))),
        )
        val decoded = messages.map { raw ->
            val head = DhJson.peek(raw)
            when (head.type) {
                MessageTypes.TELEMETRY -> DhJson.decodeData<Telemetry>(head).data
                MessageTypes.ACK -> DhJson.decodeData<Ack>(head).data
                else -> {
                    val vehicleEvent = VehicleEventType.entries.firstOrNull { it.name == head.type }
                    val cmd = CmdType.entries.firstOrNull { it.name == head.type }
                    when {
                        vehicleEvent != null -> DhJson.json.decodeFromJsonElement(vehicleEventSerializer(vehicleEvent), head.data)
                        cmd != null -> DhJson.json.decodeFromJsonElement(cmdSerializer(cmd), head.data)
                        else -> error("unknown type ${head.type}")
                    }
                }
            }
        }
        assertEquals(telemetry, decoded[0])
        assertEquals(Ack("prp_1", true), decoded[1])
        assertEquals(HardBrake(4.5, 90.0), decoded[2])
        assertEquals(HazardOn, decoded[3])
        assertEquals(GroupEnded("grp_1", "LEADER_ENDED"), decoded[4])
    }

    @Test
    fun payload_helpers_round_trip_through_group_event() {
        val original = RouteChanged(Place(35.1, 126.8, "목적지"), listOf(Place(36.0, 127.0, "경유지")))
        val event = GroupEvent(
            eventId = "evt_1", type = GroupEventType.ROUTE_CHANGED.name, sourceVehicleId = "veh_A",
            severity = Severity.INFO, speech = "경로가 바뀌었어요.", payload = DhJson.payloadOf(original),
        )
        val raw = DhJson.encode(Envelope(type = MessageTypes.EVENT, vid = "server", ts = 1L, seq = 1L, data = event))
        val back = DhJson.decode<GroupEvent>(raw).data
        assertEquals(GroupEventType.ROUTE_CHANGED, back.eventType)
        assertEquals(original, DhJson.payloadAs<RouteChanged>(back.payload))
        assertEquals(original, DhJson.payloadAs(back.payload, RouteChanged.serializer()))
    }
}
