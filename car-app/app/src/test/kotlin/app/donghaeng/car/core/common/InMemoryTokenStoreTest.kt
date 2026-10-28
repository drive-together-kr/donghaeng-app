package app.donghaeng.car.core.common

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InMemoryTokenStoreTest {

    private val creds = StoredCredentials(
        vehicleAccessToken = "eyJ.token", refreshToken = "opaque", vehicleId = "veh_01HTEST", nickname = "별명",
        ownerId = "usr_01H", mqttUri = "tcp://10.0.2.2:1883", mqttClientId = "veh_01HTEST", mqttUsername = "veh_01HTEST",
    )

    @Test
    fun `읽기 전은 Loading, 읽으면 Empty`() = runTest {
        val store = InMemoryTokenStore()
        assertEquals(TokenStoreState.Loading, store.state.value)
        assertNull(store.load())
        assertEquals(TokenStoreState.Empty, store.state.value)
    }

    @Test
    fun `save 후 Present, clear 후 Empty 이고 사유·시각·마스킹 vehicleId 가 남는다`() = runTest {
        val store = InMemoryTokenStore(clock = { 1_234L })
        store.save(creds)
        assertEquals(TokenStoreState.Present(creds), store.state.value)
        assertEquals(creds, store.load())
        assertNull(store.lastClear.value)
        store.clear(TokenClearReason.USER_SETTINGS_DISCONNECT)
        assertEquals(TokenStoreState.Empty, store.state.value)
        assertNull(store.load())
        assertEquals(ClearRecord("USER_SETTINGS_DISCONNECT", 1_234L, "veh_…ST"), store.lastClear.value)
    }

    @Test
    fun `읽기 실패는 Unavailable 이고 저장된 값은 지우지 않는다 — 다음 load 에서 다시 읽는다 (T33)`() = runTest {
        val store = InMemoryTokenStore(creds)
        store.unavailableReason = "SecurityException<AEADBadTagException"
        assertNull(store.load())
        assertEquals(TokenStoreState.Unavailable("SecurityException<AEADBadTagException"), store.state.value)
        store.unavailableReason = null
        assertEquals(creds, store.load())
        assertEquals(TokenStoreState.Present(creds), store.state.value)
        assertTrue(store.clearReasons.isEmpty())
    }

    @Test
    fun `updateTokens 는 저장된 refresh 가 기대값일 때만 두 토큰을 바꾼다`() = runTest {
        val store = InMemoryTokenStore(creds)
        store.load()
        assertNull(store.updateTokens(expectedRefreshToken = "다른 값", accessToken = "new", refreshToken = "r2"))
        assertEquals(creds, (store.state.value as TokenStoreState.Present).credentials)
        val updated = store.updateTokens(expectedRefreshToken = "opaque", accessToken = "new", refreshToken = "r2")
        assertEquals(creds.copy(vehicleAccessToken = "new", refreshToken = "r2"), updated)
        assertEquals(TokenStoreState.Present(updated!!), store.state.value)
        store.clear(TokenClearReason.USER_REPAIR)
        assertNull(store.updateTokens(expectedRefreshToken = "r2", accessToken = "x", refreshToken = "y"))
        assertEquals(TokenStoreState.Empty, store.state.value)
    }

    @Test
    fun `JWT exp·iat 를 서명 없이 읽고, 형식이 다르면 null`() {
        val enc = java.util.Base64.getUrlEncoder().withoutPadding()
        val payload = enc.encodeToString("""{"sub":"veh_1","iat":1700000000,"exp":1700086400}""".toByteArray())
        val jwt = "eyJhbGciOiJSUzI1NiJ9.$payload.c2ln"
        assertEquals(1_700_086_400_000L, JwtClaims.expiresAtMs(jwt))
        assertEquals(1_700_000_000L, JwtClaims.issuedAtSeconds(jwt))
        assertNull(JwtClaims.expiresAtMs("jwt-1"))
        assertNull(JwtClaims.expiresAtMs("a.!!!.c"))
        assertNull(JwtClaims.issuedAtSeconds(null))
        assertEquals(TokenClearReason.REFRESH_REJECTED, TokenClearReason.fromName("REFRESH_REJECTED"))
        assertTrue(TokenClearReason.TOKEN_REVOKED_CMD.needsRepairFromPhone && !TokenClearReason.USER_REPAIR.needsRepairFromPhone)
    }

    @Test
    fun `DhLog 는 I·W·E 를 링 버퍼에 남기고 keep=false·D 는 남기지 않는다`() {
        DhLog.i("t33-ring-keep marker")
        DhLog.i("t33-ring-skip marker", keep = false)
        DhLog.d("t33-ring-debug marker")
        DhLog.w("t33-ring-warn marker", IllegalStateException("secret message"))
        val recent = DhLog.recent(DhLog.RING_CAPACITY)
        assertTrue(recent.any { it.contains(" I t33-ring-keep marker") })
        assertTrue(recent.none { it.contains("t33-ring-skip") || it.contains("t33-ring-debug") })
        val warn = recent.last { it.contains("t33-ring-warn") }
        assertTrue("예외는 클래스 이름만: $warn", warn.endsWith("(IllegalStateException)") && !warn.contains("secret message"))
        repeat(DhLog.RING_CAPACITY + 5) { DhLog.i("t33-ring-fill $it") }
        assertEquals(DhLog.RING_CAPACITY, DhLog.recent(DhLog.RING_CAPACITY + 50).size)
    }

    @Test
    fun `차량 ID 마스킹은 앞 4 뒤 2 만 남긴다`() {
        assertEquals("veh_…ST", TextMasking.maskVehicleId(creds.vehicleId))
        assertEquals("FAKE…01", TextMasking.maskVehicleId("FAKE-DH-0001"))
        assertEquals("******", TextMasking.maskVehicleId("ABC123"))
        assertEquals("?", TextMasking.maskVehicleId(null))
        val masked = TextMasking.maskVehicleId("KMHXX00XXXX000001")
        assertEquals("KMHX…01", masked)
        assertTrue(!masked.contains("X00XXXX0000"))
    }

    @Test
    fun `자격 증명과 Present 의 toString 은 마스킹 값만 낸다 (규칙 8, T33 리뷰)`() {
        val secret = StoredCredentials(
            vehicleAccessToken = "eyJhbGciOiJSUzI1NiJ9.payloadPART.signaturePART", refreshToken = "Rf7Qx9SecretRefreshValue",
            vehicleId = "veh_01HSECRETID", nickname = "엄마차", ownerId = "usr_01HOWNERID",
            mqttUri = "tcp://10.0.2.2:1883", mqttClientId = "veh_01HSECRETID", mqttUsername = "veh_01HSECRETID",
        )
        val present = TokenStoreState.Present(secret)
        for (text in listOf(secret.toString(), present.toString(), "token state $present")) {
            listOf("payloadPART", "signaturePART", "SecretRefreshValue", "엄마차", "01HOWNERID", "01HSECRETID").forEach { raw ->
                assertFalse("원문 '$raw' 이 남았다: $text", text.contains(raw))
            }
            assertTrue(text, text.contains("veh_…ID"))
            assertTrue(text, text.contains("eyJh…(len=${secret.vehicleAccessToken.length})"))
        }
        // equals 는 그대로(원문 비교) — 마스킹은 문자열화에만
        assertEquals(secret, secret.copy())
        assertTrue(secret != secret.copy(refreshToken = "other"))
    }
}
