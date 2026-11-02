package app.donghaeng.car.core.network

import app.donghaeng.protocol.GroupJoined
import app.donghaeng.protocol.rest.ApiPaths
import retrofit2.Response
import retrofit2.http.DELETE
import retrofit2.http.GET

/**
 * 차량 자신에 대한 REST (차량 JWT 전용 — docs/03 배치 간 계약 1·2, 2026-11-02).
 * 경로 상수는 protocol 의 [ApiPaths] 에 아직 없어(서버 배치가 추가) 여기서 [ApiPaths.BASE] 로 만든다.
 */
interface VehicleSelfApi {

    /**
     * 계약 1 — 이 차량의 ACTIVE 그룹. 200 + [GroupJoined](cmd `GROUP_JOINED` 본문과 같은 형태), 없으면 204(본문 없음 → Retrofit body null).
     * 이 경로를 모르는 옛 서버는 404/405 — 호출 쪽이 '지원 안 함'으로 보고 `GET /groups/{id}` 로 대신 확인한다.
     */
    @GET(MY_GROUP)
    suspend fun myGroup(): Response<GroupJoined>

    /**
     * 계약 2 — 이 차량의 페어링을 스스로 해제(활성 그룹에서 빠지고 refresh·차량 토큰 폐기). 204.
     * 이미 해제됐으면 204 또는 401 — 둘 다 성공으로 본다. 401 에 토큰 갱신을 시도하지 않는다([VehicleTokenAuthenticator] 제외 경로).
     */
    @DELETE(ME)
    suspend fun unpairSelf(): Response<Unit>

    companion object {
        const val ME = "${ApiPaths.BASE}/vehicles/me"
        const val MY_GROUP = "$ME/group"
    }
}
