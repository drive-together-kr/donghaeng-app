package app.donghaeng.car.surface

import app.donghaeng.car.adapter.voice.VoicePath
import app.donghaeng.car.domain.session.GroupSession
import app.donghaeng.car.domain.session.ReceivedGroupEvent
import app.donghaeng.car.domain.voice.VoiceCue
import app.donghaeng.car.feature.drive.DriveDashboardMapper
import app.donghaeng.car.feature.drive.EventHighlight
import app.donghaeng.car.feature.drive.PttUi
import app.donghaeng.car.feature.groupdrive.ConnState
import app.donghaeng.car.feature.groupdrive.GroupDriveUiState
import app.donghaeng.protocol.GroupEvent
import app.donghaeng.protocol.GroupState
import app.donghaeng.protocol.GroupTopics
import app.donghaeng.protocol.Place
import app.donghaeng.protocol.Severity
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneOffset

/**
 * 위험 이벤트 강조(docs/05 §5.2): 급제동·에어백의 음성 시작이 3초를 넘기면(또는 늦을 게 분명하면) 빨간 강조 + 깜빡임을 먼저,
 * 음성이 시작되면 깜빡임을 멈추고 3초 강조 유지. 패널 소식 줄·대시보드 띠가 같은 값.
 */
class EventHighlightPolicyTest {

    private val t0 = 1_700_000_000_000L
    private val android = VoiceCue(path = VoicePath.ANDROID_TTS, avgLatencyMs = 900)

    private fun decide(nowOffset: Long, cue: VoiceCue, type: String = "HARD_BRAKE") =
        EventHighlightPolicy.decide(type, "evt_1", receivedAtMs = t0, nowMs = t0 + nowOffset, cue = cue)

    @Test
    fun `only danger events are highlighted`() {
        listOf("LOW_ENERGY", "WAYPOINT_ARRIVED", "MEMBER_OFFLINE", "PASSENGER_MESSAGE").forEach {
            assertEquals(it, EventHighlight.NONE, decide(9_000, VoiceCue(path = VoicePath.DISPLAY_ONLY), it))
        }
        assertEquals(EventHighlight.BLINK, decide(0, VoiceCue(path = VoicePath.DISPLAY_ONLY), "AIRBAG_DEPLOYED"))
    }

    @Test
    fun `fast voice route - no highlight for 3 s, blink after 3 s without a start`() {
        assertEquals(EventHighlight.NONE, decide(0, android))
        assertEquals(EventHighlight.NONE, decide(2_999, android))
        assertEquals(EventHighlight.BLINK, decide(3_000, android))
        assertEquals(EventHighlight.BLINK, decide(9_000, android))
    }

    @Test
    fun `voice started within 3 s - never highlighted`() {
        val cue = android.copy(starts = mapOf("evt_1" to t0 + 1_200))
        assertEquals(EventHighlight.NONE, decide(1_000, cue))
        assertEquals(EventHighlight.NONE, decide(5_000, cue))
    }

    @Test
    fun `voice started after the highlight - blinking stops, highlight holds 3 s then ends`() {
        val cue = android.copy(starts = mapOf("evt_1" to t0 + 4_000))
        assertEquals(EventHighlight.BLINK, decide(3_500, android))
        assertEquals(EventHighlight.STEADY, decide(4_000, cue))
        assertEquals(EventHighlight.STEADY, decide(6_999, cue))
        assertEquals(EventHighlight.NONE, decide(7_000, cue))
    }

    @Test
    fun `display only or a slow route average - highlight from the first moment`() {
        assertEquals(EventHighlight.BLINK, decide(0, VoiceCue(path = VoicePath.DISPLAY_ONLY)))
        val slow = VoiceCue(path = VoicePath.PLEOS_TTS, avgLatencyMs = 6_100)
        assertEquals(EventHighlight.BLINK, decide(0, slow))
        // 늦은 경로라도 음성이 시작되면 깜빡임을 멈추고 3초 강조
        val started = slow.copy(starts = mapOf("evt_1" to t0 + 5_500))
        assertEquals(EventHighlight.STEADY, decide(6_000, started))
        assertEquals(EventHighlight.NONE, decide(8_500, started))
        // 평균이 딱 3초면 '분명히 늦음'이 아니다 — 3초 규칙
        assertEquals(EventHighlight.NONE, decide(1_000, VoiceCue(path = VoicePath.PLEOS_TTS, avgLatencyMs = 3_000)))
    }

    @Test
    fun `unknown route without samples falls back to the 3 s rule`() {
        assertEquals(EventHighlight.NONE, decide(1_000, VoiceCue()))
        assertEquals(EventHighlight.BLINK, decide(3_000, VoiceCue()))
    }

    // ---- 패널 소식 줄·대시보드 띠가 같은 값 ----

    private val session = GroupSession("grp_1", "강릉 가족 여행", "veh_A", Place(37.7719, 128.9479, "경포해변"), GroupTopics("s", "e"), 0L)

    private fun brake(ageMs: Long) = ReceivedGroupEvent(
        GroupEvent("evt_1", "HARD_BRAKE", "veh_A", Severity.WARN, "앞쪽 아빠 차가 급제동했어요. 주의하세요.", JsonObject(emptyMap())),
        envelopeTsMs = t0 - ageMs, receivedAtMs = t0 - ageMs,
    )

    private fun panel(events: List<ReceivedGroupEvent>, cue: VoiceCue?) =
        FloatingPanelMapper.map(session, GroupState("grp_1", "veh_A", emptyList()), "veh_ME", events, PttUi(), connected = true, nowMs = t0, zone = ZoneOffset.UTC, voiceCue = cue).event?.highlight

    private fun dashboard(events: List<ReceivedGroupEvent>, cue: VoiceCue) =
        DriveDashboardMapper.map(GroupDriveUiState(session = session, connection = ConnState.CONNECTED, nowMs = t0, recentEvents = events, myVehicleId = "veh_ME", voiceCue = cue), ZoneOffset.UTC)!!.event?.highlight

    @Test
    fun `panel news line and parked dashboard band carry the same highlight`() {
        val events = listOf(brake(ageMs = 4_000))
        assertEquals(EventHighlight.BLINK, panel(events, android))
        assertEquals(EventHighlight.BLINK, dashboard(events, android))
        val started = android.copy(starts = mapOf("evt_1" to t0 - 500))
        assertEquals(EventHighlight.STEADY, panel(events, started))
        assertEquals(EventHighlight.STEADY, dashboard(events, started))
        val fresh = listOf(brake(ageMs = 1_000))
        assertEquals(EventHighlight.NONE, panel(fresh, android))
        assertEquals(EventHighlight.NONE, dashboard(fresh, android))
        assertEquals("음성 값이 없으면 강조 없음(옛 호출)", EventHighlight.NONE, panel(events, null))
    }
}
