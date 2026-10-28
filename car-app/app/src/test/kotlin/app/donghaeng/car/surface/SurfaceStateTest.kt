package app.donghaeng.car.surface

import app.donghaeng.car.domain.session.GroupSession
import app.donghaeng.car.domain.session.ReceivedGroupEvent
import app.donghaeng.protocol.GroupEvent
import app.donghaeng.protocol.GroupState
import app.donghaeng.protocol.GroupTopics
import app.donghaeng.protocol.MemberState
import app.donghaeng.protocol.MemberStatus
import app.donghaeng.protocol.Place
import app.donghaeng.protocol.Severity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 표면 상태 홀더 — 세션·권한·전면 여부 → 모드·패널 표시, 이벤트 10초 창 갱신, 그룹 바뀌면 이벤트 비움, 모서리 저장 */
@OptIn(ExperimentalCoroutinesApi::class)
class SurfaceStateTest {

    private val session = MutableStateFlow<GroupSession?>(null)
    private val groupState = MutableStateFlow<GroupState?>(null)
    private val events = MutableSharedFlow<ReceivedGroupEvent>(extraBufferCapacity = 8)
    private val canDraw = MutableStateFlow(false)
    private val resumed = MutableStateFlow(true)
    private val driving = MutableStateFlow(false)
    private val positions = InMemoryPanelPositionStore(PanelCorner.BOTTOM_START)
    private val group = GroupSession("grp_1", "강릉 가족 여행", "veh_A", Place(37.7, 128.9, "경포해변"), GroupTopics("s", "e"), 0L)

    private fun surface(scheduler: TestCoroutineScheduler) = SurfaceState(
        session = session, groupState = groupState, events = events, myVehicleId = { "veh_ME" }, connected = MutableStateFlow(true),
        driving = driving, canDrawOverlays = canDraw, appResumed = resumed, positions = positions,
        dispatcher = StandardTestDispatcher(scheduler), clock = { scheduler.currentTime },
    )

    private fun member(vid: String, dist: Int) = MemberState(vid, vid, "#2E7DFF", true, 37.0, 127.0, 0f, 80f, 0, null, null, dist, MemberStatus.DRIVING)

    @Test
    fun `mode and panel visibility follow session, overlay permission and the app screen`() = runTest {
        val s = surface(testScheduler); s.start(); runCurrent()
        try {
            assertEquals(SurfaceMode.OFF, s.mode.value)
            assertNull(s.panelUi.value)
            assertEquals("저장된 모서리를 읽는다", PanelCorner.BOTTOM_START, s.corner.value)

            session.value = group; runCurrent()
            assertEquals(SurfaceMode.CARDS_ONLY, s.mode.value)
            assertFalse(s.panelVisible.value)
            assertNotNull(s.panelUi.value)

            canDraw.value = true; runCurrent()
            assertEquals(SurfaceMode.PANEL, s.mode.value)
            assertFalse("동행 화면이 앞", s.panelVisible.value)
            resumed.value = false; runCurrent()
            assertTrue(s.panelVisible.value)

            driving.value = true; runCurrent()
            assertTrue(s.isDriving.value)

            s.setCorner(PanelCorner.TOP_END)
            assertEquals(PanelCorner.TOP_END, positions.corner)

            session.value = null; runCurrent()
            assertEquals(SurfaceMode.OFF, s.mode.value)
            assertFalse(s.panelVisible.value)
            assertNull(s.panelUi.value)
        } finally { s.stop() }
    }

    @Test
    fun `panel ui is rebuilt from snapshots and the event line expires after 10 s, a new group clears events`() = runTest {
        session.value = group
        val s = surface(testScheduler); s.start(); runCurrent()
        try {
            groupState.value = GroupState("grp_1", "veh_A", listOf(member("veh_A", 0), member("veh_ME", 500))); runCurrent()
            assertEquals(1, s.panelUi.value!!.cars.size)
            assertEquals("앞 500", s.panelUi.value!!.cars.single().gapText)

            val t = testScheduler.currentTime
            events.emit(ReceivedGroupEvent(GroupEvent("e1", "HARD_BRAKE", "veh_A", Severity.WARN, "급제동", JsonObject(emptyMap())), t, t)); runCurrent()
            assertEquals(1, s.recentEvents.value.size)
            assertEquals("급제동", s.panelUi.value!!.event?.text)
            advanceTimeBy(11_000); runCurrent()
            assertNull("10초 지나면 이벤트 줄이 사라진다", s.panelUi.value!!.event)

            session.value = group.copy(groupId = "grp_2"); runCurrent()
            assertTrue(s.recentEvents.value.isEmpty())
        } finally { s.stop() }
    }
}
