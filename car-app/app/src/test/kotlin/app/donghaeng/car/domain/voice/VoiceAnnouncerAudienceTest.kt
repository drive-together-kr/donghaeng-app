package app.donghaeng.car.domain.voice

import app.donghaeng.car.adapter.voice.VoiceAdapter
import app.donghaeng.car.adapter.voice.VoiceAdapterStatus
import app.donghaeng.car.domain.model.Priority
import app.donghaeng.car.domain.session.ReceivedGroupEvent
import app.donghaeng.protocol.GroupEvent
import app.donghaeng.protocol.GroupState
import app.donghaeng.protocol.MemberState
import app.donghaeng.protocol.MemberStatus
import app.donghaeng.protocol.Severity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 급제동 수신 대상 — 음성 경로 (docs/03 §5.4 audience, docs/05 §3).
 * impl_02 상황: 선두 A(0) — B(2.1 km 뒤). A 패널에 B 가 "뒤 2.1 km" 인데 A 가 "앞쪽 B 급제동"을 들었다 → 앞차 A 는 듣지 않고 뒤차 B 만 듣는다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceAnnouncerAudienceTest {

    private class RecordingVoice : VoiceAdapter {
        val spoken = mutableListOf<String>()
        override val status: StateFlow<VoiceAdapterStatus> = MutableStateFlow(VoiceAdapterStatus(ready = true))
        override suspend fun speak(text: String, priority: Priority) { spoken += text }
        override fun stop() = Unit
    }

    private fun member(vid: String, dist: Int?) = MemberState(vid, vid, "#2E7DFF", true, null, null, null, 80f, 500, null, null, dist, MemberStatus.DRIVING)

    /** 선두 A(0) — B(2.1 km 뒤) */
    private val pair = GroupState("grp_1", "veh_A", listOf(member("veh_A", 0), member("veh_B", 2_100)))
    private var seq = 0

    private fun event(type: String, source: String, audience: List<String>?, now: Long) =
        ReceivedGroupEvent(GroupEvent("evt_${seq++}", type, source, Severity.WARN, "앞쪽 $source 급제동 #$seq", JsonObject(emptyMap()), audience), envelopeTsMs = now, receivedAtMs = now)

    /** [me] 차의 음성 안내에 [events] 를 흘리고 읽은 문장 수·대상 아님 생략 수를 돌려준다 */
    private fun TestScope.hear(me: String, state: GroupState?, vararg events: (Long) -> ReceivedGroupEvent): Pair<Int, Int> {
        val flow = MutableSharedFlow<ReceivedGroupEvent>(extraBufferCapacity = 16)
        val voice = RecordingVoice()
        val a = VoiceAnnouncer(flow, { me }, voice, groupState = { state }, dispatcher = StandardTestDispatcher(testScheduler), clock = { testScheduler.currentTime })
        a.start(); runCurrent()
        try {
            events.forEach { flow.tryEmit(it(testScheduler.currentTime)) }
            advanceTimeBy(60_000); runCurrent()
            return voice.spoken.size to a.state.value.skippedNotAudience
        } finally {
            a.stop()
        }
    }

    @Test
    fun `server audience - the car ahead stays silent, the car behind hears it`() = runTest {
        // B(뒤) 급제동: 서버가 B 보다 뒤인 차가 없어 audience = [] → 앞차 A 는 안 읽는다
        assertEquals("앞차 A: 뒤차 B 의 급제동은 안 읽음", 0 to 1, hear("veh_A", pair, { now -> event("HARD_BRAKE", "veh_B", emptyList(), now) }))
        // A(선두) 급제동: audience = [B] → 뒤차 B 는 읽는다
        assertEquals("뒤차 B: 앞차 A 의 급제동을 읽음", 1 to 0, hear("veh_B", pair, { now -> event("HARD_BRAKE", "veh_A", listOf("veh_B"), now) }))
        // audience 에 내가 없으면 스냅샷이 뭐라 하든 안 읽는다
        assertEquals(0 to 1, hear("veh_B", pair, { now -> event("HARD_BRAKE", "veh_A", listOf("veh_C"), now) }))
    }

    @Test
    fun `empty audience reaches nobody`() = runTest {
        assertEquals(0 to 1, hear("veh_A", null, { now -> event("HARD_BRAKE", "veh_B", emptyList(), now) }))
        assertEquals(0 to 1, hear("veh_C", null, { now -> event("HARD_BRAKE", "veh_B", emptyList(), now) }))
    }

    @Test
    fun `no audience (old server) - the latest snapshot decides, unknown means speak`() = runTest {
        assertEquals("앞차 A: 스냅샷상 B 가 뒤 → 안 읽음", 0 to 1, hear("veh_A", pair, { now -> event("HARD_BRAKE", "veh_B", null, now) }))
        assertEquals("뒤차 B: 스냅샷상 A 가 앞 → 읽음", 1 to 0, hear("veh_B", pair, { now -> event("HARD_BRAKE", "veh_A", null, now) }))
        assertEquals("스냅샷 없음 → 읽음(경고를 놓치지 않게)", 1 to 0, hear("veh_A", null, { now -> event("HARD_BRAKE", "veh_B", null, now) }))
        assertEquals("출처 위치 모름 → 읽음", 1 to 0, hear("veh_A", pair.copy(members = listOf(member("veh_A", 0), member("veh_B", null))), { now -> event("HARD_BRAKE", "veh_B", null, now) }))
    }

    @Test
    fun `other event types are not filtered by position`() = runTest {
        assertEquals(1 to 0, hear("veh_A", pair, { now -> event("LOW_ENERGY", "veh_B", null, now) }))
    }
}
