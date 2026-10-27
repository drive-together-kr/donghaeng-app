package app.donghaeng.identity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CarIdHasherTest {

    private val hex64 = Regex("^[0-9a-f]{64}$")

    @Test
    fun `hash is deterministic sha256 hex of id plus salt`() {
        val hasher = CarIdHasher("dev-salt-change-me")
        val a = hasher.hash("CAR-INFO-ID-0001")
        val b = hasher.hash("CAR-INFO-ID-0001")
        assertEquals(a, b)
        assertTrue(hex64.matches(a), a)
        // 알려진 벡터: SHA-256("abc") — salt 가 비면 id 만 해시된다
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            CarIdHasher("").hash("abc"),
        )
        // salt 는 id 뒤에 붙는다: SHA-256("ab" + "c") == SHA-256("abc")
        assertEquals(CarIdHasher("").hash("abc"), CarIdHasher("c").hash("ab"))
    }

    @Test
    fun `different ids or salts give different hashes`() {
        val hasher = CarIdHasher("salt-a")
        assertNotEquals(hasher.hash("CAR-1"), hasher.hash("CAR-2"))
        assertNotEquals(hasher.hash("CAR-1"), CarIdHasher("salt-b").hash("CAR-1"))
    }

    @Test
    fun `hash never contains the raw id`() {
        val id = "VERY-SECRET-CAR-INFO-ID"
        assertTrue(!CarIdHasher("s").hash(id).contains(id, ignoreCase = true))
    }
}
