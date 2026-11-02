package app.donghaeng.car.feature.drive

import app.donghaeng.car.core.common.DrivingStateMonitor
import app.donghaeng.car.core.common.DrivingStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 예시 화면 둘러보기(2-1): 단계 순환, 주행 중이면 닫는다(규칙 6 — 주행 중 입력 UI 비활성) */
@OptIn(ExperimentalCoroutinesApi::class)
class DemoTourTest {

    private class FakeDriving : DrivingStateMonitor {
        val driving = MutableStateFlow(false)
        override fun isDriving(): Flow<Boolean> = driving
        override val status: StateFlow<DrivingStatus> = MutableStateFlow(DrivingStatus(source = "test"))
    }

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `next wraps around to the first step`() {
        assertEquals(1, DemoTour.next(0))
        assertEquals(0, DemoTour.next(DemoTour.STEPS.lastIndex))
    }

    @Test
    fun `the tour follows the driving state so it closes once the car moves off`() {
        val monitor = FakeDriving()
        val vm = DemoTourViewModel(monitor)
        assertFalse(vm.isDriving.value)
        assertFalse(DemoTour.closesWhenDriving(vm.isDriving.value))
        monitor.driving.value = true
        assertTrue(vm.isDriving.value)
        assertTrue(DemoTour.closesWhenDriving(vm.isDriving.value))
    }
}
