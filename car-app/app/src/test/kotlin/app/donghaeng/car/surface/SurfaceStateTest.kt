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

    private val connected = MutableStateFlow(true)
    private val groupStateAt = MutableStateFlow<Long?>(null)

    private fun surface(scheduler: TestCoroutineScheduler, serverOffsetMs: Long = 0L) = SurfaceState(
        session = session, groupState = groupState, events = events, myVehicleId = { "veh_ME" }, connected = connected,
        driving = driving, canDrawOverlays = canDraw, appVisible = resumed, positions = positions,
        dispatcher = StandardTestDispatcher(scheduler), clock = { scheduler.currentTime }, serverClock = { scheduler.currentTime + serverOffsetMs },
        groupStateAt = groupStateAt,
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

    @Test
    fun `an old event redelivered by the broker after a reconnect is kept in the list but never shown as just now`() = runTest {
        session.value = group
        // 서버 시계가 기기보다 1.5 s 앞(T31 실측 범위) — 신선도·나이는 서버 시계 기준
        val s = surface(testScheduler, serverOffsetMs = 1_500); s.start(); runCurrent()
        try {
            groupState.value = GroupState("grp_1", "veh_A", listOf(member("veh_A", 0), member("veh_ME", 500))); runCurrent()
            val serverNow = testScheduler.currentTime + 1_500
            val local = testScheduler.currentTime
            // 재접속 직후: 3분 전에 생긴 급제동이 지금 수신됨(receivedAt = 지금)
            events.emit(ReceivedGroupEvent(GroupEvent("old", "HARD_BRAKE", "veh_A", Severity.WARN, "옛 급제동", JsonObject(emptyMap())), serverNow - 180_000, local)); runCurrent()
            assertEquals(1, s.recentEvents.value.size)
            assertNull("60초보다 오래된 이벤트는 소식 줄에 안 뜬다", s.panelUi.value!!.event)
            assertEquals(serverNow, s.serverNow())

            events.emit(ReceivedGroupEvent(GroupEvent("new", "LOW_ENERGY", "veh_A", Severity.WARN, "저잔량", JsonObject(emptyMap())), serverNow - 4_000, local)); runCurrent()
            assertEquals("저잔량", s.panelUi.value!!.event?.text)
            assertEquals("나이 = 서버 시계 − 봉투 ts", 4, s.panelUi.value!!.event?.ageS)
        } finally { s.stop() }
    }

    @Test
    fun `the stale age counts from when the group state was received, not from when the panel first saw it`() = runTest {
        session.value = group
        val s = surface(testScheduler); s.start(); runCurrent()
        try {
            connected.value = false
            // 2분 전에 받은 스냅샷(대시보드 stateAt 과 같은 값) — 패널이 지금 처음 봐도 나이는 2분
            groupStateAt.value = testScheduler.currentTime - 120_000
            groupState.value = GroupState("grp_1", "veh_A", listOf(member("veh_A", 0), member("veh_ME", 500))); runCurrent()
            assertEquals("2분 전", s.panelUi.value!!.staleText)

            // 끊긴 동안 세션 객체가 다시 방출돼도(이름 변경 등) 나이가 0 으로 돌아가지 않는다
            session.value = group.copy(name = "강릉 가족 여행 2"); runCurrent()
            assertEquals("2분 전", s.panelUi.value!!.staleText)

            // 새 스냅샷을 받으면(연결 복구) 표시가 사라진다
            connected.value = true
            groupStateAt.value = testScheduler.currentTime; runCurrent()
            assertNull(s.panelUi.value!!.staleText)
        } finally { s.stop() }
    }

    @Test
    fun `state age falls back to first seen only without a receipt time`() {
        val g = GroupState("grp_1", "veh_A", emptyList())
        assertNull(SurfaceState.stateAgeMs(null, 1_000, 0, 5_000))
        assertEquals(4_000L, SurfaceState.stateAgeMs(g, 1_000, 3_000, 5_000))
        assertEquals(2_000L, SurfaceState.stateAgeMs(g, null, 3_000, 5_000))
        assertEquals("시계가 거꾸로면 0", 0L, SurfaceState.stateAgeMs(g, 9_000, 3_000, 5_000))
    }
}
