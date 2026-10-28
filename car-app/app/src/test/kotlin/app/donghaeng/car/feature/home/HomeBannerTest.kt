package app.donghaeng.car.feature.home

import app.donghaeng.car.core.common.ClearRecord
import app.donghaeng.car.core.common.StoredCredentials
import app.donghaeng.car.core.common.TokenClearReason
import app.donghaeng.car.core.common.TokenStoreState
import app.donghaeng.car.core.network.InMemoryServerOverrideStorage
import app.donghaeng.car.core.network.ServerConfig
import app.donghaeng.car.core.network.mqtt.HiveMqttGateway
import app.donghaeng.car.core.network.mqtt.InMemoryMqttTransportMemory
import app.donghaeng.car.core.network.mqtt.MqttState
import app.donghaeng.car.core.network.mqtt.MqttStatus
import app.donghaeng.car.domain.pairing.TokenRefreshState
import app.donghaeng.car.domain.session.StoreRetryState
import kotlinx.coroutines.test.runTest
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

    @Test
    fun `선제 갱신이 거부되면(아직 동작) 곧 다시 연결 배너 — 서버 연결 끊김보다 먼저 (T33 리뷰)`() {
        val present = TokenStoreState.Present(creds)
        val rejected = TokenRefreshState(accessExpiresAtMs = now + 600_000, repairNeededSinceMs = now - 1_000)
        val connected = MqttStatus(state = MqttState.CONNECTED, connectedAtMs = now)
        assertEquals(HomeBanner.RepairSoon(now + 600_000), HomeViewModel.banner(present, connected, null, StoreRetryState(), now, rejected))
        val reconnecting = MqttStatus(state = MqttState.RECONNECTING, notConnectedSinceMs = now - 45_000)
        assertEquals(HomeBanner.RepairSoon(now + 600_000), HomeViewModel.banner(present, reconnecting, null, StoreRetryState(), now, rejected))
        assertNull("거부가 없으면 배너 없음", HomeViewModel.banner(present, connected, null, StoreRetryState(), now, TokenRefreshState(accessExpiresAtMs = now + 600_000)))
    }

    @Test
    fun `브로커 주소 정책 거부도 30초 뒤 서버 연결 끊김 배너로 보인다 (T33 리뷰)`() = runTest {
        // 서버가 https 인데 평문 tcp:// 브로커 → MqttUriPolicy 가 거부(클라이언트를 만들지 않는다). T32 부터 ws:// 는 지원 스킴이라 https + tcp 로 바꿨다
        val https = ServerConfig("https://1-2-3-4.sslip.io", "ssl://1-2-3-4.sslip.io:8883", InMemoryServerOverrideStorage())
        val gateway = HiveMqttGateway(https, InMemoryMqttTransportMemory())
        gateway.connect(creds.copy(mqttUri = "tcp://1-2-3-4.sslip.io:1883"))
        val first = gateway.status.value
        assertEquals(MqttState.DISCONNECTED, first.state)
        assertTrue("refused 사유: ${first.lastError}", first.lastError?.startsWith("refused:") == true)
        val since = assertNotNullValue(first.notConnectedSinceMs)
        // 토큰 갱신마다 connect 가 다시 와도 처음 시각을 유지한다
        gateway.connect(creds.copy(mqttUri = "tcp://1-2-3-4.sslip.io:1883", vehicleAccessToken = "jwt-2"))
        assertEquals(since, gateway.status.value.notConnectedSinceMs)

        val banner = HomeViewModel.banner(TokenStoreState.Present(creds), gateway.status.value, null, StoreRetryState(), since + 31_000)
        assertTrue("배너: $banner", banner is HomeBanner.ServerDisconnected && banner.lastError?.startsWith("refused:") == true && !banner.authRejected)
    }

    private fun <T : Any> assertNotNullValue(value: T?): T {
        assertTrue("값이 있어야 한다", value != null)
        return value!!
    }
}
