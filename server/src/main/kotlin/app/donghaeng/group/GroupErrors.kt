package app.donghaeng.group

import app.donghaeng.common.api.ApiException
import app.donghaeng.protocol.rest.ErrorCodes
import org.springframework.http.HttpStatus

/** group 모듈의 도메인 오류 (docs/03 §2 상태 코드 표). 메시지는 사용자에게 그대로 보여 줄 수 있는 한 문장. */
internal object GroupErrors {

    fun validation(detail: String) =
        ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_FAILED, "요청 값이 올바르지 않아요. ($detail)")

    /** 초대 토큰이 없음·형식 오류·만료·사용 횟수 초과·그룹 종료 — 열거 방지를 위해 구분하지 않는다 */
    fun inviteExpired() =
        ApiException(HttpStatus.GONE, ErrorCodes.INVITE_EXPIRED, "초대 코드가 만료됐거나 올바르지 않아요. 선두차에서 QR 을 다시 받아 주세요.")

    fun groupFull(maxMembers: Int) =
        ApiException(HttpStatus.CONFLICT, ErrorCodes.GROUP_FULL, "그룹 정원(${maxMembers}대)이 찼어요.")

    fun noPairedVehicle() =
        ApiException(HttpStatus.NOT_FOUND, ErrorCodes.NO_PAIRED_VEHICLE, "계정에 연결된 차가 없어요. 차량을 먼저 등록해 주세요.")

    fun notMyVehicle() =
        ApiException(HttpStatus.NOT_FOUND, ErrorCodes.NO_PAIRED_VEHICLE, "내 차 목록에 없는 차량이에요.")

    fun notMyMemberVehicle() =
        ApiException(HttpStatus.NOT_FOUND, ErrorCodes.NO_PAIRED_VEHICLE, "이 그룹에 들어가 있는 내 차가 아니에요.")

    fun alreadyMember() =
        ApiException(HttpStatus.CONFLICT, ErrorCodes.ALREADY_MEMBER, "이미 이 그룹에 들어가 있는 차량이에요.")

    fun groupEnded() =
        ApiException(HttpStatus.CONFLICT, ErrorCodes.GROUP_ENDED, "이미 종료된 그룹이에요.")

    fun invalidMessage(detail: String) =
        ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.INVALID_MESSAGE, "메시지를 보낼 수 없어요. ($detail)")

    /** 그룹이 없거나 호출자가 볼 수 없는 그룹 — 존재 여부를 숨기기 위해 둘 다 404 */
    fun groupNotFound() =
        ApiException(HttpStatus.NOT_FOUND, ErrorCodes.NOT_FOUND, "그룹을 찾을 수 없어요.")

    fun memberNotFound() =
        ApiException(HttpStatus.NOT_FOUND, ErrorCodes.NOT_FOUND, "그룹에 없는 차량이에요.")

    fun leaderOnly() =
        ApiException(HttpStatus.FORBIDDEN, ErrorCodes.FORBIDDEN, "방장만 할 수 있어요.")

    fun vehicleRevoked() =
        ApiException(HttpStatus.FORBIDDEN, ErrorCodes.FORBIDDEN, "연결이 해제된 차량이에요. 폰에서 차량을 다시 등록해 주세요.")

    fun wrongPrincipal(detail: String) =
        ApiException(HttpStatus.FORBIDDEN, ErrorCodes.FORBIDDEN, detail)
}
