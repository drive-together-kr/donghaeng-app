package app.donghaeng.event

import app.donghaeng.group.Caller
import app.donghaeng.group.GroupAccessApi
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionOperations
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
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
        val released = mutableListOf<String>()
        override fun firstWithin(vehicleId: String, type: String, ttl: Duration): Boolean {
            val key = "$vehicleId:$type"
            if (seen.containsKey(key)) return false
            seen[key] = ttl
            return true
        }
        override fun release(vehicleId: String, type: String) {
            seen.remove("$vehicleId:$type")
            released += "$vehicleId:$type"
        }
    }

    private class RecordingSink(private val tx: RecordingTx) : GroupEventSink {
        data class Rec(val groupId: String, val type: GroupEventType, val vid: String?, val severity: Severity, val speech: String?, val payload: JsonObject, val audience: List<String>?, val inTx: Boolean)
        val recorded = mutableListOf<Rec>()
        /** true 면 DB 오류처럼 던진다 */
        var fail = false
        override fun record(groupId: String, type: GroupEventType, sourceVehicleId: String?, severity: Severity, speech: String?, payload: JsonObject, audience: List<String>?): GroupEvent {
            if (fail) throw IllegalStateException("db down")
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

    /** 그룹별 스냅샷을 테스트가 정한다. [fail] 이면 명단 DB 오류처럼 예외. [lags] = 차별 시계 지연 기준값 */
    private class FakePresence(private val tx: RecordingTx, var states: Map<String, GroupState> = emptyMap(), var fail: Boolean = false) : PresenceApi {
        var calls = 0
        var callsInTx = 0
        var lags: Map<String, Long> = emptyMap()
        override fun snapshotOf(groupId: String): GroupState? {
            calls++
            if (tx.active) callsInTx++
            if (fail) throw IllegalStateException("roster query failed")
            return states[groupId]
        }
        override fun clockLagMs(vehicleId: String): Long? = lags[vehicleId]
    }

    /** DB 기준 소속(저장 트랜잭션 안의 그룹 행 잠금 대역). [groupOf] 에 없으면 '진행 중 그룹 없음(끝났거나 나감)' */
    private class FakeAccess(private val tx: RecordingTx, var groupOf: Map<String, String>) : GroupAccessApi {
        var lockCalls = 0
        var lockCallsInTx = 0
        override fun lockActiveMemberVehicle(caller: Caller.Vehicle, groupId: String): VehicleInfo = throw UnsupportedOperationException()
        override fun requireViewer(caller: Caller, groupId: String): Boolean = throw UnsupportedOperationException()
        override fun lockActiveGroupOfVehicle(vehicleId: String): String? {
            lockCalls++
            if (tx.active) lockCallsInTx++
            return groupOf[vehicleId]
        }
    }

    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }

    private fun member(vid: String, dist: Int?) = MemberState(vid, vid, "#2E7DFF", true, null, null, null, 80f, 500, null, null, dist, MemberStatus.DRIVING)

    /** 선두 A(0) — B(1.0 km 뒤) — C(2.1 km 뒤), 모두 grp_1 */
    private val convoy = GroupState("grp_1", "veh_A", listOf(member("veh_A", 0), member("veh_B", 1_000), member("veh_C", 2_100)))

    private val dedup = InMemoryDedup()
    private val tx = RecordingTx()
    private val sink = RecordingSink(tx)
    private val presence = FakePresence(tx)
    private val clock = MutableClock(Instant.parse("2026-11-02T03:00:00Z"))
    private val convoyGroups = mapOf("veh_A" to "grp_1", "veh_B" to "grp_1", "veh_C" to "grp_1", "veh_D" to "grp_1")
    private val access = FakeAccess(tx, mapOf("veh_A" to "grp_1"))
    private val service = VehicleEventService(
        FakeMembership(mapOf("veh_A" to "grp_1")),
        FakeIdentity(mapOf("veh_A" to "아빠 차")),
        dedup, SpeechComposer(), sink, presence, access, tx, clock,
    )

    private fun convoyService(states: Map<String, GroupState> = mapOf("grp_1" to convoy)): VehicleEventService {
        presence.states = states
        return VehicleEventService(
            FakeMembership(convoyGroups),
            FakeIdentity(mapOf("veh_A" to "아빠 차", "veh_B" to "둘째 차", "veh_C" to "삼촌 차")),
            InMemoryDedup(), SpeechComposer(), sink, presence, FakeAccess(tx, convoyGroups), tx, clock,
        )
    }

    private fun hardBrake() = buildJsonObject { put("decelMps2", 5.6); put("speedBeforeKph", 72.0) }

    private val nowMs: Long get() = clock.millis()

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
        val svc = VehicleEventService(
            FakeMembership(mapOf("veh_B" to "grp_2")), FakeIdentity(emptyMap()), InMemoryDedup(), SpeechComposer(), sink, presence,
            FakeAccess(tx, mapOf("veh_B" to "grp_2")), tx, clock,
        )
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

    // ---------------------------------------------------------------- 늦은 위험 이벤트 (C017, 계약 4)

    @Test
    fun `a hard brake that arrives minutes late is recorded but announced to nobody, and does not hold the dedup window`() {
        // 터널에서 큐에 쌓였다가 3분 뒤 도착 — 서버 수신 − 봉투 ts = 180 s > 60 s
        val late = assertNotNull(service.handle("veh_A", VehicleEventType.HARD_BRAKE, hardBrake(), sentAtMs = nowMs - 180_000))
        assertNull(late.speech, "늦은 급제동은 음성 문구가 없다")
        assertEquals(emptyList(), late.audience, "audience [] = 어느 화면·음성에도 안 나온다(기록만)")
        assertEquals(Severity.WARN, late.severity)
        assertTrue("veh_A:HARD_BRAKE" !in dedup.seen, "늦은 이벤트는 중복 억제 창을 잡지 않는다")
        // 재연결 직후의 새 급제동은 평소대로 알린다
        val fresh = assertNotNull(service.handle("veh_A", VehicleEventType.HARD_BRAKE, hardBrake(), sentAtMs = nowMs - 800))
        assertEquals("앞쪽 아빠 차가 급제동했어요. 주의하세요.", fresh.speech)
        assertEquals(2, sink.recorded.size)
        assertTrue(sink.recorded.all { it.inTx }, "늦은 이벤트도 같은 저장 트랜잭션(그룹 확인)을 거친다")
    }

    @Test
    fun `a late airbag is recorded without speech, other event types are never judged late`() {
        val airbag = assertNotNull(service.handle("veh_A", VehicleEventType.AIRBAG_DEPLOYED, buildJsonObject { }, sentAtMs = nowMs - 90_000))
        assertNull(airbag.speech)
        assertEquals(Severity.ALERT, airbag.severity)
        assertEquals(emptyList(), airbag.audience)
        // 저잔량은 늦어도 그대로(순간 위험 경고가 아니다)
        val low = assertNotNull(service.handle("veh_A", VehicleEventType.LOW_ENERGY, buildJsonObject { put("kind", "FUEL"); put("pct", 12) }, sentAtMs = nowMs - 600_000))
        assertEquals("아빠 차 연료가 12% 남았어요.", low.speech)
        // 60 s 이내는 늦지 않다
        val onTime = assertNotNull(service.handle("veh_A", VehicleEventType.HARD_BRAKE, hardBrake(), sentAtMs = nowMs - 59_000))
        assertEquals("앞쪽 아빠 차가 급제동했어요. 주의하세요.", onTime.speech)
    }

    @Test
    fun `a car whose clock is 90 s slow is not misjudged late - the telemetry lag baseline is subtracted`() {
        presence.lags = mapOf("veh_A" to 90_000L)
        // 차 시계가 90 s 늦어 봉투 ts 가 95 s 전처럼 보이지만 실제 지연은 5 s
        val fresh = assertNotNull(service.handle("veh_A", VehicleEventType.HARD_BRAKE, hardBrake(), sentAtMs = nowMs - 95_000))
        assertEquals("앞쪽 아빠 차가 급제동했어요. 주의하세요.", fresh.speech)
        // 같은 차의 큐에 쌓였던 급제동(실제 3분 지연)은 여전히 늦다
        dedup.seen.clear()
        val late = assertNotNull(service.handle("veh_A", VehicleEventType.HARD_BRAKE, hardBrake(), sentAtMs = nowMs - 90_000 - 180_000))
        assertNull(late.speech)
        // 차 시계가 빠르면(기준값 음수) 빼지 않는다 — 늦음을 덜 보는 쪽(안전 쪽)으로만 보정
        presence.lags = mapOf("veh_A" to -30_000L)
        dedup.seen.clear()
        assertNull(service.handle("veh_A", VehicleEventType.HARD_BRAKE, hardBrake(), sentAtMs = nowMs - 70_000)!!.speech)
    }

    // ---------------------------------------------------------------- 차량 문자열·숫자 검증 (C105)

    @Test
    fun `LOW_ENERGY with a percentage outside 0-100 is dropped`() {
        assertNull(service.handle("veh_A", VehicleEventType.LOW_ENERGY, buildJsonObject { put("kind", "FUEL"); put("pct", 250) }))
        assertNull(service.handle("veh_A", VehicleEventType.LOW_ENERGY, buildJsonObject { put("kind", "EV"); put("pct", -1) }))
        assertTrue(sink.recorded.isEmpty())
        assertTrue("veh_A:LOW_ENERGY" !in dedup.seen, "버린 이벤트는 창을 잡지 않는다")
        assertEquals("아빠 차 배터리가 100% 남았어요.", service.handle("veh_A", VehicleEventType.LOW_ENERGY, buildJsonObject { put("kind", "EV"); put("pct", 100) })!!.speech)
    }

    @Test
    fun `waypoint names lose control and bidi characters and are cut to 40 chars in speech and payload`() {
        val raw = "덕평‮휴게소\n" + "가".repeat(300)
        val e = assertNotNull(service.handle("veh_A", VehicleEventType.WAYPOINT_ARRIVED, buildJsonObject { put("name", raw); put("lat", 37.2); put("lon", 127.4) }))
        val name = e.payload["name"]!!.jsonPrimitive.content
        assertEquals(40, name.length)
        assertTrue(name.startsWith("덕평 휴게소 가"), name)
        assertTrue(name.none { Character.getType(it) == Character.FORMAT.toInt() || it.isISOControl() }, name)
        assertEquals("아빠 차가 ${name}에 도착했어요.", e.speech)
        // 이름이 제어·서식 문자뿐이면 '경유지'
        dedup.seen.clear()
        assertEquals("아빠 차가 경유지에 도착했어요.", service.handle("veh_A", VehicleEventType.WAYPOINT_ARRIVED, buildJsonObject { put("name", "\u0007​"); put("lat", 37.2); put("lon", 127.4) })!!.speech)
    }

    // ---------------------------------------------------------------- 기록 트랜잭션·중복 억제 (C107)

    @Test
    fun `the record transaction re-checks the group in the DB - an ended group records nothing and frees the dedup window`() {
        access.groupOf = emptyMap() // 캐시는 grp_1 이라고 하지만 DB 에서는 종료가 먼저 커밋됐다
        assertNull(service.handle("veh_A", VehicleEventType.HARD_BRAKE, hardBrake()))
        assertTrue(sink.recorded.isEmpty())
        assertTrue("veh_A:HARD_BRAKE" !in dedup.seen, "기록하지 못했으면 창을 푼다")
        assertEquals(1, access.lockCallsInTx, "그룹 확인은 저장 트랜잭션 안에서")
    }

    @Test
    fun `a DB failure while recording releases the dedup window so the next event of that type is not swallowed`() {
        sink.fail = true
        assertThrows<IllegalStateException> { service.handle("veh_A", VehicleEventType.AIRBAG_DEPLOYED, buildJsonObject { }) }
        assertEquals(listOf("veh_A:AIRBAG_DEPLOYED"), dedup.released)
        sink.fail = false
        assertNotNull(service.handle("veh_A", VehicleEventType.AIRBAG_DEPLOYED, buildJsonObject { }), "120 s 억제되지 않는다")
    }

    @Test
    fun `a stale membership cache records into the DB group and drops the audience chosen from the wrong snapshot`() {
        presence.states = mapOf("grp_1" to convoy)
        val dbAccess = FakeAccess(tx, mapOf("veh_B" to "grp_9"))
        val stale = VehicleEventService(
            FakeMembership(convoyGroups), FakeIdentity(mapOf("veh_B" to "둘째 차")), InMemoryDedup(), SpeechComposer(), sink, presence, dbAccess, tx, clock,
        )
        val e = assertNotNull(stale.handle("veh_B", VehicleEventType.HARD_BRAKE, hardBrake()))
        assertEquals("grp_9", sink.recorded.single().groupId)
        assertNull(e.audience, "다른 그룹 스냅샷으로 고른 대상은 버리고 그룹 전체(차량이 다시 판정)")
    }
}
