package app.donghaeng.voice

import app.donghaeng.common.api.ApiException
import app.donghaeng.protocol.rest.ErrorCodes
import org.springframework.http.HttpStatus

/**
 * voice 모듈의 도메인 오류 (docs/03 §2.6). 멤버·종료·해제 차량 오류(404/409/403)는 group 의 `GroupAccessApi` 가 던진다.
 * Spring multipart 상한을 넘은 요청(컨트롤러에 닿기 전)의 413 은 `common.api.GlobalExceptionHandler` 가 같은 코드로 만든다.
 */
internal object VoiceErrors {

    fun tooLarge() =
        ApiException(HttpStatus.PAYLOAD_TOO_LARGE, ErrorCodes.VOICE_TOO_LARGE, "무전이 너무 커요. (${VoiceRules.MAX_BYTES / 1024} KB 까지)")

    fun invalid(detail: String) =
        ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.INVALID_VOICE, "무전을 보낼 수 없어요. ($detail)")

    fun unsupportedType() =
        ApiException(
            HttpStatus.UNSUPPORTED_MEDIA_TYPE,
            ErrorCodes.UNSUPPORTED_VOICE_TYPE,
            "지원하지 않는 무전 형식이에요. (${VoiceRules.ALLOWED_MIME.joinToString(" · ")})",
        )

    fun rateLimited() =
        ApiException(HttpStatus.TOO_MANY_REQUESTS, ErrorCodes.VOICE_RATE_LIMITED, "무전은 ${VoiceRules.RATE_WINDOW.seconds}초에 한 번 보낼 수 있어요.")

    /** 없음·형식 오류·만료·그룹 종료로 삭제 — 구분하지 않는다 */
    fun notFound() =
        ApiException(HttpStatus.NOT_FOUND, ErrorCodes.NOT_FOUND, "무전을 찾을 수 없어요. 보관 시간(24시간)이 지났거나 그룹이 끝났어요.")
}
