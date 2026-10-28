package app.donghaeng.car.debug

import app.donghaeng.protocol.EventAudience
import app.donghaeng.protocol.GroupEvent
import app.donghaeng.protocol.MemberState
import app.donghaeng.protocol.MemberStatus
import app.donghaeng.protocol.Severity
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 디버그 `event` 주입의 출처 기본값 (리뷰 지적: 기본 출처 veh_DEMO 는 명단 밖이라 판정이 UNKNOWN → 선두 A 에도 '앞쪽 시연 차가 급제동' 이 떠
 * 고치기 전 impl_02 가 그대로 재현됐다). 출처를 고른 뒤 실제 수신과 같은 [EventAudience.decide] 를 거쳐 결과까지 본다.
 */
class DemoEventSourceTest {

    private fun member(vid: String, dist: Int?) = MemberState(vid, vid, "#2E7DFF", true, 37.0, 127.0, 0f, 80f, 0, null, null, dist, MemberStatus.DRIVING)

    /** impl_02 상황: 선두 A(0) — B(2.1 km 뒤) */
    private val pair = listOf(member("veh_A", 0), member("veh_B", 2_100))

    private fun verdict(type: String, pick: DemoEventSource.Pick, me: String, audience: List<String>? = null): EventAudience.Verdict {
        val source = (pick as DemoEventSource.Pick.Source).vehicleId
        val e = GroupEvent("demo-1", type, source, Severity.WARN, "앞쪽 시연 차가 급제동했어요. 주의하세요.", JsonObject(emptyMap()), audience)
        return EventAudience.decide(e, me, pair)
    }

    @Test
    fun `hard brake on the leader with no source is not injected - nobody is ahead of the leader`() {
        val pick = DemoEventSource.pick("HARD_BRAKE", null, audienceGiven = false, me = "veh_A", members = pair)
        assertEquals(DemoEventSource.Pick.NoNeighbour("ahead", defaulted = true), pick)
    }

    @Test
    fun `hard brake on the car behind defaults to the car ahead and is for me by snapshot`() {
        val pick = DemoEventSource.pick("HARD_BRAKE", null, audienceGiven = false, me = "veh_B", members = pair)
        assertEquals(DemoEventSource.Pick.Source("veh_A", "ahead"), pick)
        assertEquals(EventAudience.Verdict(true, EventAudience.Basis.SNAPSHOT), verdict("HARD_BRAKE", pick, "veh_B"))
    }

    @Test
    fun `explicit source behind on the leader is not for me`() {
        val pick = DemoEventSource.pick("HARD_BRAKE", "behind", audienceGiven = false, me = "veh_A", members = pair)
        assertEquals(DemoEventSource.Pick.Source("veh_B", "behind"), pick)
        assertFalse(verdict("HARD_BRAKE", pick, "veh_A").forMe)
    }

    @Test
    fun `no snapshot means no neighbour - the default hard brake is not injected`() {
        assertEquals(DemoEventSource.Pick.NoNeighbour("ahead", defaulted = true), DemoEventSource.pick("HARD_BRAKE", null, false, "veh_B", null))
        assertEquals(DemoEventSource.Pick.NoNeighbour("ahead", defaulted = true), DemoEventSource.pick("HARD_BRAKE", null, false, "veh_B", listOf(member("veh_A", 0), member("veh_B", null))))
    }

    @Test
    fun `explicit out-of-roster source and explicit audience keep working as overrides`() {
        val outside = DemoEventSource.pick("HARD_BRAKE", "veh_DEMO", audienceGiven = false, me = "veh_A", members = pair)
        assertEquals(DemoEventSource.Pick.Source("veh_DEMO", "veh_DEMO"), outside)
        assertEquals(EventAudience.Basis.UNKNOWN, verdict("HARD_BRAKE", outside, "veh_A").basis)

        // --es audience me: 서버가 정한 audience 처럼 판정 — 선두에 카드 자리만 볼 때
        val forced = DemoEventSource.pick("HARD_BRAKE", null, audienceGiven = true, me = "veh_A", members = pair)
        assertEquals(DemoEventSource.Pick.Source(DemoEventSource.DEFAULT_SOURCE, DemoEventSource.DEFAULT_SOURCE), forced)
        assertEquals(EventAudience.Verdict(true, EventAudience.Basis.AUDIENCE), verdict("HARD_BRAKE", forced, "veh_A", audience = listOf("veh_A")))
    }

    @Test
    fun `other event types keep the out-of-roster default and go to the whole group`() {
        val pick = DemoEventSource.pick("LOW_ENERGY", null, audienceGiven = false, me = "veh_A", members = pair)
        assertEquals(DemoEventSource.Pick.Source(DemoEventSource.DEFAULT_SOURCE, DemoEventSource.DEFAULT_SOURCE), pick)
        assertTrue(verdict("LOW_ENERGY", pick, "veh_A").forMe)
    }
}
