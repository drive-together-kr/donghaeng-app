package app.donghaeng.group

/**
 * 그룹 API 호출자 — 사용자 토큰(`typ=user`, companion 모듈) 또는 차량 토큰(`typ=vehicle`, 차량 앱).
 * 같은 엔드포인트를 둘 다 부를 수 있는 경우(`GET /groups/{id}`, `leave`, `end`, `leader`, `invites`)가 있어 서비스가 이 타입으로 분기한다.
 */
sealed interface Caller {
    data class User(val userId: String) : Caller

    /** [ownerId] 는 토큰의 `owner` 클레임(발급 시점 소유자). 현재 소유자는 identity 에서 다시 확인한다 */
    data class Vehicle(val vehicleId: String, val ownerId: String?) : Caller
}
