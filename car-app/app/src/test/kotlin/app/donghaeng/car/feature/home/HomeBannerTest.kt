package app.donghaeng.car.feature.home

import app.donghaeng.car.core.common.ClearRecord
import app.donghaeng.car.core.common.StoredCredentials
import app.donghaeng.car.core.common.TokenClearReason
import app.donghaeng.car.core.common.TokenStoreState
import app.donghaeng.car.core.network.mqtt.MqttState
import app.donghaeng.car.core.network.mqtt.MqttStatus
import app.donghaeng.car.domain.session.StoreRetryState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T33 홈 상태 배너 결정(순수 함수) */
class HomeBannerTest {

    private val creds = StoredCredentials("jwt", "r", "veh_B", "엄마 차", "usr_B", "tcp://10.0.2.2:1883", "veh_B", "veh_B")
    private val now = 1_700_000_100_000L

    @Test
    fun `저장소 오류는 재시도 진행과 함께 배너로`() {
        val b = HomeViewModel.banner(TokenStoreState.Unavailable("SecurityException"), MqttStatus(), null, StoreRetryState(attempts = 3), now)
        assertEquals(HomeBanner.StorageUnavailable("SecurityException", 3, 10, exhausted = false), b)
    }

    @Test
    fun `서버가 끊은 연결(TOKEN_REVOKED·refresh 거부)만 다시 연결 배너, 사용자가 끊은 건 배너 없음`() {
        fun banner(reason: TokenClearReason) = HomeViewModel.banner(TokenStoreState.Empty, MqttStatus(), ClearRecord(reason.name, now, null), StoreRetryState(), now)
        assertEquals(HomeBanner.RepairNeeded(TokenClearReason.REFRESH_REJECTED), banner(TokenClearReason.REFRESH_REJECTED))
        assertEquals(HomeBanner.RepairNeeded(TokenClearReason.TOKEN_REVOKED_CMD), banner(TokenClearReason.TOKEN_REVOKED_CMD))
        assertNull(banner(TokenClearReason.USER_SETTINGS_DISCONNECT))
        assertNull(banner(TokenClearReason.USER_REPAIR))
        assertNull(HomeViewModel.banner(TokenStoreState.Empty, MqttStatus(), null, StoreRetryState(), now))
    }

    @Test
    fun `MQTT 가 30초 이상 못 붙으면 서버 연결 끊김 배너(인증 거부 표시 포함), 30초 전엔 없음`() {
        val present = TokenStoreState.Present(creds)
        val reconnecting = MqttStatus(state = MqttState.RECONNECTING, lastError = "auth rejected: NOT_AUTHORIZED", notConnectedSinceMs = now - 45_000)
        val b = HomeViewModel.banner(present, reconnecting, null, StoreRetryState(), now) as HomeBanner.ServerDisconnected
        assertEquals(45L, b.seconds)
        assertTrue(b.authRejected)
        assertNull(HomeViewModel.banner(present, reconnecting.copy(notConnectedSinceMs = now - 10_000), null, StoreRetryState(), now))
        assertNull(HomeViewModel.banner(present, MqttStatus(state = MqttState.CONNECTED, connectedAtMs = now), null, StoreRetryState(), now))
        val plain = HomeViewModel.banner(present, reconnecting.copy(lastError = "Connection refused"), null, StoreRetryState(), now) as HomeBanner.ServerDisconnected
        assertEquals("Connection refused", plain.lastError)
        assertTrue(!plain.authRejected)
    }
}
