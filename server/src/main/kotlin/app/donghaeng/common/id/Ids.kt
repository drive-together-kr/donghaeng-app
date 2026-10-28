package app.donghaeng.common.id

import java.security.SecureRandom
import java.util.Random

/**
 * ULID 생성기 (https://github.com/ulid/spec) — 외부 라이브러리 없이 구현.
 *
 * - 26자, Crockford Base32 (I, L, O, U 제외), 대문자
 * - 앞 10자 = 48비트 epoch millis, 뒤 16자 = 80비트 난수
 * - 같은 밀리초 안에서는 난수 부분을 1씩 증가시켜 단조 증가(monotonic)를 보장한다.
 */
class UlidGenerator(private val random: Random = SecureRandom()) {

    private var lastTime = -1L
    private var hi = 0L // 난수 상위 16비트
    private var lo = 0L // 난수 하위 64비트

    @Synchronized
    fun next(nowMs: Long = System.currentTimeMillis()): String {
        require(nowMs >= 0) { "timestamp must be non-negative" }
        if (nowMs <= lastTime) {
            // 같은 밀리초(또는 시계 역행): 마지막 값 + 1
            lo += 1
            if (lo == 0L) {
                hi += 1
                check(hi <= 0xFFFF) { "ULID random overflow within one millisecond" }
            }
        } else {
            lastTime = nowMs
            hi = random.nextInt(1 shl 16).toLong()
            lo = random.nextLong()
        }
        return encode(lastTime, hi, lo)
    }

    private fun encode(time: Long, randomHi: Long, randomLo: Long): String {
        require(time <= MAX_TIME) { "timestamp exceeds 48 bits" }
        val out = CharArray(LENGTH)
        var t = time
        for (i in 9 downTo 0) {
            out[i] = ALPHABET[(t and 0x1F).toInt()]
            t = t ushr 5
        }
        // 80비트 난수를 (hi 16비트, lo 64비트) 로 들고 5비트씩 아래에서부터 뽑는다.
        var hi = randomHi
        var lo = randomLo
        for (i in 25 downTo 10) {
            out[i] = ALPHABET[(lo and 0x1F).toInt()]
            lo = (lo ushr 5) or (hi shl 59)
            hi = hi ushr 5
        }
        return String(out)
    }

    companion object {
        const val LENGTH = 26
        const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        private const val MAX_TIME = (1L shl 48) - 1
    }
}

/**
 * 접두사가 붙은 ID (docs/04 §1): `usr_`, `veh_`, `grp_`, `inv_`, `evt_`, `prp_`, `vce_`(F16 무전, docs/04 V6) + ULID.
 */
object Ids {
    private val generator = UlidGenerator()

    fun ulid(): String = generator.next()

    fun user(): String = "usr_" + ulid()
    fun vehicle(): String = "veh_" + ulid()
    fun group(): String = "grp_" + ulid()
    fun invite(): String = "inv_" + ulid()
    fun event(): String = "evt_" + ulid()
    fun proposal(): String = "prp_" + ulid()
    fun voice(): String = VOICE_PREFIX + ulid()

    /** 무전 ID 접두사 — 다운로드 경로의 `{voiceId}` 형식 검사에도 쓴다 */
    const val VOICE_PREFIX = "vce_"
}
