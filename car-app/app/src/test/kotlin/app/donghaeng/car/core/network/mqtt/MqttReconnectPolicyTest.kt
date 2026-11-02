package app.donghaeng.car.core.network.mqtt

import app.donghaeng.car.core.network.InMemoryServerOverrideStorage
import app.donghaeng.car.core.network.ServerConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A-RT1(2026-11-02 실측: EMQX 재시작 5 s → 차 재연결 138~273 s) — 재시작 창의 대기 규칙. A018 구독 재시도 간격. A068 봉투 ts */
class MqttReconnectPolicyTest {

    /** 기본 백오프(지터 없이) — 1, 2, 4, 8, 16, 30 s */
    private val backoff: (Int) -> Long = { a -> (1_000L shl a.coerceIn(0, 5)).coerceAtMost(30_000L) }
    private val now = 1_000_000L
    private val freshTokenExp = now + 20 * 3_600_000L

    @Test
    fun `inside the restart window a valid token's auth rejection is retried quickly without a token refresh`() {
        val d0 = MqttReconnectPolicy.decide(now, lostAtMs = now - 5_000, attempts = 3, authRejected = true, rejectedLatest = true, tokenExpiresAtMs = freshTokenExp, authRejectsInWindow = 0, quickSwitch = false, backoff = backoff)
        assertEquals(3_000L, d0.waitMs)
        assertFalse("토큰 회전하지 않음(JWKS 미적재는 토큰 탓이 아니다)", d0.refreshToken)
        val d2 = MqttReconnectPolicy.decide(now, now - 30_000, 6, true, true, freshTokenExp, authRejectsInWindow = 5, quickSwitch = false, backoff = backoff)
        assertEquals("상한 10 s", 10_000L, d2.waitMs)
    }

    @Test
    fun `outside the window, with an expiring token, or never connected, auth rejection waits for a refresh as before`() {
        val outside = MqttReconnectPolicy.decide(now, now - 200_000, 2, true, true, freshTokenExp, 0, false, backoff)
        assertTrue(outside.refreshToken)
        assertEquals(MqttReconnectPolicy.AUTH_WAIT_MS, outside.waitMs)
        val expiring = MqttReconnectPolicy.decide(now, now - 5_000, 2, true, true, now + 60_000, 0, false, backoff)
        assertTrue("만료가 가까우면 진짜 토큰 문제일 수 있다", expiring.refreshToken)
        val firstConnect = MqttReconnectPolicy.decide(now, lostAtMs = null, attempts = 0, authRejected = true, rejectedLatest = true, tokenExpiresAtMs = freshTokenExp, authRejectsInWindow = 0, quickSwitch = false, backoff = backoff)
        assertTrue(firstConnect.refreshToken)
        // 옛 토큰이 거부된 거면 갱신 없이 백오프 뒤 새 토큰으로
        val old = MqttReconnectPolicy.decide(now, null, 1, true, rejectedLatest = false, tokenExpiresAtMs = freshTokenExp, authRejectsInWindow = 0, quickSwitch = false, backoff = backoff)
        assertFalse(old.refreshToken)
        assertEquals(2_000L, old.waitMs)
    }

    @Test
    fun `inside the window plain failures back off at most 5 s, and early failures do not count for the transport fallback`() {
        assertEquals(5_000L, MqttReconnectPolicy.decide(now, now - 20_000, attempts = 5, authRejected = false, rejectedLatest = false, tokenExpiresAtMs = null, authRejectsInWindow = 0, quickSwitch = false, backoff = backoff).waitMs)
        assertEquals(30_000L, MqttReconnectPolicy.decide(now, null, 5, false, false, null, 0, false, backoff).waitMs)
        assertFalse(MqttReconnectPolicy.countsForTransport(now, now - 10_000))
        assertTrue(MqttReconnectPolicy.countsForTransport(now, now - 61_000))
        assertTrue(MqttReconnectPolicy.countsForTransport(now, null))
    }

    /**
     * 실측 장면 재현: 0 s 에 끊김, 브로커는 5 s 에 다시 뜨지만 JWKS 를 40 s 에야 받는다(그 전 인증은 NOT_AUTHORIZED).
     * 새 규칙으로 다시 붙는 시각이 60 s 안이어야 한다(목표 — 실측 138~273 s).
     */
    @Test
    fun `broker restart scenario reconnects within 60 s`() {
        val lost = 0L
        val brokerUpAt = 5_000L
        val jwksAt = 40_000L
        var t = 0L
        var attempts = 0
        var authRejects = 0
        var connectedAt: Long? = null
        while (t < 300_000L) {
            val authRejected = t >= brokerUpAt && t < jwksAt
            if (t >= jwksAt) { connectedAt = t; break }
            val d = MqttReconnectPolicy.decide(t, lost, attempts, authRejected, rejectedLatest = authRejected, tokenExpiresAtMs = 20 * 3_600_000L, authRejectsInWindow = authRejects, quickSwitch = false, backoff = backoff)
            if (authRejected && !d.refreshToken) authRejects++
            attempts++
            t += d.waitMs
        }
        assertTrue("재연결 ${connectedAt}ms", connectedAt != null && connectedAt <= 60_000L)
    }

    @Test
    fun `A018 - subscription retries back off 2 4 8 16 30 s and notify after the third denial`() {
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), (0..5).map { SubscribeRetryPolicy.delayMs(it) })
        assertEquals(3, SubscribeRetryPolicy.NOTIFY_AFTER)
    }

    @Test
    fun `A070 - topics unsubscribed while offline are remembered for the next connection unless subscribed again`() = kotlinx.coroutines.test.runTest {
        val gateway = HiveMqttGateway(ServerConfig("http://10.0.2.2:8080", InMemoryServerOverrideStorage()), InMemoryMqttTransportMemory())
        gateway.subscribe(listOf("dh/v1/group/grp_1/state", "dh/v1/group/grp_1/event"))
        gateway.unsubscribe(listOf("dh/v1/group/grp_1/state", "dh/v1/group/grp_1/event"))
        assertEquals(setOf("dh/v1/group/grp_1/state", "dh/v1/group/grp_1/event"), gateway.pendingUnsubscribes())
        gateway.subscribe(listOf("dh/v1/group/grp_1/event"))
        assertEquals("다시 구독한 토픽은 풀지 않는다", setOf("dh/v1/group/grp_1/state"), gateway.pendingUnsubscribes())
    }

    @Test
    fun `A068 - the published envelope ts comes from the envelope clock (server time when synced)`() {
        val gateway = HiveMqttGateway(ServerConfig("http://10.0.2.2:8080", InMemoryServerOverrideStorage()), InMemoryMqttTransportMemory())
        assertTrue("기본은 기기 시계", gateway.envelopeFor("t", "TELEMETRY", "veh_B", 1).ts > 0)
        gateway.envelopeClock = { 1_793_000_000_123L }
        val e1 = gateway.envelopeFor("dh/v1/vehicle/veh_B/event", "HARD_BRAKE", "veh_B", "x")
        val e2 = gateway.envelopeFor("dh/v1/vehicle/veh_B/event", "HARD_BRAKE", "veh_B", "y")
        assertEquals(1_793_000_000_123L, e1.ts)
        assertEquals("seq 는 토픽별로 오른다", e1.seq + 1, e2.seq)
    }
}
