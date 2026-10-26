package app.donghaeng.protocol

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * (1) 모든 스키마 클래스의 JSON 왕복: encode -> decode -> equals.
 */
class RoundTripTest {

    private inline fun <reified T> roundTrip(value: T): String {
        val encoded = DhJson.json.encodeToString(value)
        val decoded = DhJson.json.decodeFromString<T>(encoded)
        assertEquals(value, decoded, "왕복 결과가 다릅니다: $encoded")
        return encoded
    }

    // ---------- 공통 ----------

    @Test
    fun envelope_with_json_element_data() {
        val env = Envelope(
            type = MessageTypes.ACK,
            vid = "veh_01H",
            ts = 1793000000000L,
            seq = 7L,
            data = buildJsonObject { put("proposalId", "prp_1"); put("accepted", true) },
        )
        roundTrip(env)
    }

    @Test
    fun place_full_and_minimal() {
        roundTrip(Place(35.1595454, 126.8526012, "목적지", poiId = "poi_1", address = "어딘가 1-2"))
        roundTrip(Place(35.0, 126.0, "이름만"))
    }

    // ---------- TELEMETRY ----------

    @Test
    fun telemetry_full() {
        roundTrip(
            Telemetry(
                lat = 37.4854472, lon = 127.0363666, heading = 182.5f, speedKph = 87.0f,
                locSource = LocSource.NAVI, gear = "D",
                eta = Eta(destTs = 1793003600000L, remainM = 182000, remainS = 6600),
                energy = Energy(fuelPct = 34, socPct = null),
            ),
        )
    }

    @Test
    fun telemetry_minimal_nulls() {
        roundTrip(Telemetry(lat = 1.0, lon = 2.0, heading = null, speedKph = null, locSource = LocSource.FAKE))
    }

    @Test
    fun eta_and_energy() {
        roundTrip(Eta(destTs = 1L, remainM = 2, remainS = 3))
        roundTrip(Eta(destTs = null, remainM = null, remainS = null))
        roundTrip(Energy(fuelPct = 34))
        roundTrip(Energy(socPct = 80))
        roundTrip(Energy())
    }

    @Test
    fun enums() {
        LocSource.entries.forEach { roundTrip(it) }
        EnergyKind.entries.forEach { roundTrip(it) }
        VehicleEventType.entries.forEach { roundTrip(it) }
        MemberStatus.entries.forEach { roundTrip(it) }
        Severity.entries.forEach { roundTrip(it) }
        GroupEventType.entries.forEach { roundTrip(it) }
        CmdType.entries.forEach { roundTrip(it) }
    }

    // ---------- 차량 EVENT 본문 ----------

    @Test
    fun vehicle_event_payloads() {
        roundTrip(LowEnergy(kind = EnergyKind.FUEL, pct = 12))
        roundTrip(LowEnergy(kind = EnergyKind.EV, pct = 9))
        roundTrip(HardBrake(decelMps2 = 4.8, speedBeforeKph = 96.3))
        roundTrip(HazardOn)
        roundTrip(AirbagDeployed)
        roundTrip(
            RouteChanged(
                destination = Place(35.1, 126.8, "목적지"),
                waypoints = listOf(Place(36.0, 127.0, "경유지 1"), Place(36.5, 127.2, "경유지 2", poiId = "poi_9")),
            ),
        )
        roundTrip(RouteChanged(destination = Place(35.1, 126.8, "목적지")))
        roundTrip(WaypointArrived(name = "휴게소", lat = 36.0, lon = 127.0))
        roundTrip(DestinationArrived(name = "목적지"))
        roundTrip(Stopped(durationS = 60))
        roundTrip(Stopped())
        roundTrip(Resumed(durationS = 300))
        roundTrip(Resumed())
        roundTrip(PresetMessage(presetId = "REST_PLEASE"))
    }

    // ---------- 그룹 STATE ----------

    @Test
    fun group_state() {
        val leader = MemberState(
            vehicleId = "veh_A", nickname = "아빠 차", color = "#2E7DFF", online = true,
            lat = 37.0, lon = 127.0, heading = 10.5f, speedKph = 92.0f, ageMs = 820L,
            eta = Eta(destTs = 1793003600000L, remainM = 182000, remainS = 6600),
            energy = Energy(fuelPct = 34),
            distanceToLeaderM = 0, status = MemberStatus.DRIVING,
        )
        val offline = MemberState(
            vehicleId = "veh_B", nickname = "엄마 차", color = "#FF6B2E", online = false,
            lat = null, lon = null, heading = null, speedKph = null, ageMs = null,
            eta = null, energy = null, distanceToLeaderM = null, status = MemberStatus.OFFLINE,
        )
        roundTrip(leader)
        roundTrip(offline)
        roundTrip(GroupState(groupId = "grp_1", leaderVehicleId = "veh_A", members = listOf(leader, offline)))
        roundTrip(GroupState(groupId = "grp_1", leaderVehicleId = "veh_A", members = emptyList()))
    }

    // ---------- 그룹 EVENT ----------

    @Test
    fun group_event() {
        roundTrip(
            GroupEvent(
                eventId = "evt_1", type = GroupEventType.LOW_ENERGY.name, sourceVehicleId = "veh_B",
                severity = Severity.WARN, speech = "연료가 12% 남았어요.",
                payload = DhJson.payloadOf(LowEnergy(EnergyKind.FUEL, 12)),
            ),
        )
        roundTrip(
            GroupEvent(
                eventId = "evt_2", type = GroupEventType.REST_STOP_VOTE_RESULT.name, sourceVehicleId = null,
                severity = Severity.INFO, speech = null,
                payload = buildJsonObject { put("proposalId", "prp_1"); put("accepted", 3); put("rejected", 1) },
            ),
        )
        roundTrip(
            GroupEvent(
                eventId = "evt_3", type = GroupEventType.HAZARD_ON.name, sourceVehicleId = "veh_C",
                severity = Severity.ALERT, speech = "비상등", payload = buildJsonObject { },
            ),
        )
    }

    // ---------- CMD 본문 ----------

    @Test
    fun cmd_payloads() {
        roundTrip(
            GroupJoined(
                groupId = "grp_1", name = "귀성길", leaderVehicleId = "veh_A",
                destination = Place(35.1595454, 126.8526012, "목적지", address = "어딘가"),
                topics = GroupTopics(state = Topics.groupState("grp_1"), event = Topics.groupEvent("grp_1")),
            ),
        )
        roundTrip(GroupTopics(state = "dh/v1/group/grp_1/state", event = "dh/v1/group/grp_1/event"))
        roundTrip(
            RouteFollowProposal(
                proposalId = "prp_1", destination = Place(35.1, 126.8, "목적지"),
                waypoints = listOf(Place(36.0, 127.0, "경유지")), speech = "선두차 경로를 따라갈까요?",
            ),
        )
        roundTrip(
            RouteFollowProposal(
                proposalId = "prp_2", destination = Place(35.1, 126.8, "목적지"),
                waypoints = emptyList(), speech = "경유지 없음",
            ),
        )
        roundTrip(
            RestStopProposal(
                proposalId = "prp_3",
                poi = RestAreaPoi(poiId = "ra_0012", name = "휴게소(부산방향)", lat = 36.1, lon = 127.3, distanceKm = 18.2, hasEvCharger = true),
                reason = "LOW_ENERGY", speech = "18km 앞 휴게소에 들를까요?",
            ),
        )
        roundTrip(RestAreaPoi(poiId = "ra_1", name = "휴게소", lat = 36.1, lon = 127.3, distanceKm = 5.0))
        roundTrip(GroupEnded(groupId = "grp_1", reason = "LEADER_ENDED"))
        roundTrip(TokenRevoked)
    }

    // ---------- ACK · PRESENCE ----------

    @Test
    fun ack_and_presence() {
        roundTrip(Ack(proposalId = "prp_1", accepted = true))
        roundTrip(Ack(proposalId = "prp_1", accepted = false))
        roundTrip(Presence(online = true))
        roundTrip(Presence(online = false))
    }

    // ---------- 봉투 + 본문 조합 ----------

    @Test
    fun envelopes_with_typed_payloads() {
        roundTrip(Envelope(type = VehicleEventType.LOW_ENERGY.name, vid = "veh_1", ts = 1L, seq = 1L, data = LowEnergy(EnergyKind.EV, 10)))
        roundTrip(Envelope(type = VehicleEventType.HAZARD_ON.name, vid = "veh_1", ts = 2L, seq = 2L, data = HazardOn))
        roundTrip(Envelope(type = CmdType.TOKEN_REVOKED.name, vid = "veh_1", ts = 3L, seq = 3L, data = TokenRevoked))
        roundTrip(Envelope(type = MessageTypes.ACK, vid = "veh_1", ts = 4L, seq = 4L, data = Ack("prp_1", true)))
        roundTrip(Envelope(type = MessageTypes.PRESENCE, vid = "veh_1", ts = 5L, seq = 5L, data = Presence(true)))
        roundTrip(
            Envelope(
                type = MessageTypes.STATE, vid = "server", ts = 6L, seq = 6L,
                data = GroupState("grp_1", "veh_A", emptyList()),
            ),
        )
        roundTrip(
            Envelope(
                type = MessageTypes.EVENT, vid = "server", ts = 7L, seq = 7L,
                data = GroupEvent("evt_1", "MEMBER_JOINED", "veh_B", Severity.INFO, "합류했어요.", buildJsonObject { put("nickname", JsonPrimitive("엄마 차")) }),
            ),
        )
    }
}
