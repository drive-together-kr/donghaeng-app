package app.donghaeng.car.core.common

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
    fun `save 후 Present, clear 후 Empty`() = runTest {
        val store = InMemoryTokenStore()
        store.save(creds)
        assertEquals(TokenStoreState.Present(creds), store.state.value)
        assertEquals(creds, store.load())
        store.clear()
        assertEquals(TokenStoreState.Empty, store.state.value)
        assertNull(store.load())
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
}
