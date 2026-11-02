package app.donghaeng.car.adapter.vehicle

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A047: VIN·CRN 조회의 응답 시간 초과는 '차량 ID 없음'으로 확정하지 않는다(다음 호출에서 다시 읽는다) */
class VehicleIdReaderTest {

    private suspend fun timeout(): Throwable = runCatching { withTimeout(1) { kotlinx.coroutines.delay(10) } }.exceptionOrNull()!!
    private val isTimeout: (Throwable) -> Boolean = { it is TimeoutCancellationException }

    @Test
    fun `a VIN timeout is not cached and does not fall through to the CRN`() = runTest {
        val t = timeout()
        var crnCalls = 0
        val outcome = VehicleIdReader.read(readVin = { Result.failure(t) }, readCrn = { crnCalls++; Result.success("CRN123456789") }, isTimeout = isTimeout)
        assertTrue(outcome is VehicleIdReader.Outcome.Retry)
        assertEquals("VIN 이 늦은 차를 CRN 해시로 등록하지 않는다", 0, crnCalls)
    }

    @Test
    fun `a usable VIN resolves, placeholder VIN falls back to CRN, both unusable resolves to none, a CRN timeout retries`() = runTest {
        val vin = VehicleIdReader.read({ Result.success("KMHXX00XXXX000001") }, { error("unused") }, isTimeout)
        assertEquals("KMHXX00XXXX000001", (vin as VehicleIdReader.Outcome.Resolved).id)
        val crn = VehicleIdReader.read({ Result.success("\"invalid\"") }, { Result.success("CRN123456789") }, isTimeout)
        assertEquals("CRN123456789", (crn as VehicleIdReader.Outcome.Resolved).id)
        val none = VehicleIdReader.read({ Result.failure(IllegalStateException("onFailed")) }, { Result.success("invalid") }, isTimeout)
        assertEquals("onFailed·자리표시자는 '없음'으로 확정(폴백 ID)", null, (none as VehicleIdReader.Outcome.Resolved).id)
        val t = timeout()
        assertTrue(VehicleIdReader.read({ Result.success("invalid") }, { Result.failure(t) }, isTimeout) is VehicleIdReader.Outcome.Retry)
    }
}
