package app.donghaeng.car.core.network

import app.donghaeng.protocol.rest.ApiPaths
import app.donghaeng.protocol.rest.GroupCreateRequest
import app.donghaeng.protocol.rest.GroupCreateResponse
import app.donghaeng.protocol.rest.GroupDetailResponse
import app.donghaeng.protocol.rest.InviteDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * group 모듈 REST (docs/03 §2) — 차량 JWT 로 호출한다. `Authorization: Bearer` 는 [VehicleAuthInterceptor] 가 붙인다.
 * DTO 는 `protocol/` 의 것만(CLAUDE.md 규칙 7). 본문 없는 POST 도 `{}` 를 보낸다(서버는 본문 없는 POST 에 415 를 낼 수 있다).
 */
interface GroupApi {

    /** 차량 JWT → 201 [GroupCreateResponse] (초대 QR url 포함). 이미 다른 활성 그룹에 있었으면 거기서 자동 이탈 */
    @POST(ApiPaths.GROUPS)
    suspend fun createGroup(@Body body: GroupCreateRequest): Response<GroupCreateResponse>

    /** 방장만. 200 [InviteDto] — 이전 초대는 즉시 만료 */
    @POST("${ApiPaths.GROUPS}/{groupId}/invites")
    suspend fun reissueInvite(@Path("groupId") groupId: String, @Body body: Map<String, String> = emptyMap()): Response<InviteDto>

    /** 멤버 차량 → 200 [GroupDetailResponse] (members, recentEvents) */
    @GET("${ApiPaths.GROUPS}/{groupId}")
    suspend fun getGroup(@Path("groupId") groupId: String): Response<GroupDetailResponse>

    /** 차량 자신 나가기 → 204 */
    @POST("${ApiPaths.GROUPS}/{groupId}/leave")
    suspend fun leave(@Path("groupId") groupId: String, @Body body: Map<String, String> = emptyMap()): Response<Unit>

    /** 방장 종료 → 204 */
    @POST("${ApiPaths.GROUPS}/{groupId}/end")
    suspend fun end(@Path("groupId") groupId: String, @Body body: Map<String, String> = emptyMap()): Response<Unit>
}
