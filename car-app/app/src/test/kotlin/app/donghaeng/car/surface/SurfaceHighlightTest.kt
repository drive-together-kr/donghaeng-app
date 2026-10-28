package app.donghaeng.car.surface

import app.donghaeng.car.adapter.voice.VoicePath
import app.donghaeng.car.domain.session.GroupSession
import app.donghaeng.car.domain.session.ReceivedGroupEvent
import app.donghaeng.car.domain.voice.VoiceCue
import app.donghaeng.car.feature.drive.EventHighlight
import app.donghaeng.protocol.GroupEvent
import app.donghaeng.protocol.GroupState
import app.donghaeng.protocol.GroupTopics
import app.donghaeng.protocol.Place
import app.donghaeng.protocol.Severity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** 표면 상태 홀더의 위험 이벤트 강조 — 3초 뒤 깜빡임, 음성 시작 신호가 오면 틱을 기다리지 않고 바로 STEADY */
@OptIn(ExperimentalCoroutinesApi::class)
class SurfaceHighlightTest {

    private val session = MutableStateFlow<GroupSession?>(GroupSession("grp_1", "강릉 가족 여행", "veh_A", Place(37.7, 128.9, "경포해변"), GroupTopics("s", "e"), 0L))
    private val events = MutableSharedFlow<ReceivedGroupEvent>(extraBufferCapacity = 8)
    private val cue = MutableStateFlow(VoiceCue(path = VoicePath.ANDROID_TTS, avgLatencyMs = 800))

    @Test
    fun `brake without voice blinks after 3 s, a start signal turns it steady at once, the feed exposes the same cue`() = runTest {
        val s = SurfaceState(
            session = session, groupState = MutableStateFlow(GroupState("grp_1", "veh_A", emptyList())), events = events, myVehicleId = { "veh_ME" },
            connected = MutableStateFlow(true), driving = MutableStateFlow(true), canDrawOverlays = MutableStateFlow(true), appVisible = MutableStateFlow(false),
            positions = InMemoryPanelPositionStore(PanelCorner.TOP_END), dispatcher = StandardTestDispatcher(testScheduler),
            clock = { testScheduler.currentTime }, voiceCues = cue,
        )
        s.start(); runCurrent()
        try {
            val t0 = testScheduler.currentTime
            events.emit(ReceivedGroupEvent(GroupEvent("evt_b", "HARD_BRAKE", "veh_A", Severity.WARN, "앞쪽 아빠 차가 급제동했어요.", JsonObject(emptyMap())), t0, t0))
            runCurrent()
            assertEquals(EventHighlight.NONE, s.panelUi.value?.event?.highlight)
            advanceTimeBy(3_100); runCurrent()
            assertEquals(EventHighlight.BLINK, s.panelUi.value?.event?.highlight)
            // 틱(1초) 사이에 음성이 시작돼도 바로 다시 그린다
            advanceTimeBy(300)
            cue.value = cue.value.copy(starts = mapOf("evt_b" to testScheduler.currentTime)); runCurrent()
            assertEquals(EventHighlight.STEADY, s.panelUi.value?.event?.highlight)
            assertEquals(cue.value, s.voiceCue())
            advanceTimeBy(3_700); runCurrent()   // 시작 + 3초 유지가 지난 다음 틱
            assertEquals(EventHighlight.NONE, s.panelUi.value?.event?.highlight)
        } finally { s.stop() }
    }
}
