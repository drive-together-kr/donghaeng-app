package app.donghaeng.common.id

import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IdsTest {

    /** Crockford Base32: 0-9, A-Z 중 I, L, O, U 제외 */
    private val ulidPattern = Regex("^[0-9A-HJKMNP-TV-Z]{26}$")

    @Test
    fun `ulid is 26 chars of Crockford base32`() {
        repeat(1000) {
            val id = Ids.ulid()
            assertEquals(26, id.length, id)
            assertTrue(ulidPattern.matches(id), "invalid ulid: $id")
        }
    }

    @Test
    fun `prefixed helpers use the docs-04 prefixes`() {
        val cases = mapOf(
            "usr_" to Ids.user(),
            "veh_" to Ids.vehicle(),
            "grp_" to Ids.group(),
            "inv_" to Ids.invite(),
            "evt_" to Ids.event(),
            "prp_" to Ids.proposal(),
        )
        cases.forEach { (prefix, id) ->
            assertTrue(id.startsWith(prefix), id)
            assertEquals(30, id.length, id)
            assertTrue(ulidPattern.matches(id.removePrefix(prefix)), id)
        }
    }

    @Test
    fun `time component encodes epoch millis per the ULID spec`() {
        // ULID 스펙 예시: 1469918176385 ms → "01ARYZ6S41"
        assertEquals("01ARYZ6S41", UlidGenerator(Random(42)).next(1469918176385L).substring(0, 10))
        assertEquals("0000000000", UlidGenerator(Random(1)).next(0L).substring(0, 10))
    }

    @Test
    fun `later timestamps sort after earlier ones`() {
        val gen = UlidGenerator(Random(7))
        val a = gen.next(1_000_000L)
        val b = gen.next(1_000_001L)
        val c = gen.next(2_000_000L)
        assertTrue(a < b, "$a < $b")
        assertTrue(b < c, "$b < $c")

        val x = Ids.ulid()
        val y = Ids.ulid()
        assertTrue(x < y, "$x < $y")
    }

    @Test
    fun `monotonic and unique within the same millisecond`() {
        val gen = UlidGenerator(Random(3))
        val ids = List(2000) { gen.next(1_700_000_000_000L) }
        assertEquals(ids.size, ids.toSet().size, "duplicates")
        for (i in 1 until ids.size) {
            assertTrue(ids[i - 1] < ids[i], "${ids[i - 1]} < ${ids[i]}")
        }
        val timePart = ids.first().substring(0, 10)
        assertTrue(ids.all { it.startsWith(timePart) })
    }

    @Test
    fun `increment carries from the low 64 bits into the high 16 bits`() {
        val fixed = object : Random() {
            override fun nextInt(bound: Int): Int = 5
            override fun nextLong(): Long = -1L // 하위 64비트가 전부 1 → 다음 증가에서 자리올림
        }
        val gen = UlidGenerator(fixed)
        val a = gen.next(10L)
        val b = gen.next(10L)
        assertTrue(a < b, "$a < $b")
        assertEquals(a.substring(0, 10), b.substring(0, 10))
    }
}
