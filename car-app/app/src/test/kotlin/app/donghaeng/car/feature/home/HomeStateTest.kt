package app.donghaeng.car.feature.home

import app.donghaeng.car.core.network.mqtt.MqttState
import app.donghaeng.car.domain.session.GroupSession
import app.donghaeng.protocol.GroupTopics
import app.donghaeng.protocol.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 홈 위계(2-2·E117): 상태별 주 버튼 하나, 주행 중 잠금 / 서버 배너 원인 분류(E119) / 실시간 연결 칩 */
class HomeStateTest {

    private val group = GroupSession("grp_1", "강릉 가족 여행", "veh_A", Place(37.7, 128.9, "경포해변"), GroupTopics("s", "e"), 0L)

    @Test
    fun `one primary action per state - pair when not connected, start when connected, open when a group exists`() {
        assertEquals(HomeAction.PAIR_PHONE, HomeUiState(connection = VehicleConnection.DISCONNECTED).primaryAction)
        assertEquals(HomeAction.PAIR_PHONE, HomeUiState(connection = VehicleConnection.UNAVAILABLE).primaryAction)
        assertEquals(HomeAction.START_GROUP, HomeUiState(connection = VehicleConnection.CONNECTED, canStartGroup = true).primaryAction)
        assertEquals(HomeAction.OPEN_GROUP, HomeUiState(connection = VehicleConnection.CONNECTED, canStartGroup = true, groupSession = group).primaryAction)
        assertEquals("저장소 오류여도 그룹이 남아 있으면 그룹 보기", HomeAction.OPEN_GROUP, HomeUiState(connection = VehicleConnection.UNAVAILABLE, groupSession = group).primaryAction)
    }

    @Test
    fun `while driving only open group stays enabled, and pairing waits until the store is read`() {
        assertTrue(HomeUiState(connection = VehicleConnection.CONNECTED, groupSession = group, isDriving = true).primaryEnabled)
        assertFalse(HomeUiState(connection = VehicleConnection.CONNECTED, canStartGroup = true, isDriving = true).primaryEnabled)
        assertTrue(HomeUiState(connection = VehicleConnection.CONNECTED, canStartGroup = true).primaryEnabled)
        assertFalse(HomeUiState(connection = VehicleConnection.DISCONNECTED, isDriving = true).primaryEnabled)
        assertTrue(HomeUiState(connection = VehicleConnection.DISCONNECTED).primaryEnabled)
        assertFalse("확인 중", HomeUiState(connection = VehicleConnection.CHECKING).primaryEnabled)
    }

    @Test
    fun `server banner reason is classified into plain words, raw exception text stays in diagnostics`() {
        assertEquals(HomeBanner.ServerReason.AUTH, HomeBanner.serverReason("anything", authRejected = true))
        assertEquals(HomeBanner.ServerReason.TIMEOUT, HomeBanner.serverReason("Timeout while waiting for CONNACK", false))
        assertEquals(HomeBanner.ServerReason.REFUSED, HomeBanner.serverReason("refused: plaintext tcp to non-local host", false))
        assertEquals(HomeBanner.ServerReason.UNREACHABLE, HomeBanner.serverReason("java.net.UnknownHostException: 1.2.3.4.sslip.io", false))
        assertEquals(HomeBanner.ServerReason.UNREACHABLE, HomeBanner.serverReason("Connection refused", false))
        assertEquals(HomeBanner.ServerReason.OTHER, HomeBanner.serverReason("something odd", false))
        assertEquals(HomeBanner.ServerReason.OTHER, HomeBanner.serverReason(null, false))
        assertEquals(HomeBanner.ServerReason.TIMEOUT, HomeBanner.ServerDisconnected(90, "Timeout while waiting for CONNACK", false).reason)
    }

    @Test
    fun `realtime chip follows the mqtt state and sdk counts come from the probe`() {
        assertEquals(Realtime.CONNECTED, HomeViewModel.realtimeOf(MqttState.CONNECTED))
        assertEquals(Realtime.CONNECTING, HomeViewModel.realtimeOf(MqttState.RECONNECTING))
        assertEquals(Realtime.CONNECTING, HomeViewModel.realtimeOf(MqttState.CONNECTING))
        assertEquals(Realtime.OFF, HomeViewModel.realtimeOf(MqttState.DISCONNECTED))
        val probed = HomeUiState(sdkProbe = listOf(app.donghaeng.car.adapter.sdk.ProbeLine("a", true, ""), app.donghaeng.car.adapter.sdk.ProbeLine("b", false, "")))
        assertEquals(1, probed.sdkAvailable)
        assertEquals(2, probed.sdkTotal)
        assertEquals(null, HomeUiState().sdkAvailable)
    }
}
