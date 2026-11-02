package app.donghaeng.car.core.network.mqtt

import app.donghaeng.car.core.network.InMemoryServerOverrideStorage
import app.donghaeng.car.core.network.ServerConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        assertFalse(MqttReconnectPolicy.countsForTransport(now, now - 10_000, brokerNotReady = true))
        assertTrue(MqttReconnectPolicy.countsForTransport(now, now - 61_000, brokerNotReady = true))
        assertTrue(MqttReconnectPolicy.countsForTransport(now, null, brokerNotReady = true))
        // RA-8: 창 안이어도 시간 초과·TLS 실패처럼 '전송이 막혔을 수 있는' 실패는 센다
        assertTrue(MqttReconnectPolicy.countsForTransport(now, now - 10_000, brokerNotReady = false))
    }

    // ---------------------------------------------------------------- RA-7: 끊김 판단은 ReconnectTracker 한곳(게이트웨이는 적용만)

    private val ssl = BrokerEndpoint("1-2-3-4.sslip.io", 8883, tls = true)
    private val wss = BrokerEndpoint("1-2-3-4.sslip.io", 443, tls = true, webSocket = true)
    private val freshToken = "jwt-fresh"

    private fun event(
        t: Long,
        failure: AttemptFailure = AttemptFailure.UNREACHABLE,
        notReady: Boolean = false,
        authCode: String? = null,
        used: String? = freshToken,
        attempts: Int = 1,
    ) = DisconnectEvent(
        now = t, failure = failure, brokerNotReady = notReady, authCode = authCode, usedToken = used, latestToken = freshToken,
        tokenExpiresAtMs = t + 20 * 3_600_000L, attempts = attempts,
    )

    @Test
    fun `RA-7 - a lost connection opens the restart window, auth rejections inside it wait 3 5 10 s without a refresh, connecting resets`() {
        val tracker = ReconnectTracker(MqttTransportFallback(listOf(ssl, wss)))
        tracker.onConnected()
        val lost = tracker.onDisconnected(event(0L, notReady = true, attempts = 0), ssl, backoff)
        assertTrue(lost.wasUp)
        assertEquals(0L, tracker.lostAtMs)
        assertTrue(lost.restartWindow)
        assertEquals(1_000L, lost.waitMs)

        val waits = (1..4).map { i ->
            val p = tracker.onDisconnected(event(i * 5_000L, failure = AttemptFailure.BROKER_ANSWERED, authCode = "NOT_AUTHORIZED", attempts = i), ssl, backoff)
            assertFalse("창 안·멀쩡한 토큰 — 갱신하지 않는다", p.refreshForAuth)
            assertTrue(p.rejectedLatest)
            p.waitMs
        }
        assertEquals("거부마다 3 → 5 → 10 s(마지막 값 반복)", listOf(3_000L, 5_000L, 10_000L, 10_000L), waits)
        assertEquals(4, tracker.authRejectsInWindow)
        assertFalse(tracker.awaitingAuth)

        // 창 밖이면 예전처럼 토큰 갱신을 기다린다(최소 15 s)
        val outside = tracker.onDisconnected(event(200_000L, failure = AttemptFailure.BROKER_ANSWERED, authCode = "NOT_AUTHORIZED", attempts = 5), ssl, backoff)
        assertTrue(outside.refreshForAuth)
        assertTrue(tracker.awaitingAuth)
        assertTrue(outside.waitMs >= MqttReconnectPolicy.AUTH_WAIT_MS)

        tracker.onConnected()
        assertNull(tracker.lostAtMs)
        assertEquals(0, tracker.authRejectsInWindow)
        assertFalse(tracker.awaitingAuth)
    }

    @Test
    fun `RA-7 - a rejection of an old token does not ask for a refresh, a never-connected rejection does`() {
        val tracker = ReconnectTracker(MqttTransportFallback(listOf(ssl)))
        val old = tracker.onDisconnected(event(0L, failure = AttemptFailure.BROKER_ANSWERED, authCode = "NOT_AUTHORIZED", used = "jwt-old"), ssl, backoff)
        assertFalse(old.rejectedLatest)
        assertFalse(old.refreshForAuth)
        val first = tracker.onDisconnected(event(1_000L, failure = AttemptFailure.BROKER_ANSWERED, authCode = "NOT_AUTHORIZED"), ssl, backoff)
        assertTrue("붙은 적 없음 = 창 밖 — 토큰 갱신", first.refreshForAuth)
        assertNull(tracker.lostAtMs)
    }

    /**
     * RA-8: 8883 으로 붙어 주행하다 8883 을 막는 망으로 바뀌었다 — 시도마다 연결 시간 초과(10 s). 예전엔 끊긴 뒤 60 s 동안 실패를 세지 않아
     * WSS 로 넘어가기까지 70~90 s 끊겼다. 이제 시간 초과는 창 안에서도 세어 실패 2회(약 30 s 안)에 넘어간다.
     */
    @Test
    fun `RA-8 - a network that starts blocking 8883 mid-drive switches to wss after two timeouts, not after 60 s`() {
        val tracker = ReconnectTracker(MqttTransportFallback(listOf(ssl, wss)))
        tracker.onConnected()
        var t = 0L
        var plan = tracker.onDisconnected(event(t, notReady = true, attempts = 0), ssl, backoff)   // 연결이 끊김(망 바뀜)
        var attempts = 1
        var switchedAt: Long? = null
        while (t < 120_000L && switchedAt == null) {
            t += plan.waitMs + 10_000L   // 기다렸다가 시도 → 10 s 시간 초과
            plan = tracker.onDisconnected(event(t, failure = AttemptFailure.UNREACHABLE, notReady = false, attempts = attempts++), ssl, backoff)
            if (plan.switch?.to == wss) switchedAt = t
        }
        assertTrue("WSS 전환 ${switchedAt}ms (예전 규칙이면 60 s 이후)", switchedAt != null && switchedAt <= 30_000L)
    }

    @Test
    fun `RA-8 - a broker restart (refused, closed before CONNACK, 502) inside the first 60 s does not switch transports`() {
        val tracker = ReconnectTracker(MqttTransportFallback(listOf(ssl, wss)))
        tracker.onConnected()
        var plan = tracker.onDisconnected(event(0L, notReady = true, attempts = 0), ssl, backoff)
        var t = 0L
        var attempts = 1
        while (t < 55_000L) {
            t += plan.waitMs
            plan = tracker.onDisconnected(event(t, failure = AttemptFailure.UNREACHABLE, notReady = true, attempts = attempts++), ssl, backoff)
            assertNull("재시작 신호로는 전환하지 않는다 t=$t", plan.switch)
            assertTrue("재시작 창 백오프 상한 5 s", plan.waitMs <= MqttReconnectPolicy.RESTART_BACKOFF_MAX_MS)
        }
    }

    // ---------------------------------------------------------------- RA-7: 구독 결과 처리(SubscribeRetrier) — 가짜 스케줄러

    private class FakeScheduler {
        val tasks = mutableListOf<Pair<Long, () -> Unit>>()
        fun schedule(delayMs: Long, task: () -> Unit) {
            tasks += delayMs to task
        }

        fun runNext(): Long {
            val (d, task) = tasks.removeAt(0)
            task()
            return d
        }
    }

    @Test
    fun `RA-7 - three denials notify once with 2 4 8 s retries, a new connection generation stops the old retries`() {
        val scheduler = FakeScheduler()
        var generation = 1L
        val wanted = mutableSetOf("dh/v1/group/grp_1/state")
        val sent = mutableListOf<Pair<String, Int>>()
        val denied = mutableSetOf<String>()
        val notified = mutableListOf<String>()
        lateinit var retrier: SubscribeRetrier
        retrier = SubscribeRetrier(
            schedule = scheduler::schedule,
            stillWanted = { topic, gen -> gen == generation && topic in wanted },
            resend = { topic, attempt, gen -> sent += topic to attempt; retrier.onResult(topic, attempt, gen, denied = true) },
            markDenied = { topic, d -> if (d) denied += topic else denied -= topic },
            notifyDenied = { notified += it },
        )
        val topic = "dh/v1/group/grp_1/state"
        assertEquals(2_000L, retrier.onResult(topic, attempt = 0, generation = 1L, denied = true))
        assertEquals(setOf(topic), denied)
        assertTrue(notified.isEmpty())
        assertEquals(2_000L, scheduler.runNext())   // 재시도 #1 → 다시 거부(4 s 예약)
        assertEquals(4_000L, scheduler.runNext())   // 재시도 #2 → 세 번째 거부 → 알림
        assertEquals(listOf(topic), notified)
        assertEquals(listOf(topic to 1, topic to 2), sent)
        // 다시 접속(세대 바뀜) — 예약돼 있던 옛 재시도는 보내지 않는다
        generation = 2L
        assertEquals(8_000L, scheduler.runNext())
        assertEquals("세대가 바뀐 뒤 재시도 없음", 2, sent.size)
        assertEquals("알림은 한 번", 1, notified.size)
        assertTrue(scheduler.tasks.isEmpty())
        // 새 접속에서 받아들여지면 거부 상태를 지운다
        assertNull(retrier.onResult(topic, attempt = 0, generation = 2L, denied = false))
        assertTrue(denied.isEmpty())
    }

    @Test
    fun `RA-7 - a topic unsubscribed while a retry waits is not subscribed again`() {
        val scheduler = FakeScheduler()
        val wanted = mutableSetOf("t")
        var resent = 0
        val retrier = SubscribeRetrier(scheduler::schedule, { topic, _ -> topic in wanted }, { _, _, _ -> resent++ }, { _, _ -> }, { })
        retrier.onResult("t", 0, 1L, denied = true)
        wanted.clear()
        scheduler.runNext()
        assertEquals(0, resent)
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
