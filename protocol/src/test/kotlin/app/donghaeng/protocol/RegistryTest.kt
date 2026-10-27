package app.donghaeng.protocol

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 타입 enum 과 직렬화기 레지스트리의 정합성.
 * (`SerialDescriptor.serialName` 비교 때문에 ExperimentalSerializationApi opt-in)
 */
@OptIn(ExperimentalSerializationApi::class)
class RegistryTest {

    @Test
    fun vehicle_event_serializer_maps_every_type() {
        val expected = mapOf(
            VehicleEventType.LOW_ENERGY to LowEnergy.serializer(),
            VehicleEventType.HARD_BRAKE to HardBrake.serializer(),
            VehicleEventType.HAZARD_ON to HazardOn.serializer(),
            VehicleEventType.AIRBAG_DEPLOYED to AirbagDeployed.serializer(),
            VehicleEventType.ROUTE_CHANGED to RouteChanged.serializer(),
            VehicleEventType.WAYPOINT_ARRIVED to WaypointArrived.serializer(),
            VehicleEventType.DESTINATION_ARRIVED to DestinationArrived.serializer(),
            VehicleEventType.STOPPED to Stopped.serializer(),
            VehicleEventType.RESUMED to Resumed.serializer(),
            VehicleEventType.PRESET_MESSAGE to PresetMessage.serializer(),
        )
        assertEquals(VehicleEventType.entries.toSet(), expected.keys)
        VehicleEventType.entries.forEach { type ->
            assertEquals(
                expected.getValue(type).descriptor.serialName,
                vehicleEventSerializer(type).descriptor.serialName,
                type.name,
            )
        }
    }

    @Test
    fun cmd_serializer_maps_every_type() {
        val expected = mapOf(
            CmdType.GROUP_JOINED to GroupJoined.serializer(),
            CmdType.ROUTE_FOLLOW_PROPOSAL to RouteFollowProposal.serializer(),
            CmdType.REST_STOP_PROPOSAL to RestStopProposal.serializer(),
            CmdType.GROUP_ENDED to GroupEnded.serializer(),
            CmdType.TOKEN_REVOKED to TokenRevoked.serializer(),
        )
        assertEquals(CmdType.entries.toSet(), expected.keys)
        CmdType.entries.forEach { type ->
            assertEquals(expected.getValue(type).descriptor.serialName, cmdSerializer(type).descriptor.serialName, type.name)
        }
    }

    @Test
    fun group_event_types_are_superset_of_vehicle_event_types() {
        val groupNames = GroupEventType.entries.map { it.name }.toSet()
        VehicleEventType.entries.forEach { v ->
            assertTrue(v.name in groupNames, "GroupEventType 에 ${v.name} 이 없습니다")
            assertTrue(GroupEventType.valueOf(v.name).isVehicleEvent)
        }
        val serverOnly = setOf(
            "MEMBER_JOINED", "MEMBER_LEFT", "LEADER_CHANGED", "MEMBER_OFFLINE", "REST_STOP_PROPOSAL",
            "REST_STOP_VOTE_RESULT", "DEVIATION", "PASSENGER_MESSAGE", "GROUP_ENDED",
        )
        assertEquals(VehicleEventType.entries.map { it.name }.toSet() + serverOnly, groupNames)
        serverOnly.forEach { assertFalse(GroupEventType.valueOf(it).isVehicleEvent, it) }
    }

    @Test
    fun message_type_constants() {
        assertEquals("TELEMETRY", MessageTypes.TELEMETRY)
        assertEquals("STATE", MessageTypes.STATE)
        assertEquals("EVENT", MessageTypes.EVENT)
        assertEquals("ACK", MessageTypes.ACK)
        assertEquals("PRESENCE", MessageTypes.PRESENCE)
        // 고정 타입명과 이벤트/CMD enum 이름이 겹치지 않는다
        val fixed = setOf(MessageTypes.TELEMETRY, MessageTypes.STATE, MessageTypes.EVENT, MessageTypes.ACK, MessageTypes.PRESENCE)
        assertTrue(VehicleEventType.entries.none { it.name in fixed })
        assertTrue(CmdType.entries.none { it.name in fixed })
    }

    @Test
    fun unknown_group_event_type_string_still_decodes() {
        val e = DhJson.json.decodeFromString<GroupEvent>(
            """{"eventId":"e","type":"SOMETHING_FROM_THE_FUTURE","severity":"INFO","payload":{}}""",
        )
        assertEquals("SOMETHING_FROM_THE_FUTURE", e.type)
        assertNull(e.eventType)
    }
}
