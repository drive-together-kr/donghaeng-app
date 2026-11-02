package app.donghaeng.identity

/**
 * 차량이 계정에서 떨어져 나갔을 때 identity 가 같은 트랜잭션 안에서 발행하는 Spring 애플리케이션 이벤트.
 * group 모듈이 받아 그 차량을 활성 그룹에서 뺀다(모듈 간 직접 호출 없이 — docs/06 §2).
 *
 * @param reason REVOKED(소유자가 페어링 해제 — 폰 또는 차량 자신) | TRANSFERRED(다른 계정이 같은 차를 다시 페어링해 소유권 이전)
 * @param previousNickname 떨어져 나가기 **전** 별명(C104) — 소유권 이전이면 같은 트랜잭션에서 차량 행이 새 소유자의 별명으로 이미 바뀌어 있으므로,
 *        옛 그룹의 `MEMBER_LEFT` 문구는 이 값을 쓴다(새 소유자가 붙인 별명이 옛 그룹에 나가지 않게). null 이면 차량 행의 현재 별명
 */
data class VehicleUnpaired(
    val vehicleId: String,
    val previousOwnerId: String,
    val reason: Reason,
    val previousNickname: String? = null,
) {
    enum class Reason { REVOKED, TRANSFERRED }
}
