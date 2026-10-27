package app.donghaeng.car.feature.pairing

import app.donghaeng.car.adapter.vehicle.FakeVehicleAdapter
import app.donghaeng.car.adapter.vehicle.VehicleAdapter
import app.donghaeng.car.core.common.InMemoryTokenStore
import app.donghaeng.car.core.common.StoredCredentials
import app.donghaeng.car.core.common.TokenStoreState
import app.donghaeng.car.core.common.VehicleIdFallback
import app.donghaeng.car.core.network.IdentityApi
import app.donghaeng.car.domain.pairing.PairingRepository
import app.donghaeng.protocol.rest.DeviceCodeRequest
import app.donghaeng.protocol.rest.DeviceCodeResponse
import app.donghaeng.protocol.rest.DeviceTokenRequest
import app.donghaeng.protocol.rest.DeviceTokenResponse
import app.donghaeng.protocol.rest.ErrorCodes
import app.donghaeng.protocol.rest.MqttInfo
import app.donghaeng.protocol.rest.VehicleSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.io.IOException

/**
 * 페어링 상태 기계 (docs/08 T12). 가상 시간으로 interval 폴링·백오프·만료 재발급을 검증한다.
 * IdentityApi 는 응답을 미리 적어 둔 스크립트, TokenStore 는 메모리.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PairingViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val json = "application/json".toMediaType()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** 응답 스크립트. 호출 시각(가상 ms)을 기록해 폴링 간격을 검증한다. */
    private inner class ScriptedIdentityApi : IdentityApi {
        val codeResponses = ArrayDeque<() -> Response<DeviceCodeResponse>>()
        val tokenResponses = ArrayDeque<() -> Response<DeviceTokenResponse>>()
        var codeCalls = 0
        val tokenCallTimes = mutableListOf<Long>()
        var lastCodeRequest: DeviceCodeRequest? = null

        override suspend fun requestDeviceCode(body: DeviceCodeRequest): Response<DeviceCodeResponse> {
            codeCalls++
            lastCodeRequest = body
            return (codeResponses.removeFirstOrNull() ?: error("device/code 스크립트 소진 (call=$codeCalls)"))()
        }

        override suspend fun requestDeviceToken(body: DeviceTokenRequest): Response<DeviceTokenResponse> {
            tokenCallTimes += dispatcher.scheduler.currentTime
            return (tokenResponses.removeFirstOrNull() ?: error("device/token 스크립트 소진 (call=${tokenCallTimes.size})"))()
        }
    }

    private fun envelope(code: String) = """{"error":{"code":"$code","message":"m","traceId":"t"}}""".toResponseBody(json)
    private fun codeOk(expiresIn: Int = 600, interval: Int = 3, deviceCode: String = "dc_1"): () -> Response<DeviceCodeResponse> =
        { Response.success(DeviceCodeResponse(deviceCode, "KQ74MZ", "http://10.0.2.2:8080/pair?c=KQ74MZ", expiresIn, interval)) }
    private fun tokenOk(): () -> Response<DeviceTokenResponse> = {
        Response.success(
            DeviceTokenResponse(
                vehicleAccessToken = "eyJ.vehicle", refreshToken = "opaque",
                vehicle = VehicleSummary("veh_01HTEST", "별명차", "usr_01H"),
                mqtt = MqttInfo("tcp://10.0.2.2:1883", "veh_01HTEST", "veh_01HTEST"),
            ),
        )
    }
    private fun pending(): () -> Response<DeviceTokenResponse> = { Response.error(428, envelope(ErrorCodes.AUTHORIZATION_PENDING)) }
    private fun slowDown(): () -> Response<DeviceTokenResponse> = { Response.error(429, envelope(ErrorCodes.SLOW_DOWN)) }
    private fun expired(): () -> Response<DeviceTokenResponse> = { Response.error(410, envelope(ErrorCodes.CODE_EXPIRED)) }
    private fun notFound(): () -> Response<DeviceTokenResponse> = { Response.error(404, envelope(ErrorCodes.CODE_NOT_FOUND)) }

    private fun TestScope.createViewModel(
        api: IdentityApi,
        store: InMemoryTokenStore = InMemoryTokenStore(),
        vehicleAdapter: VehicleAdapter = FakeVehicleAdapter(),
        fallback: VehicleIdFallback = VehicleIdFallback { "DHAVD-TESTFALLBACK" },
    ) = PairingViewModel(
        repository = PairingRepository(api, store, vehicleAdapter, fallback),
        clock = { testScheduler.currentTime },
        autoStart = true,
    )

    @Test
    fun `저장된 토큰이 있으면 API 호출 없이 Connected`() = runTest(dispatcher) {
        val creds = StoredCredentials("tok", "ref", "veh_SAVED01", "저장차", "usr_1", "tcp://h:1883", "veh_SAVED01", "veh_SAVED01")
        val api = ScriptedIdentityApi()
        val vm = createViewModel(api, InMemoryTokenStore(creds))
        advanceUntilIdle()
        assertEquals(PairingState.Connected("veh_SAVED01", "저장차"), vm.state.value)
        assertEquals(0, api.codeCalls)
    }

    @Test
    fun `428 428 200 - interval 마다 폴링하고 토큰을 저장한 뒤 Connected 와 Paired 이벤트`() = runTest(dispatcher) {
        val api = ScriptedIdentityApi().apply {
            codeResponses += codeOk(interval = 3)
            tokenResponses += pending(); tokenResponses += pending(); tokenResponses += tokenOk()
        }
        val store = InMemoryTokenStore()
        val vm = createViewModel(api, store)
        val events = mutableListOf<PairingEvent>()
        // 전경 코루틴으로 첫 이벤트만 받는다(backgroundScope 는 advanceUntilIdle 이 기다려 주지 않는다)
        launch { events += vm.events.first() }

        runCurrent()
        val issued = vm.state.value
        assertTrue("코드 발급 후 CodeIssued 여야 한다: $issued", issued is PairingState.CodeIssued)
        issued as PairingState.CodeIssued
        assertEquals("KQ74MZ", issued.userCode)
        assertEquals(600_000L, issued.expiresAtMillis)
        assertEquals(1, issued.attempt)
        assertEquals("FAKE-DH-0001", api.lastCodeRequest?.vehicleId)

        advanceUntilIdle()
        assertEquals(listOf(3_000L, 6_000L, 9_000L), api.tokenCallTimes)
        assertEquals(PairingState.Connected("veh_01HTEST", "별명차"), vm.state.value)
        val saved = (store.state.value as TokenStoreState.Present).credentials
        assertEquals("eyJ.vehicle", saved.vehicleAccessToken)
        assertEquals("veh_01HTEST", saved.mqttClientId)
        assertEquals(listOf<PairingEvent>(PairingEvent.Paired), events)
    }

    @Test
    fun `429 SLOW_DOWN 이면 다음 폴링은 interval+2 초 뒤`() = runTest(dispatcher) {
        val api = ScriptedIdentityApi().apply {
            codeResponses += codeOk(interval = 3)
            tokenResponses += slowDown(); tokenResponses += pending(); tokenResponses += tokenOk()
        }
        val vm = createViewModel(api)
        advanceUntilIdle()
        assertEquals(listOf(3_000L, 8_000L, 13_000L), api.tokenCallTimes)
        assertTrue(vm.state.value is PairingState.Connected)
    }

    @Test
    fun `410 CODE_EXPIRED 면 새 코드를 요청하고 3회를 넘기면 Error`() = runTest(dispatcher) {
        val api = ScriptedIdentityApi().apply {
            repeat(3) { codeResponses += codeOk(deviceCode = "dc_$it") }
            repeat(3) { tokenResponses += expired() }
        }
        val vm = createViewModel(api)
        advanceUntilIdle()
        assertEquals(3, api.codeCalls)
        val error = vm.state.value
        assertTrue("Error 여야 한다: $error", error is PairingState.Error)
        assertEquals(PairingErrorReason.TOO_MANY_CODES, (error as PairingState.Error).reason)
        assertTrue(error.retryable)
    }

    @Test
    fun `로컬 만료(expiresIn 경과)면 서버를 부르지 않고 새 코드를 받는다`() = runTest(dispatcher) {
        val api = ScriptedIdentityApi().apply {
            codeResponses += codeOk(expiresIn = 5, interval = 3)   // 3초: 폴링(428) → 6초: 만료 → 새 코드
            codeResponses += codeOk(expiresIn = 600, interval = 3)
            tokenResponses += pending(); tokenResponses += tokenOk()
        }
        val vm = createViewModel(api)
        advanceUntilIdle()
        assertEquals(2, api.codeCalls)
        assertEquals(listOf(3_000L, 9_000L), api.tokenCallTimes)
        assertEquals(PairingState.Connected("veh_01HTEST", "별명차"), vm.state.value)
    }

    @Test
    fun `폴링 404 CODE_NOT_FOUND 도 새 코드를 받는다`() = runTest(dispatcher) {
        val api = ScriptedIdentityApi().apply {
            codeResponses += codeOk(); codeResponses += codeOk(deviceCode = "dc_2")
            tokenResponses += notFound(); tokenResponses += tokenOk()
        }
        val vm = createViewModel(api)
        advanceUntilIdle()
        assertEquals(2, api.codeCalls)
        assertTrue(vm.state.value is PairingState.Connected)
    }

    @Test
    fun `네트워크 오류는 재시도 가능한 Error 이고 retry 로 이어 간다`() = runTest(dispatcher) {
        val api = ScriptedIdentityApi().apply {
            codeResponses += { throw IOException("connect refused") }
            codeResponses += codeOk()
            tokenResponses += tokenOk()
        }
        val vm = createViewModel(api)
        advanceUntilIdle()
        val error = vm.state.value
        assertTrue("Error 여야 한다: $error", error is PairingState.Error)
        assertEquals(PairingErrorReason.NETWORK, (error as PairingState.Error).reason)
        assertTrue(error.retryable)

        vm.retry()
        advanceUntilIdle()
        assertEquals(2, api.codeCalls)
        assertTrue(vm.state.value is PairingState.Connected)
    }

    @Test
    fun `device code 가 404(엔드포인트 없음) 면 SERVER 오류`() = runTest(dispatcher) {
        val api = ScriptedIdentityApi().apply {
            codeResponses += { Response.error(404, "Not Found".toResponseBody("text/plain".toMediaType())) }
        }
        val vm = createViewModel(api)
        advanceUntilIdle()
        val error = vm.state.value as PairingState.Error
        assertEquals(PairingErrorReason.SERVER, error.reason)
        assertEquals("HTTP 404", error.detail)
        assertEquals(0, api.tokenCallTimes.size)
    }

    @Test
    fun `CarInfo 가 차량 ID 를 못 주면 설치별 폴백 ID 로 코드를 요청한다`() = runTest(dispatcher) {
        val api = ScriptedIdentityApi().apply { codeResponses += codeOk(); tokenResponses += tokenOk() }
        val noId = object : VehicleAdapter by FakeVehicleAdapter() {
            override suspend fun vehicleId(): String? = null
        }
        val vm = createViewModel(api, vehicleAdapter = noId)
        advanceUntilIdle()
        assertEquals(1, api.codeCalls)
        assertEquals("DHAVD-TESTFALLBACK", api.lastCodeRequest?.vehicleId)
        assertEquals("FAKE", api.lastCodeRequest?.model)
        assertTrue(vm.state.value is PairingState.Connected)
    }

    @Test
    fun `폴백 ID 마저 실패하면 서버를 부르지 않고 VEHICLE_ID_UNAVAILABLE`() = runTest(dispatcher) {
        val api = ScriptedIdentityApi()
        val noId = object : VehicleAdapter by FakeVehicleAdapter() {
            override suspend fun vehicleId(): String? = null
        }
        val vm = createViewModel(api, vehicleAdapter = noId, fallback = VehicleIdFallback { error("prefs unavailable") })
        advanceUntilIdle()
        val error = vm.state.value as PairingState.Error
        assertEquals(PairingErrorReason.VEHICLE_ID_UNAVAILABLE, error.reason)
        assertEquals(0, api.codeCalls)
    }

    @Test
    fun `연결 상태에서 disconnectAndRepair 는 토큰을 지우고 새 코드를 받는다`() = runTest(dispatcher) {
        val creds = StoredCredentials("tok", "ref", "veh_SAVED01", "저장차", "usr_1", "tcp://h:1883", "veh_SAVED01", "veh_SAVED01")
        val store = InMemoryTokenStore(creds)
        val api = ScriptedIdentityApi().apply { codeResponses += codeOk() }
        val vm = createViewModel(api, store)
        advanceUntilIdle()
        assertTrue(vm.state.value is PairingState.Connected)

        vm.disconnectAndRepair()
        runCurrent()
        assertEquals(TokenStoreState.Empty, store.state.value)
        assertTrue("새 코드 발급 상태여야 한다: ${vm.state.value}", vm.state.value is PairingState.CodeIssued)
    }
}
