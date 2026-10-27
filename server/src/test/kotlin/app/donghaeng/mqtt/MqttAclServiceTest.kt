package app.donghaeng.mqtt

import app.donghaeng.group.GroupMembershipApi
import app.donghaeng.mqtt.MqttAclService.Action.PUBLISH
import app.donghaeng.mqtt.MqttAclService.Action.SUBSCRIBE
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** docs/03 §4 ACL 표 — 차량은 자기 토픽만, 그룹 토픽은 소속 그룹만, 서버는 dh/v1 전부 */
class MqttAclServiceTest {

    private val membership = object : GroupMembershipApi {
        override fun activeGroupIdOf(vehicleId: String): String? = if (vehicleId == "veh_A") "grp_1" else null
    }
    private val acl = MqttAclService(membership)

    private fun allowed(user: String?, action: MqttAclService.Action, topic: String?) = acl.decide(user, action, topic).allow

    @Test
    fun `vehicle may publish only its own telemetry-event-ack-presence`() {
        listOf("telemetry", "event", "ack", "presence").forEach { leaf ->
            assertTrue(allowed("veh_A", PUBLISH, "dh/v1/vehicle/veh_A/$leaf"), leaf)
            assertFalse(allowed("veh_A", PUBLISH, "dh/v1/vehicle/veh_B/$leaf"), "other vehicle $leaf")
        }
        assertFalse(allowed("veh_A", PUBLISH, "dh/v1/vehicle/veh_A/cmd"), "cmd is server → vehicle only")
        assertFalse(allowed("veh_A", PUBLISH, "dh/v1/group/grp_1/event"), "group topics are server-published")
        assertFalse(allowed("veh_A", PUBLISH, "dh/v1/group/grp_1/state"))
    }

    @Test
    fun `vehicle may subscribe only its own cmd and its current group's state-event`() {
        assertTrue(allowed("veh_A", SUBSCRIBE, "dh/v1/vehicle/veh_A/cmd"))
        assertFalse(allowed("veh_A", SUBSCRIBE, "dh/v1/vehicle/veh_B/cmd"))
        assertFalse(allowed("veh_A", SUBSCRIBE, "dh/v1/vehicle/veh_A/telemetry"), "no eavesdropping on own uplink either")
        assertTrue(allowed("veh_A", SUBSCRIBE, "dh/v1/group/grp_1/state"))
        assertTrue(allowed("veh_A", SUBSCRIBE, "dh/v1/group/grp_1/event"))
        assertFalse(allowed("veh_A", SUBSCRIBE, "dh/v1/group/grp_2/state"), "not a member")
        assertFalse(allowed("veh_B", SUBSCRIBE, "dh/v1/group/grp_1/state"), "veh_B has no group")
        assertFalse(allowed("veh_A", SUBSCRIBE, "dh/v1/group/grp_1/cmd"), "unknown group leaf")
    }

    @Test
    fun `wildcards, shared subscriptions and foreign prefixes are denied for vehicles`() {
        assertFalse(allowed("veh_A", SUBSCRIBE, "dh/v1/vehicle/+/cmd"))
        assertFalse(allowed("veh_A", SUBSCRIBE, "dh/v1/#"))
        assertFalse(allowed("veh_A", SUBSCRIBE, "\$share/server/dh/v1/vehicle/+/#"))
        assertFalse(allowed("veh_A", SUBSCRIBE, "\$SYS/brokers"))
        assertFalse(allowed("veh_A", PUBLISH, "other/topic"))
        assertFalse(allowed("veh_A", PUBLISH, "dh/v1/vehicle/veh_A/telemetry/extra"), "exactly 5 segments")
    }

    @Test
    fun `server may use everything under dh-v1 including the shared subscription, nothing else`() {
        assertTrue(allowed("srv_01ABC", SUBSCRIBE, "\$share/server/dh/v1/vehicle/+/#"))
        assertTrue(allowed("srv_01ABC", PUBLISH, "dh/v1/vehicle/veh_A/cmd"))
        assertTrue(allowed("srv_01ABC", PUBLISH, "dh/v1/group/grp_1/state"))
        assertFalse(allowed("srv_01ABC", PUBLISH, "other/topic"))
        assertFalse(allowed("srv_01ABC", SUBSCRIBE, "\$SYS/#"))
    }

    @Test
    fun `unknown principals and empty input are denied`() {
        assertFalse(allowed("usr_01ABC", SUBSCRIBE, "dh/v1/vehicle/usr_01ABC/cmd"))
        assertFalse(allowed("admin", PUBLISH, "dh/v1/vehicle/veh_A/telemetry"))
        assertFalse(allowed(null, PUBLISH, "dh/v1/vehicle/veh_A/telemetry"))
        assertFalse(allowed("veh_A", PUBLISH, null))
        assertFalse(allowed("", PUBLISH, ""))
    }
}
