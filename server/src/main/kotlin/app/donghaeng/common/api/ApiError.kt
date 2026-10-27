package app.donghaeng.common.api

import jakarta.validation.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.ErrorResponse
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.HandlerMethodValidationException
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
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiError.of("VALIDATION_FAILED", validationMessage(detail)))
    }

    /** `@Validated` 컨트롤러/서비스의 `@RequestParam`·`@PathVariable` 제약 위반 (jakarta ConstraintViolation) */
    @ExceptionHandler(ConstraintViolationException::class)
    fun handleConstraintViolation(e: ConstraintViolationException): ResponseEntity<ApiError> {
        val detail = e.constraintViolations.joinToString(", ") { "${it.propertyPath}: ${it.message}" }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiError.of("VALIDATION_FAILED", validationMessage(detail)))
    }

    /** Spring 6.1+ 메서드 파라미터 검증 실패 (컨트롤러 파라미터에 직접 붙인 제약) */
    @ExceptionHandler(HandlerMethodValidationException::class)
    fun handleMethodValidation(e: HandlerMethodValidationException): ResponseEntity<ApiError> {
        val detail = e.allErrors.joinToString(", ") { it.defaultMessage ?: it.toString() }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiError.of("VALIDATION_FAILED", validationMessage(detail)))
    }

    private fun validationMessage(detail: String): String =
        if (detail.isEmpty()) "요청 값이 올바르지 않아요." else "요청 값이 올바르지 않아요. ($detail)"

    @ExceptionHandler(NoResourceFoundException::class, NoHandlerFoundException::class)
    fun handleNotFound(e: Exception): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.of("NOT_FOUND", "요청한 리소스를 찾을 수 없어요."))

    @ExceptionHandler(AccessDeniedException::class)
    fun handleAccessDenied(e: AccessDeniedException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiError.of("FORBIDDEN", "권한이 없어요."))

    @ExceptionHandler(Exception::class)
    fun handleOther(e: Exception): ResponseEntity<ApiError> {
        // Spring MVC 표준 오류(405, 406, 415, 503 등)는 상태 코드와 헤더(405 의 Allow 등)를 그대로 살린다.
        if (e is ErrorResponse) {
            val status = e.statusCode
            val code = when (status.value()) {
                404 -> "NOT_FOUND"
                405 -> "METHOD_NOT_ALLOWED"
                406 -> "NOT_ACCEPTABLE"
                415 -> "UNSUPPORTED_MEDIA_TYPE"
                503 -> "SERVICE_UNAVAILABLE"
                else -> if (status.is4xxClientError) "BAD_REQUEST" else "INTERNAL_ERROR"
            }
            val error = ApiError.of(code, "요청을 처리할 수 없어요.")
            if (status.is5xxServerError) log.error("ErrorResponse {} traceId={}", status, error.error.traceId, e)
            return ResponseEntity.status(status).headers(e.headers).body(error)
        }
        val error = ApiError.of("INTERNAL_ERROR", "서버 오류가 발생했어요.")
        log.error("Unhandled exception traceId={}", error.error.traceId, e)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error)
    }
}
