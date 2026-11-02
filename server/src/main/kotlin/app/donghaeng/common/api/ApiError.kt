package app.donghaeng.common.api

import app.donghaeng.protocol.rest.ErrorCodes
import jakarta.validation.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.ErrorResponse
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.multipart.MaxUploadSizeExceededException
import org.springframework.web.multipart.MultipartException
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

/**
 * 모든 오류 응답 = docs/03 오류 형식 JSON. **Content-Type 을 `application/json` 으로 미리 정해** `Accept` 협상을 건너뛴다 —
 * 협상에 맡기면 JSON 을 받지 않는 요청(무전 다운로드의 `Accept: audio/mp4`·오디오 와일드카드 등)에서 `ApiError` 를 쓰지 못해(`HttpMediaTypeNotAcceptableException`)
 * 원래 오류(404·403…)가 `/error` 로 넘어가 본문 없는 500 이 됐다.
 */
@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    private fun json(status: HttpStatusCode): ResponseEntity.BodyBuilder =
        ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)

    @ExceptionHandler(ApiException::class)
    fun handleApi(e: ApiException): ResponseEntity<ApiError> {
        val builder = json(e.status)
        // 빈도 제한(429 RATE_LIMITED 등)은 다시 보낼 때까지의 초를 알려 준다 (docs/03 §1·§2.3)
        if (e is app.donghaeng.common.web.RateLimitedException) builder.header(HttpHeaders.RETRY_AFTER, e.retryAfterSeconds.toString())
        return builder.body(ApiError.of(e.code, e.message))
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(e: MethodArgumentNotValidException): ResponseEntity<ApiError> {
        val detail = e.bindingResult.fieldErrors.joinToString(", ") { "${it.field}: ${it.defaultMessage}" }
        return json(HttpStatus.BAD_REQUEST).body(ApiError.of("VALIDATION_FAILED", validationMessage(detail)))
    }

    /** `@Validated` 컨트롤러/서비스의 `@RequestParam`·`@PathVariable` 제약 위반 (jakarta ConstraintViolation) */
    @ExceptionHandler(ConstraintViolationException::class)
    fun handleConstraintViolation(e: ConstraintViolationException): ResponseEntity<ApiError> {
        val detail = e.constraintViolations.joinToString(", ") { "${it.propertyPath}: ${it.message}" }
        return json(HttpStatus.BAD_REQUEST).body(ApiError.of("VALIDATION_FAILED", validationMessage(detail)))
    }

    /** Spring 6.1+ 메서드 파라미터 검증 실패 (컨트롤러 파라미터에 직접 붙인 제약) */
    @ExceptionHandler(HandlerMethodValidationException::class)
    fun handleMethodValidation(e: HandlerMethodValidationException): ResponseEntity<ApiError> {
        val detail = e.allErrors.joinToString(", ") { it.defaultMessage ?: it.toString() }
        return json(HttpStatus.BAD_REQUEST).body(ApiError.of("VALIDATION_FAILED", validationMessage(detail)))
    }

    /** 본문 JSON 이 깨졌거나 필수 필드가 빠짐 (kotlinx MissingFieldException 포함). 본문 원문은 로그·응답에 싣지 않는다. */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleNotReadable(e: HttpMessageNotReadableException): ResponseEntity<ApiError> =
        json(HttpStatus.BAD_REQUEST).body(ApiError.of("VALIDATION_FAILED", "요청 본문을 읽을 수 없어요."))

    /**
     * multipart 본문을 받는 API 는 무전 업로드(`POST /groups/{id}/voice`, docs/03 §2.6)뿐이다. Spring 상한
     * (`spring.servlet.multipart.max-file-size` 512KB · `max-request-size`)을 넘으면 컨트롤러에 닿기 전에 여기로 온다 → 계약의 413 `VOICE_TOO_LARGE`.
     * (`MaxUploadSizeExceededException` 은 `ErrorResponse` 라 아래 [handleOther] 로 가면 코드가 `BAD_REQUEST` 가 된다)
     */
    @ExceptionHandler(MaxUploadSizeExceededException::class)
    fun handleUploadTooLarge(e: MaxUploadSizeExceededException): ResponseEntity<ApiError> =
        json(HttpStatus.PAYLOAD_TOO_LARGE).body(ApiError.of(ErrorCodes.VOICE_TOO_LARGE, "무전이 너무 커요. (512 KB 까지)"))

    /** 깨진 multipart(경계 없음·잘린 본문 등) — 그대로 두면 500. 무전 업로드의 400 `INVALID_VOICE` 로 */
    @ExceptionHandler(MultipartException::class)
    fun handleMultipart(e: MultipartException): ResponseEntity<ApiError> =
        json(HttpStatus.BAD_REQUEST).body(ApiError.of(ErrorCodes.INVALID_VOICE, "무전 파일을 읽을 수 없어요."))

    private fun validationMessage(detail: String): String =
        if (detail.isEmpty()) "요청 값이 올바르지 않아요." else "요청 값이 올바르지 않아요. ($detail)"

    @ExceptionHandler(NoResourceFoundException::class, NoHandlerFoundException::class)
    fun handleNotFound(e: Exception): ResponseEntity<ApiError> =
        json(HttpStatus.NOT_FOUND).body(ApiError.of("NOT_FOUND", "요청한 리소스를 찾을 수 없어요."))

    @ExceptionHandler(AccessDeniedException::class)
    fun handleAccessDenied(e: AccessDeniedException): ResponseEntity<ApiError> =
        json(HttpStatus.FORBIDDEN).body(ApiError.of("FORBIDDEN", "권한이 없어요."))

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
            return ResponseEntity.status(status).headers(e.headers).contentType(MediaType.APPLICATION_JSON).body(error)
        }
        val error = ApiError.of("INTERNAL_ERROR", "서버 오류가 발생했어요.")
        log.error("Unhandled exception traceId={}", error.error.traceId, e)
        return json(HttpStatus.INTERNAL_SERVER_ERROR).body(error)
    }
}
