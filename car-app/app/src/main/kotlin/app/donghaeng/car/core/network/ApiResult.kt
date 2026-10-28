package app.donghaeng.car.core.network

import app.donghaeng.protocol.DhJson
import app.donghaeng.protocol.rest.ErrorBody
import app.donghaeng.protocol.rest.ErrorEnvelope
import kotlinx.coroutines.CancellationException
import retrofit2.Response
import java.io.IOException

/**
 * REST 호출 결과. 예외 대신 값으로 돌려줘 ViewModel 이 `when` 으로 분기한다 (docs/03 오류 형식 `{ "error": { code, message, traceId } }`).
 *
 * 페어링(§1.2)에서 의미가 있는 HTTP 상태는 전용 타입으로 구분한다:
 * 428 [HttpFailure.Pending] / 429 [HttpFailure.SlowDown] / 410 [HttpFailure.Expired] / 404 [HttpFailure.NotFound].
 * 401 은 [HttpFailure.Unauthorized] (T33) — 차량 토큰 만료·무효. 인증 경로의 REST 는 OkHttp Authenticator 가 한 번 갱신한 뒤 재시도하므로
 * 이 값이 ViewModel 까지 왔다면 갱신도 실패한 것이다(→ '폰에서 다시 연결').
 */
sealed interface ApiResult<out T> {

    data class Success<T>(val value: T) : ApiResult<T>

    sealed interface Failure : ApiResult<Nothing> {
        /** 로그·오류 화면용 한 줄 요약. 토큰·코드 원문은 들어가지 않는다(서버 message 는 사람이 읽는 문장). */
        val summary: String
    }

    /** HTTP 4xx/5xx. [error] 는 본문이 docs/03 오류 봉투일 때만 채워진다(Spring 기본 404 등은 null). */
    sealed class HttpFailure : Failure {
        abstract val status: Int
        abstract val error: ErrorBody?

        val code: String? get() = error?.code
        override val summary: String get() = "HTTP $status ${code ?: ""}".trim()

        /** 428 AUTHORIZATION_PENDING — 아직 승인 전, 계속 폴링 */
        data class Pending(override val status: Int, override val error: ErrorBody?) : HttpFailure()

        /** 429 SLOW_DOWN — interval 보다 빨리 폴링함, 간격을 늘린다 */
        data class SlowDown(override val status: Int, override val error: ErrorBody?) : HttpFailure()

        /** 410 CODE_EXPIRED — 새 코드를 받는다 */
        data class Expired(override val status: Int, override val error: ErrorBody?) : HttpFailure()

        /** 404 CODE_NOT_FOUND(또는 엔드포인트 없음) */
        data class NotFound(override val status: Int, override val error: ErrorBody?) : HttpFailure()

        /** 401 UNAUTHORIZED / INVALID_TOKEN — 토큰 없음·만료·무효(refresh 거부 포함) */
        data class Unauthorized(override val status: Int, override val error: ErrorBody?) : HttpFailure()

        /** 그 밖의 4xx/5xx */
        data class Other(override val status: Int, override val error: ErrorBody?) : HttpFailure()

        companion object {
            fun of(status: Int, error: ErrorBody?): HttpFailure = when (status) {
                428 -> Pending(status, error)
                429 -> SlowDown(status, error)
                410 -> Expired(status, error)
                404 -> NotFound(status, error)
                401 -> Unauthorized(status, error)
                else -> Other(status, error)
            }
        }
    }

    /** 연결 실패·타임아웃 등 전송 계층 오류 — 재시도 가능 */
    data class NetworkFailure(val cause: IOException) : Failure {
        override val summary: String get() = "network ${cause.javaClass.simpleName}"
    }

    /** 역직렬화 실패·빈 본문 등 예상 밖 오류 */
    data class UnexpectedFailure(val cause: Throwable) : Failure {
        override val summary: String get() = "unexpected ${cause.javaClass.simpleName}"
    }
}

/**
 * Retrofit `Response<T>` 를 [ApiResult] 로 바꾼다. 네트워크 예외는 [ApiResult.NetworkFailure], 취소는 그대로 전파.
 * 오류 본문은 [ErrorEnvelope] 로 파싱을 시도하고 실패하면(JSON 아님) error=null 로 상태 코드만 남긴다.
 */
suspend fun <T> safeApiCall(block: suspend () -> Response<T>): ApiResult<T> = try {
    val response = block()
    if (response.isSuccessful) {
        val body = response.body()
        if (body != null) ApiResult.Success(body)
        else ApiResult.UnexpectedFailure(IllegalStateException("empty body (HTTP ${response.code()})"))
    } else {
        ApiResult.HttpFailure.of(response.code(), parseErrorBody(response.errorBody()?.string()))
    }
} catch (e: IOException) {
    ApiResult.NetworkFailure(e)
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    ApiResult.UnexpectedFailure(e)
}

/** docs/03 오류 봉투 파싱. 본문이 비었거나 다른 모양이면 null. */
fun parseErrorBody(raw: String?): ErrorBody? {
    if (raw.isNullOrBlank()) return null
    return runCatching { DhJson.json.decodeFromString(ErrorEnvelope.serializer(), raw).error }.getOrNull()
}
