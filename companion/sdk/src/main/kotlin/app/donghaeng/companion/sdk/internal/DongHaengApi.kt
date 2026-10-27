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

    /** `POST /groups/join` — [inviteToken] 은 QR 경로 `/j/{token}` 에서 꺼낸 값 */
    suspend fun joinGroup(inviteToken: String, vehicleId: String?): GroupJoinResult

    /** `POST /device/approve` — [userCode] 는 QR 쿼리 `c` 값 */
    suspend fun approvePairing(userCode: String, nickname: String): PairResult

    /** `POST /groups/{groupId}/messages` */
    suspend fun sendPassengerMessage(groupId: String, text: String)

    /** 그룹 스냅샷 스트림 (REST 폴링 또는 푸시 — 구현마다 다름) */
    fun observeGroup(groupId: String): Flow<GroupSnapshot>
}
