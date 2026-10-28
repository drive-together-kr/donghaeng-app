package app.donghaeng.protocol

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * (2) docs/03_API_프로토콜_명세.md §5 의 예시 JSON 이 그대로 디코딩되는지.
 * 문서의 `"A" | "B"` / `| null` / 주석 표기는 하나의 값으로 고정해서 넣었다.
 */
class DocSamplesTest {

    /** §5 공통 봉투 */
    @Test
    fun section5_envelope() {
        val raw = """
            { "v": 1, "type": "TELEMETRY", "vid": "veh_01H...", "ts": 1793000000000, "seq": 1024, "data": { } }
        """.trimIndent()
        val env = DhJson.peek(raw)
        assertEquals(1, env.v)
        assertEquals(MessageTypes.TELEMETRY, env.type)
        assertEquals("veh_01H...", env.vid)
        assertEquals(1793000000000L, env.ts)
        assertEquals(1024L, env.seq)
        assertIs<JsonObject>(env.data)
        assertTrue(env.data.jsonObject.isEmpty())
    }

    /** §5.1 TELEMETRY data */
    @Test
    fun section5_1_telemetry() {
        val raw = """
            {
              "lat": 37.4854472, "lon": 127.0363666, "heading": 182.5,
              "speedKph": 87.0,
              "locSource": "NAVI",
              "gear": "D",
              "eta": { "destTs": 1793003600000, "remainM": 182000, "remainS": 6600 },
              "energy": { "fuelPct": 34, "socPct": null }
            }
        """.trimIndent()
        val t = DhJson.json.decodeFromString<Telemetry>(raw)
        assertEquals(37.4854472, t.lat)
        assertEquals(127.0363666, t.lon)
        assertEquals(182.5f, t.heading)
        assertEquals(87.0f, t.speedKph)
        assertEquals(LocSource.NAVI, t.locSource)
        assertEquals("D", t.gear)
        assertEquals(Eta(1793003600000L, 182000, 6600), t.eta)
        assertEquals(Energy(fuelPct = 34, socPct = null), t.energy)
    }

    /** §5.1 TELEMETRY — `| null` 쪽 값으로 */
    @Test
    fun section5_1_telemetry_null_variant() {
        val raw = """
            {
              "lat": 37.4854472, "lon": 127.0363666, "heading": 182.5,
              "speedKph": 87.0,
              "locSource": "FAKE",
              "gear": null,
              "eta": null,
              "energy": null
            }
        """.trimIndent()
        val t = DhJson.json.decodeFromString<Telemetry>(raw)
        assertEquals(LocSource.FAKE, t.locSource)
        assertNull(t.gear)
        assertNull(t.eta)
        assertNull(t.energy)
    }

    /** §5.2 차량 EVENT data 들 (표의 표기 그대로) */
    @Test
    fun section5_2_vehicle_events() {
        assertEquals(LowEnergy(EnergyKind.FUEL, 12), DhJson.json.decodeFromString("""{ "kind": "FUEL", "pct": 12 }"""))
        assertEquals(LowEnergy(EnergyKind.EV, 14), DhJson.json.decodeFromString("""{ "kind": "EV", "pct": 14 }"""))
        assertEquals(HardBrake(4.2, 88.0), DhJson.json.decodeFromString("""{ "decelMps2": 4.2, "speedBeforeKph": 88 }"""))
        assertEquals(HazardOn, DhJson.json.decodeFromString<HazardOn>("""{}"""))
        assertEquals(AirbagDeployed, DhJson.json.decodeFromString<AirbagDeployed>("""{}"""))
        assertEquals(
            RouteChanged(Place(35.1595454, 126.8526012, "목적지", poiId = null, address = "..."), listOf(Place(36.0, 127.0, "경유지"))),
            DhJson.json.decodeFromString(
                """{ "destination": { "lat": 35.1595454, "lon": 126.8526012, "name": "목적지", "poiId": null, "address": "..." },
                     "waypoints": [ { "lat": 36.0, "lon": 127.0, "name": "경유지" } ] }""",
            ),
        )
        assertEquals(WaypointArrived("휴게소", 36.0, 127.0), DhJson.json.decodeFromString("""{ "name": "휴게소", "lat": 36.0, "lon": 127.0 }"""))
        assertEquals(DestinationArrived("목적지"), DhJson.json.decodeFromString("""{ "name": "목적지" }"""))
        assertEquals(Stopped(60), DhJson.json.decodeFromString("""{ "durationS": 60 }"""))
        assertEquals(Stopped(null), DhJson.json.decodeFromString("""{}"""))
        assertEquals(Resumed(125), DhJson.json.decodeFromString("""{ "durationS": 125 }"""))
        assertEquals(Resumed(null), DhJson.json.decodeFromString("""{}"""))
        assertEquals(PresetMessage("REST_PLEASE"), DhJson.json.decodeFromString("""{ "presetId": "REST_PLEASE" }"""))
    }

    /** §5.3 그룹 STATE data */
    @Test
    fun section5_3_group_state() {
        val raw = """
            {
              "groupId": "grp_01H...", "leaderVehicleId": "veh_A",
              "members": [{
                "vehicleId": "veh_A", "nickname": "아빠 차", "color": "#2E7DFF", "online": true,
                "lat": 0, "lon": 0, "heading": 0, "speedKph": 92, "ageMs": 820,
                "eta": { "destTs": 0, "remainM": 0 }, "energy": { "fuelPct": 34 },
                "distanceToLeaderM": 0, "status": "DRIVING"
              }]
            }
        """.trimIndent()
        val s = DhJson.json.decodeFromString<GroupState>(raw)
        assertEquals("grp_01H...", s.groupId)
        assertEquals("veh_A", s.leaderVehicleId)
        assertEquals(1, s.members.size)
        val m = s.members.single()
        assertEquals("veh_A", m.vehicleId)
        assertEquals("아빠 차", m.nickname)
        assertEquals("#2E7DFF", m.color)
        assertTrue(m.online)
        assertEquals(0.0, m.lat)
        assertEquals(0.0, m.lon)
        assertEquals(0f, m.heading)
        assertEquals(92f, m.speedKph)
        assertEquals(820L, m.ageMs)
        // 문서 예시에는 remainS 가 없다 -> null
        assertEquals(Eta(destTs = 0L, remainM = 0, remainS = null), m.eta)
        assertEquals(Energy(fuelPct = 34, socPct = null), m.energy)
        assertEquals(0, m.distanceToLeaderM)
        assertEquals(MemberStatus.DRIVING, m.status)
    }

    /** §5.4 그룹 EVENT data */
    @Test
    fun section5_4_group_event() {
        val raw = """
            {
              "eventId": "evt_01H...", "type": "LOW_ENERGY", "sourceVehicleId": "veh_B",
              "severity": "INFO",
              "speech": "엄마 차 연료가 12% 남았어요.",
              "payload": { "kind": "FUEL", "pct": 12 }
            }
        """.trimIndent()
        val e = DhJson.json.decodeFromString<GroupEvent>(raw)
        assertEquals("evt_01H...", e.eventId)
        assertEquals("LOW_ENERGY", e.type)
        assertEquals(GroupEventType.LOW_ENERGY, e.eventType)
        assertEquals("veh_B", e.sourceVehicleId)
        assertEquals(Severity.INFO, e.severity)
        assertEquals("엄마 차 연료가 12% 남았어요.", e.speech)
        assertEquals("FUEL", e.payload.getValue("kind").jsonPrimitive.content)
        assertEquals(12, e.payload.getValue("pct").jsonPrimitive.int)
        // payload 를 차량 이벤트 본문으로 해석
        assertEquals(LowEnergy(EnergyKind.FUEL, 12), DhJson.payloadAs<LowEnergy>(e.payload))
    }

    /** §5.4 그룹 EVENT data — 급제동 + 수신 대상 audience */
    @Test
    fun section5_4_group_event_with_audience() {
        val raw = """
            {
              "eventId": "evt_01H...", "type": "HARD_BRAKE", "sourceVehicleId": "veh_B",
              "severity": "WARN",
              "speech": "앞쪽 둘째 차가 급제동했어요. 주의하세요.",
              "payload": { "decelMps2": 5.6, "speedBeforeKph": 72.0 },
              "audience": ["veh_C"]
            }
        """.trimIndent()
        val e = DhJson.json.decodeFromString<GroupEvent>(raw)
        assertEquals(GroupEventType.HARD_BRAKE, e.eventType)
        assertEquals(listOf("veh_C"), e.audience)
        assertEquals(HardBrake(5.6, 72.0), DhJson.payloadAs<HardBrake>(e.payload))
    }

    /** §5.5 CMD data 들 (표의 표기 그대로) */
    @Test
    fun section5_5_cmds() {
        val joined = DhJson.json.decodeFromString<GroupJoined>(
            """{ "groupId": "grp_01H", "name": "추석 귀성길", "leaderVehicleId": "veh_01H",
                 "destination": { "lat": 35.1595454, "lon": 126.8526012, "name": "목적지", "poiId": null, "address": "..." },
                 "topics": { "state": "dh/v1/group/grp_01H/state", "event": "dh/v1/group/grp_01H/event" } }""",
        )
        assertEquals("grp_01H", joined.groupId)
        assertEquals(Topics.groupState("grp_01H"), joined.topics.state)
        assertEquals(Topics.groupEvent("grp_01H"), joined.topics.event)

        val follow = DhJson.json.decodeFromString<RouteFollowProposal>(
            """{ "proposalId": "prp_01H", "destination": { "lat": 35.1, "lon": 126.8, "name": "목적지" },
                 "waypoints": [], "speech": "선두차가 경로를 바꿨어요. 따라갈까요?" }""",
        )
        assertEquals("prp_01H", follow.proposalId)
        assertTrue(follow.waypoints.isEmpty())

        val rest = DhJson.json.decodeFromString<RestStopProposal>(
            """{ "proposalId": "prp_01H", "poi": { "poiId": "ra_0012", "name": "휴게소(부산방향)", "lat": 0, "lon": 0, "distanceKm": 18.2 },
                 "reason": "LOW_ENERGY", "speech": "18km 앞 휴게소에 들를까요?" }""",
        )
        assertEquals("ra_0012", rest.poi.poiId)
        assertEquals(18.2, rest.poi.distanceKm)
        assertEquals(false, rest.poi.hasEvCharger) // 문서 §5.5 에는 없고 §3 에만 있는 필드 -> 기본값

        assertEquals(GroupEnded("grp_01H", "LEADER_ENDED"), DhJson.json.decodeFromString("""{ "groupId": "grp_01H", "reason": "LEADER_ENDED" }"""))
        assertEquals(TokenRevoked, DhJson.json.decodeFromString<TokenRevoked>("""{}"""))
    }

    /** §5.6 ACK data */
    @Test
    fun section5_6_ack() {
        val raw = """
            { "proposalId": "prp_01H...", "accepted": true }
        """.trimIndent()
        assertEquals(Ack("prp_01H...", true), DhJson.json.decodeFromString<Ack>(raw))
    }

    /** §4 presence LWT 본문 */
    @Test
    fun section4_presence_lwt() {
        assertEquals(Presence(false), DhJson.json.decodeFromString<Presence>("""{"online":false}"""))
    }
}
