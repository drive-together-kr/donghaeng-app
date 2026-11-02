package app.donghaeng.common.web

import app.donghaeng.common.config.DhProperties
import app.donghaeng.protocol.rest.ErrorCodes
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** C094·C106: IP 당 분당 요청 수 제한과 클라이언트 IP 판정 */
class RateLimitTest {

    private class MapCounter : RateCounter {
        val counts = mutableMapOf<String, Long>()
        var down = false
        override fun incrementAndGet(key: String, window: Duration): Long? {
            if (down) return null
            return (counts.getOrDefault(key, 0L) + 1).also { counts[key] = it }
        }
    }

    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }

    private fun props(limit: Int) = DhProperties(
        publicBaseUrl = "http://x",
        mqtt = DhProperties.Mqtt(uri = "tcp://x", publicUri = "tcp://x"),
        rateLimit = DhProperties.RateLimit(ipPerMinute = limit),
    )

    @Test
    fun `the 31st call from one IP within a minute is 429 RATE_LIMITED with Retry-After, other IPs and the next minute are free`() {
        val counter = MapCounter()
        val clock = MutableClock(Instant.parse("2026-11-02T00:00:15Z"))
        val limiter = IpRateLimiter(counter, props(30), clock)
        repeat(30) { limiter.check(IpRateLimiter.BUCKET_LOGIN, "203.0.113.7") }
        val e = assertThrows<RateLimitedException> { limiter.check(IpRateLimiter.BUCKET_LOGIN, "203.0.113.7") }
        assertEquals(429, e.status.value())
        assertEquals(ErrorCodes.RATE_LIMITED, e.code)
        assertEquals(45, e.retryAfterSeconds, "이번 분이 끝날 때까지")
        limiter.check(IpRateLimiter.BUCKET_LOGIN, "198.51.100.9") // 다른 IP
        limiter.check(IpRateLimiter.BUCKET_DEVICE_CODE, "203.0.113.7") // 다른 경로
        clock.now = Instant.parse("2026-11-02T00:01:00Z")
        limiter.check(IpRateLimiter.BUCKET_LOGIN, "203.0.113.7") // 다음 분
    }

    @Test
    fun `a broken counter or a disabled limit lets requests through`() {
        val counter = MapCounter().apply { down = true }
        val clock = MutableClock(Instant.EPOCH)
        val limiter = IpRateLimiter(counter, props(1), clock)
        repeat(5) { limiter.check(IpRateLimiter.BUCKET_LOGIN, "203.0.113.7") }
        val off = IpRateLimiter(MapCounter(), props(0), clock)
        repeat(100) { off.check(IpRateLimiter.BUCKET_LOGIN, "203.0.113.7") }
    }

    @Test
    fun `client ip trusts X-Forwarded-For only from a private or loopback proxy and takes its last hop`() {
        assertEquals("203.0.113.7", ClientIp.of("172.18.0.5", "203.0.113.7"), "compose 망의 Caddy")
        assertEquals("203.0.113.7", ClientIp.of("127.0.0.1", "10.9.9.9, 203.0.113.7"), "가장 가까운 프록시가 붙인 값")
        assertEquals("198.51.100.1", ClientIp.of("198.51.100.1", "203.0.113.7"), "공인 주소에서 직접 온 요청의 헤더는 믿지 않는다")
        assertEquals("172.18.0.5", ClientIp.of("172.18.0.5", null))
        assertEquals("172.18.0.5", ClientIp.of("172.18.0.5", " , "))
        assertTrue(ClientIp.of(null, null).isNotEmpty())
        assertEquals("example.com", ClientIp.of("example.com", "1.1.1.1"), "이름은 해석하지 않고(사설 판정 안 함) 헤더도 믿지 않는다")
    }
}
