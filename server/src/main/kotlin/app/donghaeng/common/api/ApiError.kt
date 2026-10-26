package app.donghaeng.common.api

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.ErrorResponse
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.NoHandlerFoundException
import org.springframework.web.servlet.resource.NoResourceFoundException
import java.util.concurrent.ThreadLocalRandom

/**
 * docs/03 오류 형식: `{ "error": { "code", "message", "traceId" } }`
 */
data class ApiError(val error: Body) {
    data class Body(
        val code: String,
        val message: String,
        val traceId: String,
    )

    companion object {
        fun of(code: String, message: String, traceId: String = newTraceId()): ApiError =
            ApiError(Body(code, message, traceId))

        /** 짧은 랜덤 16진수 (12자). 로그와 응답을 잇는 용도. */
        fun newTraceId(): String {
            val bytes = ByteArray(6)
            ThreadLocalRandom.current().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}

/**
 * 모듈별 도메인 오류의 기반 클래스.
 * 예: `throw ApiException(HttpStatus.GONE, "INVITE_EXPIRED", "초대 코드가 만료됐어요.")`
 */
open class ApiException(
    val status: HttpStatus,
    val code: String,
    override val message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    @ExceptionHandler(ApiException::class)
    fun handleApi(e: ApiException): ResponseEntity<ApiError> =
        ResponseEntity.status(e.status).body(ApiError.of(e.code, e.message))

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(e: MethodArgumentNotValidException): ResponseEntity<ApiError> {
        val detail = e.bindingResult.fieldErrors.joinToString(", ") { "${it.field}: ${it.defaultMessage}" }
        val message = if (detail.isEmpty()) "요청 값이 올바르지 않아요." else "요청 값이 올바르지 않아요. ($detail)"
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiError.of("VALIDATION_FAILED", message))
    }

    @ExceptionHandler(NoResourceFoundException::class, NoHandlerFoundException::class)
    fun handleNotFound(e: Exception): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.of("NOT_FOUND", "요청한 리소스를 찾을 수 없어요."))

    @ExceptionHandler(AccessDeniedException::class)
    fun handleAccessDenied(e: AccessDeniedException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiError.of("FORBIDDEN", "권한이 없어요."))

    @ExceptionHandler(Exception::class)
    fun handleOther(e: Exception): ResponseEntity<ApiError> {
        // Spring MVC 표준 오류(405, 415, 400 등)는 상태 코드를 그대로 살린다.
        if (e is ErrorResponse) {
            val status = e.statusCode
            if (status.is4xxClientError) {
                val code = when (status.value()) {
                    404 -> "NOT_FOUND"
                    405 -> "METHOD_NOT_ALLOWED"
                    415 -> "UNSUPPORTED_MEDIA_TYPE"
                    else -> "BAD_REQUEST"
                }
                return ResponseEntity.status(status).body(ApiError.of(code, "요청을 처리할 수 없어요."))
            }
        }
        val error = ApiError.of("INTERNAL_ERROR", "서버 오류가 발생했어요.")
        log.error("Unhandled exception traceId={}", error.error.traceId, e)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error)
    }
}
