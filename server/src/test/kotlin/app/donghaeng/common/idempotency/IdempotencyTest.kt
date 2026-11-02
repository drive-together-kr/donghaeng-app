package app.donghaeng.common.idempotency

import app.donghaeng.common.api.ApiException
import app.donghaeng.protocol.rest.ErrorCodes
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 계약 3 — `Idempotency-Key` 형식 검사와 '첫 성공 응답 재생' 규칙 */
class IdempotencyTest {

    @Serializable
    data class Body(val id: Int)

    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }

    private val clock = MutableClock(Instant.parse("2026-11-02T00:00:00Z"))
    private val store = InMemoryIdempotencyStore()
    private val idempotency = Idempotency(store, clock)

    @Test
    fun `key format - absent or blank means no key, 8-64 of A-Za-z0-9_- is accepted, anything else is 400`() {
        assertNull(Idempotency.parseKey(null))
        assertNull(Idempotency.parseKey("  "))
        assertEquals("abcdEFGH", Idempotency.parseKey(" abcdEFGH "))
        assertEquals("123e4567-e89b-12d3-a456-426614174000", Idempotency.parseKey("123e4567-e89b-12d3-a456-426614174000"), "UUID")
        assertEquals("a".repeat(64), Idempotency.parseKey("a".repeat(64)))
        for (bad in listOf("short", "a".repeat(65), "has space1", "키값한글키값한글", "abc/defgh")) {
            val e = assertThrows<ApiException>(bad) { Idempotency.parseKey(bad) }
            assertEquals(400, e.status.value())
            assertEquals(ErrorCodes.VALIDATION_FAILED, e.code)
        }
    }

    @Test
    fun `same key replays the first result without running again, for 24 hours, per caller and path`() {
        var runs = 0
        fun call(caller: String = "usr_1", path: String = "/v1/groups/g/messages", key: String? = "key-00000001") =
            idempotency.run(caller, path, key, 201, Body.serializer()) { Body(++runs) }

        assertEquals(Body(1), call())
        assertEquals(Body(1), call(), "재시도는 첫 응답")
        assertEquals(1, runs)
        assertEquals(Body(2), call(caller = "usr_2"), "다른 호출자")
        assertEquals(Body(3), call(path = "/v1/groups/h/messages"), "다른 경로")
        assertEquals(Body(4), call(key = null), "키 없으면 매번 처리")
        clock.now = clock.now.plus(Duration.ofHours(24)).plusSeconds(1)
        assertEquals(Body(5), call(), "24시간 뒤에는 새 요청")
    }

    @Test
    fun `a failed run stores nothing so the same key is processed again`() {
        var attempts = 0
        assertThrows<IllegalStateException> {
            idempotency.run("veh_1", "/v1/groups/g/voice", "voice-key-001", 201, Body.serializer()) { attempts++; throw IllegalStateException("429") }
        }
        assertEquals(0, store.size)
        assertEquals(Body(7), idempotency.run("veh_1", "/v1/groups/g/voice", "voice-key-001", 201, Body.serializer()) { attempts++; Body(7) })
        assertEquals(2, attempts)
    }
}
