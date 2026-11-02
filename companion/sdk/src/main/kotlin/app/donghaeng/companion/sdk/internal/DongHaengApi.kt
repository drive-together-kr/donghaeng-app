package app.donghaeng.companion.sdk.internal

import app.donghaeng.companion.sdk.GroupJoinResult
import app.donghaeng.companion.sdk.GroupSnapshot
import app.donghaeng.companion.sdk.MyVehicle
import app.donghaeng.companion.sdk.PairResult
import kotlinx.coroutines.flow.Flow

/**
 * 서버 전송 계층. [DongHaeng] 은 QR 해석·초기화 검사만 하고 실제 호출은 이 인터페이스에 맡긴다.
 * 구현: [HttpDongHaengApi](REST — DEV 포함 모든 제공자가 사용) / [FakeDongHaengApi](메모리 — 단위 테스트 전용, internal 훅으로만 끼운다).
 */
internal interface DongHaengApi {

    /** `GET /me/vehicles` */
    suspend fun listMyVehicles(): List<MyVehicle>

    /**
     * `POST /groups/join` — [inviteToken] 은 QR 경로 `/j/{token}` 에서 꺼낸 값.
     * 그 차가 이미 이 그룹에 있으면(409 `ALREADY_MEMBER`) 오류 대신 지금 그룹을 돌려준다(멱등 — 응답 유실·앱 재시작 뒤 재스캔).
     */
    suspend fun joinGroup(inviteToken: String, vehicleId: String?): GroupJoinResult

    /** `POST /device/approve` — [userCode] 는 QR 쿼리 `c` 값 */
    suspend fun approvePairing(userCode: String, nickname: String): PairResult

    /**
     * `POST /groups/{groupId}/messages` + 헤더 `Idempotency-Key`([idempotencyKey], 같은 키 재전송은 서버가 한 번만 처리).
     * [fromVehicleId] 가 null 이면 이 프로세스에서 그 그룹에 마지막으로 합류할 때 고른 차, 차를 생략하고 합류했거나 기억이 없으면 서버 기본(그룹의 내 첫 차).
     */
    suspend fun sendPassengerMessage(groupId: String, text: String, fromVehicleId: String?, idempotencyKey: String)

    /** 그룹 스냅샷 스트림 (REST 폴링 또는 푸시 — 구현마다 다름). 그룹이 종료되면 마지막 스냅샷 뒤 정상 종료한다 */
    fun observeGroup(groupId: String): Flow<GroupSnapshot>
}
