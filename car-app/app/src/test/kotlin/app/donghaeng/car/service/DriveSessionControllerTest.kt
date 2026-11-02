package app.donghaeng.car.service

import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import app.donghaeng.car.domain.session.GroupSession
import app.donghaeng.protocol.GroupTopics
import app.donghaeng.protocol.Place
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A003: 백그라운드에서 FGS 시작이 막혔으면(ForegroundServiceStartNotAllowedException) 동행 화면이 보일 때(onResume·권한 결과) 다시 시작하고,
 * 그룹이 끝나면 시작 실패로 알고 있어도 늘 멈춤을 요청한다(sticky 로 다시 뜬 서비스).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DriveSessionControllerTest {

    /** 시작 요청을 기록하고, [failStarts] 면 백그라운드 FGS 금지처럼 던진다 */
    private class FakeContext : ContextWrapper(null) {
        var failStarts = true
        var starts = 0
        var stops = 0
        override fun startService(service: Intent?): ComponentName? = start()
        override fun startForegroundService(service: Intent?): ComponentName? = start()
        override fun stopService(name: Intent?): Boolean { stops++; return true }
        override fun checkSelfPermission(permission: String): Int = 0
        private fun start(): ComponentName? {
            starts++
            if (failStarts) throw IllegalStateException("ForegroundServiceStartNotAllowedException")
            return null
        }
    }

    private val session = GroupSession("grp_1", "추석 귀성길", "veh_A", Place(35.1, 126.8, "광주"), GroupTopics("s", "e"), 1L)

    @Test
    fun `a background start failure is retried when the app becomes visible, and the group end always stops`() = runTest {
        val context = FakeContext()
        val sessions = MutableStateFlow<GroupSession?>(null)
        var visible = false
        val controller = DriveSessionController(context, sessions, appVisible = { visible }, dispatcher = StandardTestDispatcher(testScheduler))
        controller.start(); runCurrent()
        val stopsAtStart = context.stops

        // 폰이 백그라운드의 차를 그룹에 넣음 → 시작 실패
        sessions.value = session; runCurrent()
        assertEquals(1, context.starts)
        assertFalse(controller.serviceRunning.value)
        assertEquals("IllegalStateException", controller.lastStartFailure.value)

        // 아직 백그라운드 → 미룬다
        assertEquals(DriveSessionController.TypeRefresh.DEFERRED_BACKGROUND, controller.ensureType(session))
        // 동행 화면이 앞에 옴(onResume) → 다시 시작(예전엔 NO_SESSION 으로 그룹 끝까지 FGS 없음)
        visible = true
        context.failStarts = false
        assertEquals(DriveSessionController.TypeRefresh.REQUESTED, controller.ensureType(session))
        assertTrue(controller.serviceRunning.value)
        assertNull(controller.lastStartFailure.value)

        // 그룹 종료 → 멈춤
        sessions.value = null; runCurrent()
        assertEquals(stopsAtStart + 1, context.stops)
        assertFalse(controller.serviceRunning.value)
    }

    @Test
    fun `the group end stops the service even when the controller thinks the start failed (sticky restart)`() = runTest {
        val context = FakeContext()
        val sessions = MutableStateFlow<GroupSession?>(session)
        val controller = DriveSessionController(context, sessions, appVisible = { false }, dispatcher = StandardTestDispatcher(testScheduler))
        controller.start(); runCurrent()
        assertFalse(controller.serviceRunning.value)
        val stopsBefore = context.stops
        sessions.value = null; runCurrent()
        assertEquals("시작 실패로 알고 있어도 멈춤 요청(멱등)", stopsBefore + 1, context.stops)
    }

    @Test
    fun `a permission result while visible restarts a missing service`() = runTest {
        val context = FakeContext()
        val sessions = MutableStateFlow<GroupSession?>(session)
        val controller = DriveSessionController(context, sessions, appVisible = { true }, dispatcher = StandardTestDispatcher(testScheduler))
        controller.start(); runCurrent()
        assertFalse(controller.serviceRunning.value)
        context.failStarts = false
        assertEquals(DriveSessionController.TypeRefresh.REQUESTED, controller.refreshType(session))
        assertTrue(controller.serviceRunning.value)
        assertEquals(DriveSessionController.TypeRefresh.NO_SESSION, controller.refreshType(null))
    }
}
