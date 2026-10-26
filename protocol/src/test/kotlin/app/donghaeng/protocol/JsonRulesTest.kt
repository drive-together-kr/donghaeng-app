package app.donghaeng.protocol

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * (3) 모르는 키 무시 · (4) null 생략/부재→null · (7) 빈 본문 `{}` 규칙.
 */
class JsonRulesTest {

    // ---------- (3) 모르는 키 ----------

    @Test
    fun unknown_keys_are_ignored_top_level_and_nested() {
        val raw = """
            {
              "lat": 1.0, "lon": 2.0, "heading": 3.0, "speedKph": 4.0, "locSource": "ANDROID",
              "futureField": "whatever", "anotherOne": { "deep": [1, 2, 3] },
              "eta": { "destTs": 10, "remainM": 20, "remainS": 30, "remainKm": 0.02 },
              "energy": { "fuelPct": 50, "rangeKm": 400 }
            }
        """.trimIndent()
        val t = DhJson.json.decodeFromString<Telemetry>(raw)
        assertEquals(Telemetry(1.0, 2.0, 3.0f, 4.0f, LocSource.ANDROID, null, Eta(10L, 20, 30), Energy(50, null)), t)
    }

    @Test
    fun unknown_keys_in_envelope_header_are_ignored() {
        val raw = """{"v":1,"type":"ACK","vid":"veh_1","ts":1,"seq":1,"traceId":"abc","data":{"proposalId":"p","accepted":false,"note":"x"}}"""
        val env = DhJson.decode<Ack>(raw)
        assertEquals(Ack("p", false), env.data)
    }

    @Test
    fun unknown_keys_in_empty_payload_are_ignored() {
        assertSame(HazardOn, DhJson.json.decodeFromString<HazardOn>("""{"since":123}"""))
        assertSame(TokenRevoked, DhJson.json.decodeFromString<TokenRevoked>("""{"reason":"x"}"""))
    }

    // ---------- (4) null 생략 / 부재 -> null ----------

    @Test
    fun nulls_are_omitted_on_encode() {
        assertEquals("""{"fuelPct":34}""", DhJson.json.encodeToString(Energy(fuelPct = 34)))
        assertEquals("""{}""", DhJson.json.encodeToString(Energy()))
        assertEquals("""{"destTs":1}""", DhJson.json.encodeToString(Eta(destTs = 1L, remainM = null, remainS = null)))

        val encoded = DhJson.json.encodeToString(Telemetry(1.0, 2.0, null, null, LocSource.FAKE))
        assertEquals("""{"lat":1.0,"lon":2.0,"locSource":"FAKE"}""", encoded)
        assertFalse(encoded.contains("heading"))
        assertFalse(encoded.contains("null"))

        val event = DhJson.json.encodeToString(
            GroupEvent("evt_1", "MEMBER_LEFT", null, Severity.INFO, null, kotlinx.serialization.json.JsonObject(emptyMap())),
        )
        assertEquals("""{"eventId":"evt_1","type":"MEMBER_LEFT","severity":"INFO","payload":{}}""", event)
    }

    @Test
    fun absent_nullable_fields_decode_to_null() {
        // 기본값이 없는 nullable (heading, speedKph) 과 기본값이 있는 nullable (gear, eta, energy) 모두 부재 허용
        val t = DhJson.json.decodeFromString<Telemetry>("""{"lat":1.0,"lon":2.0,"locSource":"FUSED"}""")
        assertNull(t.heading)
        assertNull(t.speedKph)
        assertNull(t.gear)
        assertNull(t.eta)
        assertNull(t.energy)

        val m = DhJson.json.decodeFromString<MemberState>(
            """{"vehicleId":"veh_B","nickname":"뒷차","color":"#000000","online":false,"status":"OFFLINE"}""",
        )
        assertNull(m.lat); assertNull(m.lon); assertNull(m.heading); assertNull(m.speedKph)
        assertNull(m.ageMs); assertNull(m.eta); assertNull(m.energy); assertNull(m.distanceToLeaderM)
        assertEquals(MemberStatus.OFFLINE, m.status)

        val e = DhJson.json.decodeFromString<GroupEvent>("""{"eventId":"e","type":"GROUP_ENDED","severity":"ALERT","payload":{}}""")
        assertNull(e.sourceVehicleId)
        assertNull(e.speech)
    }

    @Test
    fun explicit_null_in_input_is_accepted() {
        val t = DhJson.json.decodeFromString<Telemetry>(
            """{"lat":1.0,"lon":2.0,"heading":null,"speedKph":null,"locSource":"NAVI","gear":null,"eta":null,"energy":null}""",
        )
        assertEquals(Telemetry(1.0, 2.0, null, null, LocSource.NAVI), t)
    }

    @Test
    fun defaults_are_always_encoded() {
        // encodeDefaults = true: v=1, waypoints=[], hasEvCharger=false 도 항상 나간다
        assertTrue(DhJson.encode(Envelope(type = "ACK", vid = "v", ts = 1, seq = 1, data = Ack("p", true))).startsWith("""{"v":1,"""))
        assertEquals(
            """{"destination":{"lat":1.0,"lon":2.0,"name":"n"},"waypoints":[]}""",
            DhJson.json.encodeToString(RouteChanged(Place(1.0, 2.0, "n"))),
        )
        assertTrue(DhJson.json.encodeToString(RestAreaPoi("p", "n", 1.0, 2.0, 3.0)).endsWith(""","hasEvCharger":false}"""))
    }

    // ---------- (7) 빈 본문 ----------

    @Test
    fun empty_payloads_encode_to_empty_object() {
        assertEquals("{}", DhJson.json.encodeToString(HazardOn))
        assertEquals("{}", DhJson.json.encodeToString(AirbagDeployed))
        assertEquals("{}", DhJson.json.encodeToString(TokenRevoked))
    }

    @Test
    fun empty_payloads_decode_from_empty_object() {
        assertSame(HazardOn, DhJson.json.decodeFromString<HazardOn>("{}"))
        assertSame(AirbagDeployed, DhJson.json.decodeFromString<AirbagDeployed>("{}"))
        assertSame(TokenRevoked, DhJson.json.decodeFromString<TokenRevoked>("{}"))
        // 공백 포함
        assertSame(HazardOn, DhJson.json.decodeFromString<HazardOn>("{ }"))
    }

    @Test
    fun empty_payload_inside_envelope() {
        val raw = DhJson.encode(Envelope(type = VehicleEventType.AIRBAG_DEPLOYED.name, vid = "veh_1", ts = 9L, seq = 3L, data = AirbagDeployed))
        assertEquals("""{"v":1,"type":"AIRBAG_DEPLOYED","vid":"veh_1","ts":9,"seq":3,"data":{}}""", raw)
        val back = DhJson.decode<AirbagDeployed>(raw)
        assertSame(AirbagDeployed, back.data)
        val cmd = DhJson.decode<TokenRevoked>("""{"v":1,"type":"TOKEN_REVOKED","vid":"veh_1","ts":1,"seq":1,"data":{}}""")
        assertSame(TokenRevoked, cmd.data)
    }
}
