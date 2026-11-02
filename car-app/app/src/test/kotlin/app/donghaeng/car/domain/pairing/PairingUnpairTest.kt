package app.donghaeng.car.domain.pairing

import app.donghaeng.car.adapter.vehicle.FakeVehicleAdapter
import app.donghaeng.car.core.common.InMemoryTokenStore
import app.donghaeng.car.core.common.StoredCredentials
import app.donghaeng.car.core.common.TokenClearReason
import app.donghaeng.car.core.common.TokenStoreState
import app.donghaeng.car.core.common.VehicleIdFallback
import app.donghaeng.car.core.network.VehicleSelfApi
import app.donghaeng.car.core.network.VehicleTokenAuthenticator
import app.donghaeng.car.testing.FakeIdentityApi
import app.donghaeng.protocol.DhJson
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.create
import java.util.concurrent.TimeUnit

/** (실제 소켓 + 3 s 상한이라 가상 시간 runTest 대신 runBlocking)
 * A007(docs/03 계약 2): 설정 '연결 해제'는 서버에서도 스스로 해제(DELETE /v1/vehicles/me)한 뒤 로컬을 지운다 — 실패해도 로컬은 지운다 */
class PairingUnpairTest {

    private lateinit var server: MockWebServer
    private lateinit var selfApi: VehicleSelfApi
    private val creds = StoredCredentials("jwt", "r", "veh_B", "엄마 차", "usr_B", "tcp://10.0.2.2:1883", "veh_B", "veh_B")

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        selfApi = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build())
            .addConverterFactory(DhJson.json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun repo(store: InMemoryTokenStore) =
        PairingRepository(FakeIdentityApi(), store, FakeVehicleAdapter(), VehicleIdFallback { "DHAVD-X" }, selfApi)

    @Test
    fun `settings disconnect unpairs on the server first, then clears locally`() = runBlocking {
        val store = InMemoryTokenStore(creds).apply { load() }
        server.enqueue(MockResponse().setResponseCode(204))
        repo(store).disconnect(TokenClearReason.USER_SETTINGS_DISCONNECT)
        val req = server.takeRequest(1, TimeUnit.SECONDS)
        assertNotNull("서버 해제 요청을 보낸다", req)
        assertEquals("DELETE", req!!.method)
        assertEquals("/v1/vehicles/me", req.path)
        assertEquals(TokenStoreState.Empty, store.state.value)
        assertEquals(listOf(TokenClearReason.USER_SETTINGS_DISCONNECT), store.clearReasons)
    }

    @Test
    fun `an already unpaired car (401) or a failing server still clears locally`() = runBlocking {
        val store = InMemoryTokenStore(creds).apply { load() }
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals("ok(already-unpaired)", repo(store).unpairOnServer())

        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        repo(store).disconnect(TokenClearReason.USER_REPAIR)
        assertEquals(TokenStoreState.Empty, store.state.value)
        assertEquals(listOf(TokenClearReason.USER_REPAIR), store.clearReasons)
    }

    @Test
    fun `store reset does not call the server (no readable token)`() = runBlocking {
        val store = InMemoryTokenStore(creds).apply { load() }
        repo(store).disconnect(TokenClearReason.STORE_RESET)
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
        assertEquals(TokenStoreState.Empty, store.state.value)
    }

    @Test
    fun `a 401 on DELETE vehicles me is not refreshed (it means already unpaired)`() {
        var refreshCalls = 0
        val authenticator = VehicleTokenAuthenticator { refreshCalls++; "new" }
        fun response(method: String, path: String) = okhttp3.Response.Builder()
            .request(Request.Builder().url("http://localhost$path").method(method, if (method == "GET") null else ByteArray(0).toRequestBody(null)).header("Authorization", "Bearer old").build())
            .protocol(Protocol.HTTP_1_1).code(401).message("Unauthorized").build()
        assertNull(authenticator.authenticate(null, response("DELETE", "/v1/vehicles/me")))
        assertEquals(0, refreshCalls)
        // 같은 경로라도 소속 조회(GET /v1/vehicles/me/group)의 401 은 평소처럼 갱신·재시도
        assertNotNull(authenticator.authenticate(null, response("GET", "/v1/vehicles/me/group")))
        assertEquals(1, refreshCalls)
        assertTrue(true)
    }
}
