package app.donghaeng.common.web

import app.donghaeng.common.api.ApiException
import app.donghaeng.common.config.DhProperties
import app.donghaeng.protocol.rest.ErrorCodes
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import java.net.InetAddress
import java.time.Clock
import java.time.Duration

/**
 * 빈도 제한 초과(429). [retryAfterSeconds] 는 응답 헤더 `Retry-After` 로 나간다(`GlobalExceptionHandler`).
 * 코드 기본값은 [ErrorCodes.RATE_LIMITED] (docs/03 §1·§2.3).
 */
class RateLimitedException(
    val retryAfterSeconds: Long,
    message: String,
    code: String = ErrorCodes.RATE_LIMITED,
) : ApiException(HttpStatus.TOO_MANY_REQUESTS, code, message)

/** 고정 창 카운터. 저장소 장애면 null(→ 허용 — 빈도 제한 때문에 로그인·페어링이 막히지 않게) */
interface RateCounter {
    fun incrementAndGet(key: String, window: Duration): Long?
}

@Component
class RedisRateCounter(private val redis: StringRedisTemplate) : RateCounter {
    override fun incrementAndGet(key: String, window: Duration): Long? = try {
        val n = redis.opsForValue().increment(key)
        // 첫 증가에서만 만료를 건다(창이 늘어나지 않게). 만료 설정이 실패해 TTL 없는 키가 남아도 키에 분(minute)이 들어 있어 다음 창은 새 키다
        if (n == 1L) redis.expire(key, window)
        n
    } catch (e: Exception) {
        log.warn("rate counter failed → allow cause={}", e.javaClass.simpleName)
        null
    }

    companion object {
        private val log = LoggerFactory.getLogger(RedisRateCounter::class.java)
    }
}

/**
 * IP 당 분당 요청 수 제한 (C094·C106, docs/06 §5) — 인증 없이 DB 에 행을 만들거나(`POST /device/code`) 계정을 여는(`POST /auth/login`) 공개 경로.
 * Redis `rl:{bucket}:{ip}:{분}` INCR(70 s TTL), `dh.rate-limit.ip-per-minute`(기본 30)를 넘으면 429 `RATE_LIMITED` + `Retry-After`(이번 분이 끝날 때까지).
 * 0 이하로 설정하면 끈다. IP 는 [ClientIp] 로 정한다. 로그에는 IP 를 남기지 않는다(개인정보).
 */
@Component
class IpRateLimiter(
    private val counter: RateCounter,
    private val props: DhProperties,
    private val clock: Clock,
) {
    fun check(bucket: String, request: HttpServletRequest) = check(bucket, ClientIp.of(request))

    fun check(bucket: String, ip: String) {
        val limit = props.rateLimit.ipPerMinute
        if (limit <= 0) return
        val nowMs = clock.millis()
        val minute = nowMs / 60_000
        val n = counter.incrementAndGet("rl:$bucket:$ip:$minute", WINDOW) ?: return
        if (n > limit) {
            val retryAfter = ((minute + 1) * 60_000 - nowMs + 999) / 1000
            if (n == limit + 1L) log.warn("rate limited bucket={} limit={}/min", bucket, limit)
            throw RateLimitedException(retryAfter.coerceIn(1, 60), "요청이 너무 많아요. ${retryAfter.coerceIn(1, 60)}초 뒤에 다시 시도해 주세요.")
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(IpRateLimiter::class.java)
        private val WINDOW: Duration = Duration.ofSeconds(70)
        const val BUCKET_LOGIN = "login"
        const val BUCKET_DEVICE_CODE = "devcode"
    }
}

/**
 * 요청한 클라이언트 IP. 클라우드에서는 Caddy 가 앞에 있어 `remoteAddr` 가 Caddy 컨테이너다 → 직접 연결한 쪽이 사설·루프백 주소(같은 호스트·compose 망의
 * 프록시)일 때만 `X-Forwarded-For` 의 **마지막** 값(가장 가까운 프록시가 붙인 값 — Caddy 는 신뢰하지 않는 클라이언트가 보낸 값을 버리고 자기가 본 주소를 넣는다)을 믿는다.
 * 공인 주소에서 직접 온 요청의 헤더는 위조일 수 있어 무시한다.
 */
object ClientIp {
    fun of(request: HttpServletRequest): String = of(request.remoteAddr, request.getHeader("X-Forwarded-For"))

    fun of(remoteAddr: String?, forwardedFor: String?): String {
        val peer = remoteAddr?.trim().orEmpty()
        val forwarded = forwardedFor?.split(',')?.map { it.trim() }?.lastOrNull { it.isNotEmpty() }
        if (forwarded != null && isPrivateOrLoopback(peer)) return forwarded
        return peer.ifEmpty { "unknown" }
    }

    private fun isPrivateOrLoopback(address: String): Boolean {
        if (address.isEmpty()) return false
        // 숫자 주소만 해석한다(이름 조회를 하지 않도록 글자가 섞이면 거절)
        if (!address.all { it.isDigit() || it == '.' || it == ':' || it in 'a'..'f' || it in 'A'..'F' }) return false
        return try {
            val inet = InetAddress.getByName(address)
            inet.isLoopbackAddress || inet.isSiteLocalAddress || inet.isLinkLocalAddress || isUniqueLocalV6(inet)
        } catch (e: Exception) {
            false
        }
    }

    /** IPv6 ULA fc00::/7 (docker IPv6 망) */
    private fun isUniqueLocalV6(inet: InetAddress): Boolean {
        val b = inet.address
        return b.size == 16 && (b[0].toInt() and 0xFE) == 0xFC
    }
}
