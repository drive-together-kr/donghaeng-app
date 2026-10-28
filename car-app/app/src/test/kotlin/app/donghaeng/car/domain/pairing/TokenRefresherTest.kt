package app.donghaeng.car.domain.pairing

import app.donghaeng.car.core.common.InMemoryTokenStore
import app.donghaeng.car.core.common.StoredCredentials
import app.donghaeng.car.core.common.TokenClearReason
import app.donghaeng.car.core.common.TokenStoreState
import app.donghaeng.car.core.network.VehicleTokenAuthenticator
import app.donghaeng.car.testing.FakeIdentityApi
import app.donghaeng.car.testing.fakeJwt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * T33 차량 토큰 갱신: 선제 갱신(만료 2시간 전), 401 → 1회 갱신·재시도(single-flight), 갱신 401 → REFRESH_REJECTED.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TokenRefresherTest {

    private val nowMs = 1_700_000_000_000L
    private val nowSec = nowMs / 1000

    private fun creds(access: String, refresh: String = "r1") = StoredCredentials(
        vehicleAccessToken = access, refreshToken = refresh, vehicleId = "veh_B", nickname = "엄마 차", ownerId = "usr_B",
        mqttUri = "tcp://10.0.2.2:1883", mqttClientId = "veh_B", mqttUsername = "veh_B",
    )

    @Test
    fun `만료 2시간 전부터 선제 갱신하고 회전된 토큰 쌍을 함께 저장한다`() = runTest {
        val soon = fakeJwt(expSec = nowSec + 3600)           // 1시간 남음 → 갱신
        val fresh = fakeJwt(expSec = nowSec + 24 * 3600, jti = "new")
        val tokens = InMemoryTokenStore(creds(soon)).also { it.load() }
        val api = FakeIdentityApi().apply { refreshHandler = { FakeIdentityApi.ok(fresh, "r2") } }
        val refresher = TokenRefresher(api, tokens, clock = { nowMs })

        val outcome = refresher.refreshIfExpiring(RefreshTrigger.STARTUP)
        assertTrue(outcome is RefreshOutcome.Refreshed && outcome.rotatedNow)
        assertEquals(listOf("r1"), api.refreshCalls.map { it.refreshToken })
        val stored = (tokens.state.value as TokenStoreState.Present).credentials
        assertEquals(fresh, stored.vehicleAccessToken)
        assertEquals("refresh 도 회전값으로", "r2", stored.refreshToken)
        assertEquals((nowSec + 24 * 3600) * 1000, refresher.state.value.accessExpiresAtMs)
        assertEquals(1, refresher.state.value.refreshes)
    }

    @Test
    fun `만료까지 여유가 있거나 exp 를 읽을 수 없으면 선제 갱신하지 않는다`() = runTest {
        val later = fakeJwt(expSec = nowSec + 5 * 3600)
        val tokens = InMemoryTokenStore(creds(later)).also { it.load() }
        val api = FakeIdentityApi()
        val refresher = TokenRefresher(api, tokens, clock = { nowMs })
        assertEquals(RefreshOutcome.NotNeeded, refresher.refreshIfExpiring())

        tokens.save(creds("not-a-jwt"))
        assertEquals(RefreshOutcome.NotNeeded, refresher.refreshIfExpiring())
        assertTrue(api.refreshCalls.isEmpty())

        tokens.clear(TokenClearReason.USER_SETTINGS_DISCONNECT)
        assertEquals(RefreshOutcome.NotPaired, refresher.refreshIfExpiring())
    }

    @Test
    fun `start 는 시작 직후 한 번, 이후 30분마다 만료를 본다`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val clock = { nowMs + testScheduler.currentTime }
        // 2시간 45분 남음: 시작(2h45m)·30분 뒤(2h15m)엔 갱신 없음, 60분 뒤(1h45m)에 갱신
        val tokens = InMemoryTokenStore(creds(fakeJwt(expSec = nowSec + 2 * 3600 + 45 * 60)))
        val api = FakeIdentityApi().apply { refreshHandler = { FakeIdentityApi.ok(fakeJwt(expSec = clock() / 1000 + 24 * 3600, jti = "n${refreshCalls.size}"), "r${refreshCalls.size + 1}") } }
        val refresher = TokenRefresher(api, tokens, clock = clock)
        refresher.start(dispatcher)
        // 무한 주기 루프 — 단언이 실패해도 runTest 가 끝에서 남은 가상 시간을 끝없이 돌리지 않게 반드시 멈춘다
        try {
            runCurrent()
            assertTrue("시작 시 갱신 없음", api.refreshCalls.isEmpty())

            advanceTimeBy(TokenRefresher.CHECK_INTERVAL_MS + 1)
            runCurrent()
            assertTrue("30분 점검에서는 아직 2시간 넘게 남았다: ${api.refreshCalls.size}", api.refreshCalls.isEmpty())
            advanceTimeBy(TokenRefresher.CHECK_INTERVAL_MS)
            runCurrent()
            assertEquals("두 번째 점검(만료 2시간 안)에서 갱신", 1, api.refreshCalls.size)
            assertEquals("r2", (tokens.state.value as TokenStoreState.Present).credentials.refreshToken)
        } finally {
            refresher.stop()
        }
    }

    @Test
    fun `동시에 들어온 401 여러 건은 갱신 한 번으로 끝나고 모두 새 토큰을 받는다(single-flight)`() = runTest {
        val old = fakeJwt(expSec = nowSec + 600, jti = "old")
        val new = fakeJwt(expSec = nowSec + 24 * 3600, jti = "new")
        val tokens = InMemoryTokenStore(creds(old)).also { it.load() }
        val gate = CompletableDeferred<Unit>()
        val api = FakeIdentityApi().apply {
            refreshHandler = {
                gate.await()
                FakeIdentityApi.ok(new, "r2")
            }
        }
        val refresher = TokenRefresher(api, tokens, clock = { nowMs })

        val results = (1..3).map { async { refresher.refreshAfterRejection(old, RefreshTrigger.HTTP_401) } }
        runCurrent()
        gate.complete(Unit)
        val outcomes = results.awaitAll()
        assertEquals("서버 갱신은 한 번", 1, api.refreshCalls.size)
        assertTrue(outcomes.all { it.accessTokenOrNull == new })
        assertEquals(1, outcomes.count { (it as RefreshOutcome.Refreshed).rotatedNow })

        // 같은 새 토큰이 1분 안에 또 거부되면 회전을 반복하지 않는다
        assertEquals(RefreshOutcome.Throttled, refresher.refreshAfterRejection(new, RefreshTrigger.HTTP_401))
        assertEquals(1, api.refreshCalls.size)
    }

    @Test
    fun `갱신이 401 이면 REFRESH_REJECTED 로 자격 증명을 지운다, 네트워크 실패는 토큰을 그대로 둔다`() = runTest {
        val old = fakeJwt(expSec = nowSec + 600)
        val tokens = InMemoryTokenStore(creds(old)).also { it.load() }
        val api = FakeIdentityApi().apply { refreshHandler = { throw IOException("connect refused") } }
        val refresher = TokenRefresher(api, tokens, clock = { nowMs })

        val failed = refresher.refreshAfterRejection(old, RefreshTrigger.HTTP_401)
        assertTrue(failed is RefreshOutcome.Failed)
        assertTrue("네트워크 실패는 토큰 유지", tokens.state.value is TokenStoreState.Present)
        assertTrue(tokens.clearReasons.isEmpty())

        api.refreshHandler = { FakeIdentityApi.unauthorized() }
        assertEquals(RefreshOutcome.Rejected, refresher.refreshAfterRejection(old, RefreshTrigger.HTTP_401))
        assertEquals(TokenStoreState.Empty, tokens.state.value)
        assertEquals(listOf(TokenClearReason.REFRESH_REJECTED), tokens.clearReasons)
        assertEquals("REFRESH_REJECTED", tokens.lastClear.value?.reason)
        assertEquals("REJECTED HTTP_401", refresher.state.value.lastResult)
    }

    @Test
    fun `갱신 도중 연결 해제·재페어링이 있었으면 결과를 버리고 새 자격 증명을 건드리지 않는다`() = runTest {
        val old = fakeJwt(expSec = nowSec + 600)
        val tokens = InMemoryTokenStore(creds(old)).also { it.load() }
        val api = FakeIdentityApi()
        api.refreshHandler = {
            // 서버 응답 전에 사용자가 다시 페어링 → 다른 refresh 토큰
            tokens.save(creds("re-paired", refresh = "r-new"))
            FakeIdentityApi.ok("rotated", "r2")
        }
        val refresher = TokenRefresher(api, tokens, clock = { nowMs })
        val outcome = refresher.refreshAfterRejection(old, RefreshTrigger.HTTP_401)
        assertTrue(outcome is RefreshOutcome.Failed)
        assertEquals(creds("re-paired", refresh = "r-new"), (tokens.state.value as TokenStoreState.Present).credentials)
    }

    // ---------------------------------------------------------------- OkHttp Authenticator (REST 401 → 1회 갱신·재시도)

    private fun response(path: String, bearer: String?, prior: Response? = null): Response {
        val request = Request.Builder().url("http://10.0.2.2:8080$path").apply { if (bearer != null) header("Authorization", "Bearer $bearer") }.build()
        return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(401).message("Unauthorized").priorResponse(prior).build()
    }

    @Test
    fun `Authenticator 는 401 을 한 번 갱신해 새 Bearer 로 재시도하고 제외 경로·재시도 응답·무토큰은 건드리지 않는다`() {
        val old = fakeJwt(expSec = nowSec + 600, jti = "old")
        val new = fakeJwt(expSec = nowSec + 24 * 3600, jti = "new")
        val tokens = InMemoryTokenStore(creds(old))
        runBlocking { tokens.load() }
        val api = FakeIdentityApi().apply { refreshHandler = { FakeIdentityApi.ok(new, "r2") } }
        val refresher = TokenRefresher(api, tokens, clock = { nowMs })
        val authenticator = VehicleTokenAuthenticator { rejected -> runBlocking { refresher.refreshAfterRejection(rejected, RefreshTrigger.HTTP_401) }.accessTokenOrNull }

        val retried = authenticator.authenticate(null, response("/v1/groups/grp_1", old))
        assertEquals("Bearer $new", retried?.header("Authorization"))
        assertEquals("/v1/groups/grp_1", retried?.url?.encodedPath)
        assertEquals(1, api.refreshCalls.size)

        // 이미 재시도한 응답(priorResponse 있음) → 포기
        assertNull(authenticator.authenticate(null, response("/v1/groups/grp_1", new, prior = response("/v1/groups/grp_1", old))))
        // 인증·페어링 경로는 제외
        assertNull(authenticator.authenticate(null, response("/v1/auth/refresh", old)))
        assertNull(authenticator.authenticate(null, response("/v1/device/token", old)))
        // 우리가 토큰을 붙이지 않은 요청
        assertNull(authenticator.authenticate(null, response("/v1/groups/grp_1", null)))
        // 옛 토큰으로 나간 다른 요청의 401 → 서버를 다시 부르지 않고 최신 토큰으로 재시도
        val late = authenticator.authenticate(null, response("/v1/groups/grp_1/leave", old))
        assertEquals("Bearer $new", late?.header("Authorization"))
        assertEquals(1, api.refreshCalls.size)
    }

    @Test
    fun `Authenticator 는 갱신이 거부되면 재시도하지 않는다`() {
        val old = fakeJwt(expSec = nowSec + 600)
        val tokens = InMemoryTokenStore(creds(old))
        runBlocking { tokens.load() }
        val api = FakeIdentityApi().apply { refreshHandler = { FakeIdentityApi.unauthorized() } }
        val refresher = TokenRefresher(api, tokens, clock = { nowMs })
        val authenticator = VehicleTokenAuthenticator { rejected -> runBlocking { refresher.refreshAfterRejection(rejected, RefreshTrigger.HTTP_401) }.accessTokenOrNull }
        assertNull(authenticator.authenticate(null, response("/v1/groups/grp_1", old)))
        assertEquals(listOf(TokenClearReason.REFRESH_REJECTED), tokens.clearReasons)
    }
}
