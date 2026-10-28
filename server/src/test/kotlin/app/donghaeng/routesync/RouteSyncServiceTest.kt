package app.donghaeng.routesync

import app.donghaeng.event.GroupEventSink
import app.donghaeng.event.SpeechComposer
import app.donghaeng.group.GroupMembershipApi
import app.donghaeng.group.GroupRoster
import app.donghaeng.group.RosterMember
import app.donghaeng.mqtt.MqttPublisher
import app.donghaeng.protocol.Ack
import app.donghaeng.protocol.CmdType
import app.donghaeng.protocol.DhJson
import app.donghaeng.protocol.GroupEvent
import app.donghaeng.protocol.GroupEventType
import app.donghaeng.protocol.Place
import app.donghaeng.protocol.RouteChanged
import app.donghaeng.protocol.RouteFollowProposal
import app.donghaeng.protocol.Severity
import app.donghaeng.protocol.Topics
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RouteSyncServiceTest {

    private val roster = GroupRoster(
        "grp_1", "귀성길", "veh_A", Place(35.1595, 126.8526, "광주송정역"),
        listOf(RosterMember("veh_A", "아빠 차", "#1", "LEADER", 0), RosterMember("veh_B", "엄마 차", "#2", "MEMBER", 0), RosterMember("veh_C", "삼촌 차", "#3", "MEMBER", 0)),
    )

    private class FakeMembership(val roster: GroupRoster) : GroupMembershipApi {
        override fun activeGroupIdOf(vehicleId: String): String? = if (roster.members.any { it.vehicleId == vehicleId }) roster.groupId else null
        override fun rosterOf(groupId: String): GroupRoster? = if (groupId == roster.groupId) roster else null
        override fun activeRosters(): List<GroupRoster> = listOf(roster)
    }

    private class RecordingPublisher : MqttPublisher {
        data class Sent(val topic: String, val type: String, val json: String)
        val sent = mutableListOf<Sent>()
        override val connected = true
        override fun <T> publish(topic: String, type: String, data: T, serializer: KSerializer<T>, qos: Int, retain: Boolean): Boolean {
            sent += Sent(topic, type, DhJson.json.encodeToString(serializer, data)); return true
        }
        override fun clearRetained(topic: String) = true
    }

    private class RecordingSink : GroupEventSink {
        val recorded = mutableListOf<Triple<String, GroupEventType, JsonObject>>()
        override fun record(groupId: String, type: GroupEventType, sourceVehicleId: String?, severity: Severity, speech: String?, payload: JsonObject, audience: List<String>?): GroupEvent {
            recorded += Triple(groupId, type, payload)
            return GroupEvent("evt_${recorded.size}", type.name, sourceVehicleId, severity, speech, payload, audience)
        }
    }

    private class InMemoryProposals : ProposalStore {
        val map = mutableMapOf<String, ProposalRecord>()
        override fun save(record: ProposalRecord) { map[record.proposalId] = record }
        override fun find(proposalId: String): ProposalRecord? = map[proposalId]
        override fun recordAck(proposalId: String, vehicleId: String, accepted: Boolean): ProposalRecord? =
            map[proposalId]?.let { r -> r.copy(acks = r.acks + (vehicleId to accepted)).also { map[proposalId] = it } }
    }

    private val publisher = RecordingPublisher()
    private val sink = RecordingSink()
    private val store = InMemoryProposals()
    private val service = RouteSyncService(FakeMembership(roster), publisher, sink, SpeechComposer(), store, Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC))

    @Test
    fun `leader route change fans out a proposal to every other member and records a group event`() {
        val change = RouteChanged(destination = roster.destination!!, waypoints = listOf(Place(37.2963, 127.4120, "덕평휴게소")))
        val proposalId = assertNotNull(service.onRouteChanged("veh_A", change))
        assertTrue(proposalId.startsWith("prp_"))
        assertEquals(listOf(Topics.vehicleCmd("veh_B"), Topics.vehicleCmd("veh_C")), publisher.sent.map { it.topic })
        assertTrue(publisher.sent.all { it.type == CmdType.ROUTE_FOLLOW_PROPOSAL.name })
        val cmd: RouteFollowProposal = DhJson.json.decodeFromString(publisher.sent.first().json)
        assertEquals(proposalId, cmd.proposalId)
        assertEquals("덕평휴게소", cmd.waypoints.single().name)
        assertEquals("선두차가 덕평휴게소를 경유지로 추가했어요. 따라갈까요?", cmd.speech)
        val (gid, type, payload) = sink.recorded.single()
        assertEquals("grp_1", gid); assertEquals(GroupEventType.ROUTE_CHANGED, type)
        assertEquals(proposalId, payload["proposalId"]!!.jsonPrimitive.content)
        val record = assertNotNull(store.find(proposalId))
        assertEquals(listOf("veh_B", "veh_C"), record.targets)
    }

    @Test
    fun `destination change without waypoints uses the destination wording`() {
        service.onRouteChanged("veh_A", RouteChanged(destination = Place(37.7719, 128.9479, "경포해변")))
        val cmd: RouteFollowProposal = DhJson.json.decodeFromString(publisher.sent.first().json)
        assertEquals("선두차가 목적지를 경포해변으로 바꿨어요. 따라갈까요?", cmd.speech)
        publisher.sent.clear()
        service.onRouteChanged("veh_A", RouteChanged(destination = Place(35.1152, 129.0403, "부산역")))
        val cmd2: RouteFollowProposal = DhJson.json.decodeFromString(publisher.sent.first().json)
        assertEquals("선두차가 목적지를 부산역으로 바꿨어요. 따라갈까요?", cmd2.speech)
    }

    @Test
    fun `non leaders and strangers are ignored, acks are aggregated until everyone answered`() {
        assertNull(service.onRouteChanged("veh_B", RouteChanged(destination = roster.destination!!)), "member is not the leader")
        assertNull(service.onRouteChanged("veh_X", RouteChanged(destination = roster.destination!!)), "not in a group")
        assertTrue(publisher.sent.isEmpty()); assertTrue(sink.recorded.isEmpty())

        val id = service.onRouteChanged("veh_A", RouteChanged(destination = roster.destination!!, waypoints = listOf(Place(37.2, 127.4, "휴게소"))))!!
        service.onAck("veh_B", Ack(id, accepted = true))
        assertEquals(mapOf("veh_B" to true), store.find(id)!!.acks)
        assertTrue(!store.find(id)!!.allAnswered)
        service.onAck("veh_C", Ack(id, accepted = false))
        assertTrue(store.find(id)!!.allAnswered)
        service.onAck("veh_Z", Ack("prp_unknown", accepted = true))   // 모르는 제안 → 로그만
    }
}
