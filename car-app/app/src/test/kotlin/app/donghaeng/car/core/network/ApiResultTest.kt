package app.donghaeng.car.core.network

import app.donghaeng.protocol.rest.DeviceTokenResponse
import app.donghaeng.protocol.rest.ErrorCodes
import app.donghaeng.protocol.rest.MqttInfo
import app.donghaeng.protocol.rest.VehicleSummary
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.io.IOException

/** HTTP 상태·오류 봉투 → [ApiResult] 매핑 (docs/03 §1.2 페어링 상태 코드). */
class ApiResultTest {

    private val json = "application/json".toMediaType()

    private fun envelope(code: String) =
        """{"error":{"code":"$code","message":"사람이 읽는 문장","traceId":"t-1"}}""".toResponseBody(json)

    private fun <T> error(status: Int, body: okhttp3.ResponseBody = envelope("X")): Response<T> = Response.error(status, body)

    @Test
    fun `428 은 Pending 이고 오류 봉투 code 를 파싱한다`() = runTest {
        val r = safeApiCall { error<DeviceTokenResponse>(428, envelope(ErrorCodes.AUTHORIZATION_PENDING)) }
        assertTrue(r is ApiResult.HttpFailure.Pending)
        assertEquals(ErrorCodes.AUTHORIZATION_PENDING, (r as ApiResult.HttpFailure).code)
        assertEquals("t-1", r.error?.traceId)
        assertEquals("HTTP 428 AUTHORIZATION_PENDING", r.summary)
    }

    @Test
    fun `429 410 404 는 전용 타입, 나머지는 Other`() = runTest {
        assertTrue(safeApiCall { error<Unit>(429, envelope(ErrorCodes.SLOW_DOWN)) } is ApiResult.HttpFailure.SlowDown)
        assertTrue(safeApiCall { error<Unit>(410, envelope(ErrorCodes.CODE_EXPIRED)) } is ApiResult.HttpFailure.Expired)
        assertTrue(safeApiCall { error<Unit>(404, envelope(ErrorCodes.CODE_NOT_FOUND)) } is ApiResult.HttpFailure.NotFound)
        val other = safeApiCall { error<Unit>(500, envelope(ErrorCodes.INTERNAL_ERROR)) }
        assertTrue(other is ApiResult.HttpFailure.Other)
        assertEquals(500, (other as ApiResult.HttpFailure).status)
    }

    @Test
    fun `401 은 Unauthorized 이고 INVALID_TOKEN 코드를 파싱한다 (T33)`() = runTest {
        val r = safeApiCall { error<Unit>(401, envelope(ErrorCodes.INVALID_TOKEN)) }
        assertTrue(r is ApiResult.HttpFailure.Unauthorized)
        assertEquals(401, (r as ApiResult.HttpFailure).status)
        assertEquals(ErrorCodes.INVALID_TOKEN, r.code)
        assertEquals("HTTP 401 INVALID_TOKEN", r.summary)
        // 본문 없는 401(Spring 기본)도 Unauthorized
        val bare = safeApiCall { error<Unit>(401, "".toResponseBody(json)) }
        assertTrue(bare is ApiResult.HttpFailure.Unauthorized)
        // 403 은 그대로 Other (폐기된 차량 토큰 — 갱신 대상 아님)
        assertTrue(safeApiCall { error<Unit>(403, envelope(ErrorCodes.FORBIDDEN)) } is ApiResult.HttpFailure.Other)
    }

    @Test
    fun `오류 본문이 봉투 모양이 아니면 error 는 null 이고 상태만 남는다`() = runTest {
        val r = safeApiCall { error<Unit>(404, "<html>Not Found</html>".toResponseBody("text/html".toMediaType())) }
        assertTrue(r is ApiResult.HttpFailure.NotFound)
        assertNull((r as ApiResult.HttpFailure).error)
        assertEquals("HTTP 404", r.summary)
        assertNull(parseErrorBody(""))
        assertNull(parseErrorBody(null))
    }

    @Test
    fun `IOException 은 NetworkFailure`() = runTest {
        val r = safeApiCall<Unit> { throw IOException("connect refused") }
        assertTrue(r is ApiResult.NetworkFailure)
        assertEquals("network IOException", (r as ApiResult.Failure).summary)
    }

    @Test
    fun `200 은 Success 로 본문을 그대로 담는다`() = runTest {
        val body = DeviceTokenResponse("tok", "ref", VehicleSummary("veh_01", "별명", "usr_01"), MqttInfo("tcp://10.0.2.2:1883", "veh_01", "veh_01"))
        val r = safeApiCall { Response.success(body) }
        assertTrue(r is ApiResult.Success)
        assertEquals("veh_01", (r as ApiResult.Success).value.vehicle.vehicleId)
    }
}
