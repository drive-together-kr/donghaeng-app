package app.donghaeng.protocol

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `GroupEvent.audience` 직렬화 호환 + 급제동 수신 대상 규칙 [EventAudience] (docs/03 §5.4).
 * 거리 = `distanceToLeaderM`(선두 0, 클수록 뒤) — 패널 간격과 같은 기준.
 */
class EventAudienceTest {

    private fun member(vid: String, dist: Int?) = MemberState(vid, vid, "#2E7DFF", true, null, null, null, null, null, null, null, dist, MemberStatus.DRIVING)

    /** 선두 A(0) — B(1.0 km 뒤) — C(2.1 km 뒤) */
    private val convoy = listOf(member("veh_A", 0), member("veh_B", 1_000), member("veh_C", 2_100))

    private fun brake(source: String?, audience: List<String>? = null) =
        GroupEvent("evt_1", GroupEventType.HARD_BRAKE.name, source, Severity.WARN, "앞쪽 차가 급제동했어요.", JsonObject(emptyMap()), audience)

    // ---------- 직렬화 호환 ----------

    @Test
    fun `audience null is omitted on encode and old messages decode to null`() {
        val old = GroupEvent("evt_1", "HARD_BRAKE", "veh_A", Severity.WARN, null, JsonObject(emptyMap()))
        assertEquals("""{"eventId":"evt_1","type":"HARD_BRAKE","sourceVehicleId":"veh_A","severity":"WARN","payload":{}}""", DhJson.json.encodeToString(old))
        assertNull(DhJson.json.decodeFromString<GroupEvent>("""{"eventId":"e","type":"HARD_BRAKE","sourceVehicleId":"veh_A","severity":"WARN","payload":{}}""").audience)
    }

    @Test
    fun `an empty audience is kept as an empty list - different from null`() {
        val none = brake("veh_C", audience = emptyList())
        val raw = DhJson.json.encodeToString(none)
        assertTrue(raw.endsWith(""","audience":[]}"""), raw)
        assertEquals(emptyList(), DhJson.json.decodeFromString<GroupEvent>(raw).audience)
        val some = DhJson.json.decodeFromString<GroupEvent>(DhJson.json.encodeToString(brake("veh_A", audience = listOf("veh_B", "veh_C"))))
        assertEquals(listOf("veh_B", "veh_C"), some.audience)
    }

    // ---------- 서버: 대상 고르기 ----------

    @Test
    fun `leader brakes - everyone else is behind`() {
        assertEquals(listOf("veh_B", "veh_C"), EventAudience.behind("veh_A", convoy))
    }

    @Test
    fun `last car brakes - nobody is behind`() {
        assertEquals(emptyList(), EventAudience.behind("veh_C", convoy))
    }

    @Test
    fun `middle car brakes - only the cars behind it`() {
        assertEquals(listOf("veh_C"), EventAudience.behind("veh_B", convoy))
    }

    @Test
    fun `unknown positions are included rather than missing a warning`() {
        val withUnknown = convoy + member("veh_D", null)
        assertEquals(listOf("veh_C", "veh_D"), EventAudience.behind("veh_B", withUnknown), "위치 모르는 D 는 포함")
        val sourceUnknown = listOf(member("veh_A", 0), member("veh_B", null), member("veh_C", 2_100))
        assertEquals(listOf("veh_A", "veh_C"), EventAudience.behind("veh_B", sourceUnknown), "출처 위치를 모르면 나머지 전원")
        assertEquals(listOf("veh_A", "veh_B", "veh_C"), EventAudience.behind("veh_X", convoy), "출처가 명단에 없으면 전원")
    }

    @Test
    fun `a tie is not behind`() {
        assertEquals(listOf("veh_C"), EventAudience.behind("veh_B", convoy + member("veh_E", 1_000)))
    }

    // ---------- 차량: 수신 판정 ----------

    @Test
    fun `audience from the server decides - front car is not addressed, rear car is`() {
        val e = brake("veh_B", audience = listOf("veh_C"))
        assertEquals(EventAudience.Verdict(false, EventAudience.Basis.AUDIENCE), EventAudience.decide(e, "veh_A", convoy), "앞차 A")
        assertEquals(EventAudience.Verdict(true, EventAudience.Basis.AUDIENCE), EventAudience.decide(e, "veh_C", convoy), "뒤차 C")
        assertFalse(EventAudience.isForMe(e, "veh_A", null), "audience 가 있으면 스냅샷 없이도 정해진다")
    }

    @Test
    fun `empty audience reaches nobody`() {
        val e = brake("veh_C", audience = emptyList())
        listOf("veh_A", "veh_B", "veh_C").forEach { assertFalse(EventAudience.isForMe(e, it, convoy), it) }
    }

    @Test
    fun `without audience a hard brake falls back to my snapshot`() {
        // impl_02 상황: A 패널에 B 가 "뒤 2.1 km" — B 의 급제동은 A 에게 '앞쪽'이 아니다
        val pair = listOf(member("veh_A", 0), member("veh_B", 2_100))
        assertEquals(EventAudience.Verdict(false, EventAudience.Basis.SNAPSHOT), EventAudience.decide(brake("veh_B"), "veh_A", pair), "앞차 A 는 대상 아님")
        assertEquals(EventAudience.Verdict(true, EventAudience.Basis.SNAPSHOT), EventAudience.decide(brake("veh_A"), "veh_B", pair), "뒤차 B 는 대상")
        assertFalse(EventAudience.isForMe(brake("veh_A"), "veh_A", pair), "내 급제동은 내 대상 아님")
    }

    @Test
    fun `without audience unknown facts mean addressed`() {
        assertEquals(EventAudience.Basis.UNKNOWN, EventAudience.decide(brake("veh_B"), "veh_A", null).basis)
        assertTrue(EventAudience.isForMe(brake("veh_B"), "veh_A", null), "스냅샷 없음")
        assertTrue(EventAudience.isForMe(brake("veh_DEMO"), "veh_A", convoy), "출처가 명단에 없음(디버그 주입)")
        assertTrue(EventAudience.isForMe(brake("veh_B"), "veh_A", listOf(member("veh_A", null), member("veh_B", 2_100))), "내 위치 모름")
        assertTrue(EventAudience.isForMe(brake(null), "veh_A", convoy), "출처 없음")
        assertTrue(EventAudience.isForMe(brake("veh_B"), null, convoy), "내 id 모름")
        assertTrue(EventAudience.isForMe(brake("veh_B", audience = listOf("veh_C")), null, convoy), "audience 가 있어도 내 id 를 모르면 대상")
    }

    @Test
    fun `other event types without audience go to the whole group`() {
        val low = GroupEvent("evt_2", GroupEventType.LOW_ENERGY.name, "veh_C", Severity.WARN, "연료", JsonObject(emptyMap()))
        assertEquals(EventAudience.Verdict(true, EventAudience.Basis.GROUP), EventAudience.decide(low, "veh_A", convoy))
        val msg = GroupEvent("evt_3", GroupEventType.PASSENGER_MESSAGE.name, "veh_C", Severity.INFO, "메시지", JsonObject(emptyMap()))
        assertTrue(EventAudience.isForMe(msg, "veh_A", convoy))
        assertEquals(setOf("HARD_BRAKE"), EventAudience.BEHIND_ONLY_TYPES)
    }
}
