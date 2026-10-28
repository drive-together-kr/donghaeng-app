package app.donghaeng.event

import app.donghaeng.group.GroupMembershipApi
import app.donghaeng.group.GroupRoster
import app.donghaeng.identity.IdentityApi
import app.donghaeng.identity.VehicleInfo
import app.donghaeng.presence.PresenceApi
import app.donghaeng.protocol.GroupEvent
import app.donghaeng.protocol.GroupEventType
import app.donghaeng.protocol.GroupState
import app.donghaeng.protocol.MemberState
import app.donghaeng.protocol.MemberStatus
import app.donghaeng.protocol.Severity
import app.donghaeng.protocol.VehicleEventType
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionOperations
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VehicleEventServiceTest {

    private class FakeMembership(var groupOf: Map<String, String>) : GroupMembershipApi {
        override fun activeGroupIdOf(vehicleId: String): String? = groupOf[vehicleId]
        override fun rosterOf(groupId: String): GroupRoster? = null
        override fun activeRosters(): List<GroupRoster> = emptyList()
    }

    private class FakeIdentity(private val names: Map<String, String>) : IdentityApi {
        override fun vehicle(vehicleId: String): VehicleInfo? = names[vehicleId]?.let { VehicleInfo(vehicleId, "usr", it, "HYUNDAI", "GRANDEUR", true, true) }
        override fun vehicles(vehicleIds: Collection<String>): Map<String, VehicleInfo> = vehicleIds.mapNotNull { vehicle(it) }.associateBy { it.vehicleId }
        override fun lockVehicleForShare(vehicleId: String): VehicleInfo? = vehicle(vehicleId)
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

    private class RecordingSink(private val tx: RecordingTx) : GroupEventSink {
        data class Rec(val groupId: String, val type: GroupEventType, val vid: String?, val severity: Severity, val speech: String?, val payload: JsonObject, val audience: List<String>?, val inTx: Boolean)
        val recorded = mutableListOf<Rec>()
        override fun record(groupId: String, type: GroupEventType, sourceVehicleId: String?, severity: Severity, speech: String?, payload: JsonObject, audience: List<String>?): GroupEvent {
            recorded += Rec(groupId, type, sourceVehicleId, severity, speech, payload, audience, tx.active)
            return GroupEvent("evt_${recorded.size}", type.name, sourceVehicleId, severity, speech, payload, audience)
        }
    }

    /** 트랜잭션 안인지 기록하는 [TransactionOperations] — 저장만 트랜잭션 안에서 하는지 확인 */
    private class RecordingTx : TransactionOperations {
        var active = false
        var count = 0
        override fun <T> execute(action: TransactionCallback<T>): T? {
            count++
            active = true
            try { return action.doInTransaction(SimpleTransactionStatus()) } finally { active = false }
        }
    }

    /** 그룹별 스냅샷을 테스트가 정한다. [fail] 이면 명단 DB 오류처럼 예외 */
    private class FakePresence(private val tx: RecordingTx, var states: Map<String, GroupState> = emptyMap(), var fail: Boolean = false) : PresenceApi {
        var calls = 0
        var callsInTx = 0
        override fun snapshotOf(groupId: String): GroupState? {
            calls++
            if (tx.active) callsInTx++
            if (fail) throw IllegalStateException("roster query failed")
            return states[groupId]
        }
    }

    private fun member(vid: String, dist: Int?) = MemberState(vid, vid, "#2E7DFF", true, null, null, null, 80f, 500, null, null, dist, MemberStatus.DRIVING)

    /** 선두 A(0) — B(1.0 km 뒤) — C(2.1 km 뒤), 모두 grp_1 */
    private val convoy = GroupState("grp_1", "veh_A", listOf(member("veh_A", 0), member("veh_B", 1_000), member("veh_C", 2_100)))

    private val dedup = InMemoryDedup()
    private val tx = RecordingTx()
    private val sink = RecordingSink(tx)
    private val presence = FakePresence(tx)
    private val service = VehicleEventService(
        FakeMembership(mapOf("veh_A" to "grp_1")),
        FakeIdentity(mapOf("veh_A" to "아빠 차")),
        dedup, SpeechComposer(), sink, presence, tx,
    )

    private fun convoyService(states: Map<String, GroupState> = mapOf("grp_1" to convoy)): VehicleEventService {
        presence.states = states
        return VehicleEventService(
            FakeMembership(mapOf("veh_A" to "grp_1", "veh_B" to "grp_1", "veh_C" to "grp_1", "veh_D" to "grp_1")),
            FakeIdentity(mapOf("veh_A" to "아빠 차", "veh_B" to "둘째 차", "veh_C" to "삼촌 차")),
            InMemoryDedup(), SpeechComposer(), sink, presence, tx,
        )
    }

    private fun hardBrake() = buildJsonObject { put("decelMps2", 5.6); put("speedBeforeKph", 72.0) }

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
        val svc = VehicleEventService(FakeMembership(mapOf("veh_B" to "grp_2")), FakeIdentity(emptyMap()), InMemoryDedup(), SpeechComposer(), sink, presence, tx)
        val e = svc.handle("veh_B", VehicleEventType.LOW_ENERGY, buildJsonObject { put("kind", "EV"); put("pct", 8) })
        assertEquals("veh_B 배터리가 8% 남았어요.", e!!.speech)
    }

    // ---------------------------------------------------------------- 급제동 수신 대상 (docs/03 §5.4 audience)

    @Test
    fun `leader brakes - every car behind is addressed`() {
        val e = convoyService().handle("veh_A", VehicleEventType.HARD_BRAKE, hardBrake())!!
        assertEquals(listOf("veh_B", "veh_C"), e.audience)
        assertEquals(listOf("veh_B", "veh_C"), sink.recorded.single().audience, "저장되는 값도 같다")
        assertEquals("앞쪽 아빠 차가 급제동했어요. 주의하세요.", e.speech)
    }

    @Test
    fun `last car brakes - stored but nobody is addressed`() {
        // impl_02: A 패널에 상대가 "뒤 2.1 km" — 그 뒤차의 급제동을 앞차 A 가 '앞쪽 급제동'으로 들으면 안 된다
        val e = convoyService().handle("veh_C", VehicleEventType.HARD_BRAKE, hardBrake())
        assertNotNull(e, "이벤트는 기록(DB·REST recentEvents 원문)으로만 남는다 — 어느 차량·companion 화면에도 안 나온다")
        assertEquals(emptyList(), e.audience)
        assertEquals(1, sink.recorded.size)
    }

    @Test
    fun `middle car brakes - only the cars behind it, never the car ahead or itself`() {
        val e = convoyService().handle("veh_B", VehicleEventType.HARD_BRAKE, hardBrake())!!
        assertEquals(listOf("veh_C"), e.audience)
        assertFalse("veh_A" in e.audience!!, "앞차 A 제외")
        assertFalse("veh_B" in e.audience!!, "급제동한 차 자신 제외")
    }

    @Test
    fun `unknown positions are included and a missing snapshot means the whole group`() {
        val withUnknown = convoy.copy(members = convoy.members + member("veh_D", null))
        assertEquals(listOf("veh_C", "veh_D"), convoyService(mapOf("grp_1" to withUnknown)).handle("veh_B", VehicleEventType.HARD_BRAKE, hardBrake())!!.audience, "위치 모르는 D 포함")

        val sourceUnknown = convoy.copy(members = listOf(member("veh_A", 0), member("veh_B", null), member("veh_C", 2_100)))
        assertEquals(listOf("veh_A", "veh_C"), convoyService(mapOf("grp_1" to sourceUnknown)).handle("veh_B", VehicleEventType.HARD_BRAKE, hardBrake())!!.audience, "출처 위치를 모르면 나머지 전원")

        assertNull(convoyService(emptyMap()).handle("veh_B", VehicleEventType.HARD_BRAKE, hardBrake())!!.audience, "스냅샷 없음 → 그룹 전체(차량이 다시 판정)")
        presence.fail = true
        assertNull(convoyService().handle("veh_B", VehicleEventType.HARD_BRAKE, hardBrake())!!.audience, "스냅샷 읽기 실패 → 그룹 전체")
    }

    @Test
    fun `the audience snapshot is read outside the write transaction and only the record runs inside it`() {
        // PostgreSQL: 트랜잭션 안의 실패한 문장은 같은 트랜잭션의 INSERT 까지 거부한다(25P02) — 실제 재현은 PresenceIT
        val svc = convoyService()
        assertNotNull(svc.handle("veh_A", VehicleEventType.HARD_BRAKE, hardBrake()))
        presence.fail = true
        assertNotNull(svc.handle("veh_B", VehicleEventType.HARD_BRAKE, hardBrake()), "읽기 실패여도 저장된다")
        assertEquals(2, presence.calls)
        assertEquals(0, presence.callsInTx, "스냅샷은 트랜잭션 밖에서 읽는다")
        assertEquals(2, tx.count)
        assertTrue(sink.recorded.all { it.inTx }, "저장은 트랜잭션 안")
    }

    @Test
    fun `other event types carry no audience and do not read the snapshot`() {
        val svc = convoyService()
        assertNull(svc.handle("veh_C", VehicleEventType.LOW_ENERGY, buildJsonObject { put("kind", "FUEL"); put("pct", 12) })!!.audience)
        assertNull(svc.handle("veh_C", VehicleEventType.HAZARD_ON, buildJsonObject { })!!.audience)
        assertNull(svc.handle("veh_C", VehicleEventType.AIRBAG_DEPLOYED, buildJsonObject { })!!.audience)
        assertNull(svc.handle("veh_C", VehicleEventType.STOPPED, buildJsonObject { })!!.audience)
        assertEquals(0, presence.calls)
        assertTrue(sink.recorded.all { it.audience == null })
    }
}
