package app.donghaeng.routesync

import app.donghaeng.event.GroupEventSink
import app.donghaeng.event.SpeechComposer
import app.donghaeng.group.GroupMembershipApi
import app.donghaeng.group.GroupRoster
import app.donghaeng.group.GroupRouteApi
import app.donghaeng.group.RosterMember
import app.donghaeng.group.VehicleJoinedGroup
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
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

    /** 기록 순간에 cmd 가 몇 건 나가 있었는지도 적는다 — 커밋 뒤 발행 확인(C076) */
    private class RecordingSink(private val publisher: RecordingPublisher) : GroupEventSink {
        data class Rec(val groupId: String, val type: GroupEventType, val speech: String?, val payload: JsonObject, val publishedBefore: Int)
        val recorded = mutableListOf<Rec>()
        override fun record(groupId: String, type: GroupEventType, sourceVehicleId: String?, severity: Severity, speech: String?, payload: JsonObject, audience: List<String>?): GroupEvent {
            recorded += Rec(groupId, type, speech, payload, publisher.sent.size)
            return GroupEvent("evt_${recorded.size}", type.name, sourceVehicleId, severity, speech, payload, audience)
        }
    }

    /** InMemory 도 Redis 구현과 같은 계약: 없는 제안의 ACK 는 쓰지 않고 null */
    private class InMemoryProposals : ProposalStore {
        val map = mutableMapOf<String, ProposalRecord>()
        override fun save(record: ProposalRecord) { map[record.proposalId] = record }
        override fun find(proposalId: String): ProposalRecord? = map[proposalId]
        override fun recordAck(proposalId: String, vehicleId: String, accepted: Boolean): ProposalRecord? =
            map[proposalId]?.let { r -> r.copy(acks = r.acks + (vehicleId to accepted)).also { map[proposalId] = it } }
    }

    private class FakeRoutes(var accept: Boolean = true) : GroupRouteApi {
        data class Update(val groupId: String, val leader: String, val destination: Place, val waypoints: List<Place>)
        val updates = mutableListOf<Update>()
        override fun updateRoute(groupId: String, leaderVehicleId: String, destination: Place, waypoints: List<Place>): Boolean {
            if (accept) updates += Update(groupId, leaderVehicleId, destination, waypoints)
            return accept
        }
    }

    private val publisher = RecordingPublisher()
    private val sink = RecordingSink(publisher)
    private val store = InMemoryProposals()
    private val routes = FakeRoutes()
    private val service = RouteSyncService(FakeMembership(roster), publisher, sink, SpeechComposer(), store, routes, Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC))

    private val restStop = Place(37.2963, 127.4120, "덕평휴게소")

    @Test
    fun `leader route change fans out a proposal to every other member and records a group event`() {
        val change = RouteChanged(destination = roster.destination!!, waypoints = listOf(restStop))
        val proposalId = assertNotNull(service.onRouteChanged("veh_A", change))
        assertTrue(proposalId.startsWith("prp_"))
        assertEquals(listOf(Topics.vehicleCmd("veh_B"), Topics.vehicleCmd("veh_C")), publisher.sent.map { it.topic })
        assertTrue(publisher.sent.all { it.type == CmdType.ROUTE_FOLLOW_PROPOSAL.name })
        val cmd: RouteFollowProposal = DhJson.json.decodeFromString(publisher.sent.first().json)
        assertEquals(proposalId, cmd.proposalId)
        assertEquals("덕평휴게소", cmd.waypoints.single().name)
        assertEquals("선두차가 덕평휴게소를 경유지로 추가했어요. 따라갈까요?", cmd.speech)
        val rec = sink.recorded.single()
        assertEquals("grp_1", rec.groupId); assertEquals(GroupEventType.ROUTE_CHANGED, rec.type)
        assertEquals(proposalId, rec.payload["proposalId"]!!.jsonPrimitive.content)
        val record = assertNotNull(store.find(proposalId))
        assertEquals(listOf("veh_B", "veh_C"), record.targets)
    }

    @Test
    fun `the ROUTE_CHANGED group event carries a feed sentence naming the leader and the waypoint or destination`() {
        service.onRouteChanged("veh_A", RouteChanged(destination = roster.destination!!, waypoints = listOf(restStop)))
        assertEquals("아빠 차가 경로를 바꿨어요(경유지 덕평휴게소)", sink.recorded.last().speech)
        service.onRouteChanged("veh_A", RouteChanged(destination = Place(37.7719, 128.9479, "경포해변")))
        assertEquals("아빠 차가 경로를 바꿨어요(목적지 경포해변)", sink.recorded.last().speech)
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
        assertTrue(publisher.sent.isEmpty()); assertTrue(sink.recorded.isEmpty()); assertTrue(routes.updates.isEmpty())

        val id = service.onRouteChanged("veh_A", RouteChanged(destination = roster.destination!!, waypoints = listOf(Place(37.2, 127.4, "휴게소"))))!!
        service.onAck("veh_B", Ack(id, accepted = true))
        assertEquals(mapOf("veh_B" to true), store.find(id)!!.acks)
        assertTrue(!store.find(id)!!.allAnswered)
        service.onAck("veh_C", Ack(id, accepted = false))
        assertTrue(store.find(id)!!.allAnswered)
    }

    // ---------------------------------------------------------------- C032 그룹 경로 갱신 · 늦게 합류한 차

    @Test
    fun `a leader route change updates the group route, and a refused update (leader changed meanwhile) sends nothing`() {
        val change = RouteChanged(destination = Place(38.2070, 128.5918, "속초"), waypoints = listOf(restStop))
        service.onRouteChanged("veh_A", change)
        assertEquals(listOf(FakeRoutes.Update("grp_1", "veh_A", change.destination, change.waypoints)), routes.updates)

        routes.accept = false
        publisher.sent.clear()
        assertNull(service.onRouteChanged("veh_A", change))
        assertTrue(publisher.sent.isEmpty())
        assertEquals(1, sink.recorded.size)
    }

    @Test
    fun `a member that joins later gets the current route with waypoints as its own proposal, without waypoints nothing is sent`() {
        service.onVehicleJoined(VehicleJoinedGroup("grp_1", "veh_D", "veh_A", Place(38.2070, 128.5918, "속초"), listOf(restStop)))
        val sent = publisher.sent.single()
        assertEquals(Topics.vehicleCmd("veh_D"), sent.topic)
        val cmd: RouteFollowProposal = DhJson.json.decodeFromString(sent.json)
        assertEquals("속초", cmd.destination.name)
        assertEquals(listOf(restStop), cmd.waypoints)
        assertEquals("선두차가 덕평휴게소를 경유지로 추가했어요. 따라갈까요?", cmd.speech)
        assertEquals(listOf("veh_D"), store.find(cmd.proposalId)!!.targets, "대상은 합류한 차 하나")

        publisher.sent.clear()
        service.onVehicleJoined(VehicleJoinedGroup("grp_1", "veh_E", "veh_A", Place(38.2070, 128.5918, "속초"), emptyList()))
        assertTrue(publisher.sent.isEmpty(), "경유지가 없으면 GROUP_JOINED 의 최신 목적지로 충분하다")
    }

    // ---------------------------------------------------------------- C076 커밋 뒤 발행

    @Test
    fun `proposal commands go out only after the group event is recorded`() {
        service.onRouteChanged("veh_A", RouteChanged(destination = roster.destination!!, waypoints = listOf(restStop)))
        assertEquals(0, sink.recorded.single().publishedBefore, "cmd 는 기록(커밋) 뒤에 나간다")
        assertEquals(2, publisher.sent.size)
    }

    // ---------------------------------------------------------------- C105 차량이 보낸 경로 검증

    @Test
    fun `invalid routes from the car are dropped and names are cleaned before speech and storage`() {
        assertNull(service.onRouteChanged("veh_A", RouteChanged(destination = Place(95.0, 127.0, "북극"))), "위도 범위 밖")
        assertNull(service.onRouteChanged("veh_A", RouteChanged(destination = roster.destination!!, waypoints = listOf(Place(37.0, Double.NaN, "x")))))
        assertNull(service.onRouteChanged("veh_A", RouteChanged(destination = roster.destination!!, waypoints = List(11) { restStop })), "경유지 10개 초과")
        assertTrue(publisher.sent.isEmpty() && routes.updates.isEmpty())

        val messy = Place(37.2963, 127.4120, "덕평‮휴게소\u0000" + "가".repeat(200), poiId = "p".repeat(100), address = "  경기 이천  ")
        service.onRouteChanged("veh_A", RouteChanged(destination = Place(35.0, 127.0, "   "), waypoints = listOf(messy)))
        val stored = routes.updates.single()
        assertEquals("목적지", stored.destination.name, "빈 이름은 '목적지'")
        val wp = stored.waypoints.single()
        assertEquals(RouteRules.MAX_PLACE_NAME, wp.name.length)
        assertTrue(wp.name.startsWith("덕평 휴게소 가"), wp.name)
        assertNull(wp.poiId, "너무 긴 POI ID 는 버린다")
        assertEquals("경기 이천", wp.address)
        val cmd: RouteFollowProposal = DhJson.json.decodeFromString(publisher.sent.first().json)
        assertEquals(wp, cmd.waypoints.single(), "멤버에게도 정리한 값")
        assertTrue(cmd.speech.length < 80, cmd.speech)
        assertEquals("목적지", sink.recorded.last().payload["destination"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(1, sink.recorded.last().payload["waypoints"]!!.jsonArray.size)
    }

    // ---------------------------------------------------------------- C110 ACK 검증

    @Test
    fun `acks with a malformed id, for an unknown proposal or from a car that was not asked are dropped`() {
        val id = service.onRouteChanged("veh_A", RouteChanged(destination = roster.destination!!, waypoints = listOf(restStop)))!!
        service.onAck("veh_B", Ack("x".repeat(5_000), accepted = true))
        service.onAck("veh_Z", Ack("prp_" + "0".repeat(26), accepted = true))
        assertEquals(setOf(id), store.map.keys, "모르는 제안의 ACK 는 기록을 만들지 않는다")
        service.onAck("veh_Z", Ack(id, accepted = true))
        service.onAck("veh_A", Ack(id, accepted = true)) // 선두 자신도 대상이 아니다
        assertEquals(emptyMap(), store.find(id)!!.acks, "대상이 아닌 차의 ACK 는 집계하지 않는다")
        service.onAck("veh_B", Ack(id, accepted = true))
        assertEquals(mapOf("veh_B" to true), store.find(id)!!.acks)
    }
}
