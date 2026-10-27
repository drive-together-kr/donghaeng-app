package app.donghaeng.identity

/**
 * 차량이 계정에서 떨어져 나갔을 때 identity 가 같은 트랜잭션 안에서 발행하는 Spring 애플리케이션 이벤트.
 * group 모듈이 받아 그 차량을 활성 그룹에서 뺀다(모듈 간 직접 호출 없이 — docs/06 §2).
 *
 * @param reason REVOKED(소유자가 페어링 해제) | TRANSFERRED(다른 계정이 같은 차를 다시 페어링해 소유권 이전)
 */
data class VehicleUnpaired(
    val vehicleId: String,
    val previousOwnerId: String,
    val reason: Reason,
) {
    enum class Reason { REVOKED, TRANSFERRED }
}
