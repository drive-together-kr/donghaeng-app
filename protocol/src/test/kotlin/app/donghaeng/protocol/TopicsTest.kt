package app.donghaeng.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * (6) 토픽 문자열 생성과 ID 해석.
 */
class TopicsTest {

    @Test
    fun vehicle_topics_match_spec() {
        assertEquals("dh/v1/vehicle/veh_01H/telemetry", Topics.vehicleTelemetry("veh_01H"))
        assertEquals("dh/v1/vehicle/veh_01H/event", Topics.vehicleEvent("veh_01H"))
        assertEquals("dh/v1/vehicle/veh_01H/ack", Topics.vehicleAck("veh_01H"))
        assertEquals("dh/v1/vehicle/veh_01H/cmd", Topics.vehicleCmd("veh_01H"))
        assertEquals("dh/v1/vehicle/veh_01H/presence", Topics.vehiclePresence("veh_01H"))
    }

    @Test
    fun group_topics_match_spec() {
        assertEquals("dh/v1/group/grp_01H/state", Topics.groupState("grp_01H"))
        assertEquals("dh/v1/group/grp_01H/event", Topics.groupEvent("grp_01H"))
    }

    @Test
    fun shared_subscription_filter() {
        assertEquals("\$share/server/dh/v1/vehicle/+/#", Topics.SERVER_SHARED_VEHICLE_SUB)
        assertEquals("dh/v1", Topics.PREFIX)
    }

    @Test
    fun vehicle_id_is_parsed_from_every_vehicle_topic() {
        listOf(
            Topics.vehicleTelemetry("veh_01H"),
            Topics.vehicleEvent("veh_01H"),
            Topics.vehicleAck("veh_01H"),
            Topics.vehicleCmd("veh_01H"),
            Topics.vehiclePresence("veh_01H"),
        ).forEach { topic ->
            assertEquals("veh_01H", Topics.vehicleIdFromTopic(topic), topic)
            assertNull(Topics.groupIdFromTopic(topic), topic)
        }
    }

    @Test
    fun group_id_is_parsed_from_every_group_topic() {
        listOf(Topics.groupState("grp_01H"), Topics.groupEvent("grp_01H")).forEach { topic ->
            assertEquals("grp_01H", Topics.groupIdFromTopic(topic), topic)
            assertNull(Topics.vehicleIdFromTopic(topic), topic)
        }
    }

    @Test
    fun shared_prefix_is_tolerated_when_parsing() {
        assertEquals("veh_9", Topics.vehicleIdFromTopic("\$share/server/dh/v1/vehicle/veh_9/event"))
        assertEquals("grp_9", Topics.groupIdFromTopic("\$share/other/dh/v1/group/grp_9/state"))
        assertNull(Topics.vehicleIdFromTopic("\$share/dh/v1/vehicle/veh_9/event")) // 그룹명 없는 잘못된 형식
    }

    @Test
    fun malformed_topics_return_null() {
        listOf(
            "",
            "dh",
            "dh/v1/vehicle",
            "dh/v1/vehicle/veh_1",                 // 세그먼트 부족
            "dh/v1/vehicle/veh_1/event/extra",     // 세그먼트 초과
            "dh/v1/vehicle//telemetry",            // 빈 ID
            "dh/v2/vehicle/veh_1/telemetry",       // 다른 버전
            "xx/v1/vehicle/veh_1/telemetry",       // 다른 접두사
            "dh/v1/vehicle/+/telemetry",           // 와일드카드
            "dh/v1/vehicle/#/telemetry",
            "/dh/v1/vehicle/veh_1/telemetry",      // 선행 슬래시
            "dh/v1/vehicle/veh_1/telemetry/",      // 후행 슬래시
        ).forEach { topic ->
            assertNull(Topics.vehicleIdFromTopic(topic), "vehicle: '$topic'")
            assertNull(Topics.groupIdFromTopic(topic), "group: '$topic'")
        }
        assertNull(Topics.groupIdFromTopic("dh/v1/group//state"))
        assertNull(Topics.groupIdFromTopic("dh/v1/group/+/state"))
    }

    @Test
    fun vehicle_topic_kind() {
        assertEquals(Topics.VehicleTopicKind.TELEMETRY, Topics.vehicleTopicKind(Topics.vehicleTelemetry("v")))
        assertEquals(Topics.VehicleTopicKind.EVENT, Topics.vehicleTopicKind(Topics.vehicleEvent("v")))
        assertEquals(Topics.VehicleTopicKind.ACK, Topics.vehicleTopicKind(Topics.vehicleAck("v")))
        assertEquals(Topics.VehicleTopicKind.CMD, Topics.vehicleTopicKind(Topics.vehicleCmd("v")))
        assertEquals(Topics.VehicleTopicKind.PRESENCE, Topics.vehicleTopicKind(Topics.vehiclePresence("v")))
        assertNull(Topics.vehicleTopicKind("dh/v1/vehicle/v/unknown"))
        assertNull(Topics.vehicleTopicKind(Topics.groupState("g")))
    }

    @Test
    fun ids_with_dots_and_dashes_survive() {
        // ULID 접두사 형식 외의 ID 도 세그먼트에 슬래시만 없으면 통과
        assertEquals("veh_01H-abc.def", Topics.vehicleIdFromTopic(Topics.vehicleTelemetry("veh_01H-abc.def")))
    }
}
