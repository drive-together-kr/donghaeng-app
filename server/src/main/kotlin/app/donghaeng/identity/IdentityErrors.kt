package app.donghaeng.identity

import app.donghaeng.common.api.ApiException
import app.donghaeng.protocol.rest.ErrorCodes
import org.springframework.http.HttpStatus

/** identity 모듈의 도메인 오류 (docs/03 §1 상태 코드 표). 메시지는 사용자에게 그대로 보여 줄 수 있는 한 문장. */
internal object IdentityErrors {

    fun validation(detail: String) =
        ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_FAILED, "요청 값이 올바르지 않아요. ($detail)")

    fun invalidToken() =
        ApiException(HttpStatus.UNAUTHORIZED, ErrorCodes.INVALID_TOKEN, "토큰이 유효하지 않아요. 다시 로그인해 주세요.")

    fun devLoginKeyRejected() =
        ApiException(HttpStatus.FORBIDDEN, ErrorCodes.FORBIDDEN, "이 서버의 dev 로그인에는 접속 키가 필요해요. 이 서버용 접속 키로 만든 시연 앱으로 로그인해 주세요.")

    fun providerNotSupported(provider: String) =
        ApiException(HttpStatus.NOT_IMPLEMENTED, ErrorCodes.PROVIDER_NOT_SUPPORTED, "아직 지원하지 않는 계정 제공자예요. ($provider)")

    fun codeNotFound() =
        ApiException(HttpStatus.NOT_FOUND, ErrorCodes.CODE_NOT_FOUND, "연결 코드를 찾을 수 없어요. 차량 화면의 코드를 다시 확인해 주세요.")

    fun codeExpired() =
        ApiException(HttpStatus.GONE, ErrorCodes.CODE_EXPIRED, "연결 코드가 만료됐어요. 차량에서 새 코드를 받아 주세요.")

    fun alreadyApproved() =
        ApiException(HttpStatus.CONFLICT, ErrorCodes.ALREADY_APPROVED, "이미 승인된 코드예요.")

    fun authorizationPending() =
        ApiException(HttpStatus.PRECONDITION_REQUIRED, ErrorCodes.AUTHORIZATION_PENDING, "아직 승인 전이에요. 폰에서 QR 을 스캔해 주세요.")

    fun slowDown() =
        ApiException(HttpStatus.TOO_MANY_REQUESTS, ErrorCodes.SLOW_DOWN, "폴링 간격을 지켜 주세요.")

    fun vehicleNotFound() =
        ApiException(HttpStatus.NOT_FOUND, ErrorCodes.NOT_FOUND, "내 차 목록에 없는 차량이에요.")
}
