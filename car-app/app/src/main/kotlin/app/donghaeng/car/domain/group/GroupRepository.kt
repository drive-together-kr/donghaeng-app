package app.donghaeng.car.domain.group

import app.donghaeng.car.core.common.DhLog
import app.donghaeng.car.core.common.TextMasking
import app.donghaeng.car.core.network.ApiResult
import app.donghaeng.car.core.network.GroupApi
import app.donghaeng.car.core.network.safeApiCall
import app.donghaeng.car.core.network.safeUnitCall
import app.donghaeng.car.domain.session.GroupInvite
import app.donghaeng.car.domain.session.GroupSession
import app.donghaeng.car.domain.session.GroupSessionStore
import app.donghaeng.car.domain.session.MqttSessionManager
import app.donghaeng.protocol.Place
import app.donghaeng.protocol.rest.GroupCreateRequest
import app.donghaeng.protocol.rest.GroupCreateResponse
import app.donghaeng.protocol.rest.GroupDetailResponse
import app.donghaeng.protocol.rest.InviteDto
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 그룹 데이터 계층 (docs/03 §2, T17). ViewModel 은 이 클래스만 본다.
 *
 * - 생성(방장): `POST /groups` → [GroupSession](isLeader, invite) 저장 → [MqttSessionManager.onGroupCreated] 가 그룹 토픽을 구독한다
 *   (서버는 만든 차량에게는 `GROUP_JOINED` 를 보내지 않는다).
 * - 멤버 합류는 서버 cmd 로 들어오므로 여기엔 없다([MqttSessionManager]).
 * - 조회·초대 재발급·나가기·종료. 좌표는 로그에 남기지 않는다.
 */
@Singleton
class GroupRepository @Inject constructor(
    private val api: GroupApi,
    private val sessionStore: GroupSessionStore,
    private val sessionManager: MqttSessionManager,
) {
    val session: StateFlow<GroupSession?> get() = sessionStore.session

    suspend fun create(name: String, destination: Place, maxMembers: Int): ApiResult<GroupSession> {
        val result = safeApiCall { api.createGroup(GroupCreateRequest(name = name, destination = destination, maxMembers = maxMembers)) }
        return when (result) {
            is ApiResult.Success -> {
                val session = result.value.toSession(name, destination)
                sessionManager.onGroupCreated(session)
                DhLog.i("group created groupId=${TextMasking.maskVehicleId(session.groupId)} maxMembers=$maxMembers inviteExpiresAt=${session.invite?.expiresAtMs}")
                ApiResult.Success(session)
            }
            is ApiResult.Failure -> {
                DhLog.w("group create failed: ${result.summary}")
                result
            }
        }
    }

    /** 초대 재발급(방장). 성공하면 세션의 invite 가 바뀐다 */
    suspend fun reissueInvite(): ApiResult<GroupInvite> {
        val current = sessionStore.session.value ?: return ApiResult.UnexpectedFailure(IllegalStateException("no active group"))
        return when (val result = safeApiCall { api.reissueInvite(current.groupId) }) {
            is ApiResult.Success -> {
                val invite = result.value.toInvite()
                sessionStore.save(current.copy(invite = invite))
                DhLog.i("invite reissued groupId=${TextMasking.maskVehicleId(current.groupId)}")
                ApiResult.Success(invite)
            }
            is ApiResult.Failure -> result
        }
    }

    suspend fun detail(groupId: String): ApiResult<GroupDetailResponse> = safeApiCall { api.getGroup(groupId) }

    /**
     * 이 차가 나간다(방장이면 승계). 성공(204)·404(이미 없음)·409(이미 끝남) 모두 로컬 세션을 지운다.
     * 204 는 본문이 없으므로 [safeUnitCall] 로 받는다(A002 — 예전엔 '빈 본문 오류'로 바뀌어 오류 대화상자가 뜨고 세션도 남았다).
     */
    suspend fun leave(): ApiResult<Unit> {
        val current = sessionStore.session.value ?: return ApiResult.Success(Unit)
        val result = safeUnitCall { api.leave(current.groupId) }
        if (result is ApiResult.Success || result is ApiResult.HttpFailure.NotFound || (result as? ApiResult.HttpFailure)?.status == 409) {
            // RA-5: 나간 그룹만 지운다 — 요청 사이 다른 그룹에 합류(GROUP_JOINED)했으면 그 세션은 둔다
            sessionManager.onGroupLeftLocally(current.groupId)
        }
        return result
    }

    /**
     * 방장이 그룹을 끝낸다. 서버가 cmd `GROUP_ENDED` 도 보내지만 로컬은 즉시 정리한다.
     * 403(이미 방장이 아님 — 위임·승계를 놓친 옛 방장, A004)이면 나가기로 대신한다: 방장 권한이 없어도 이 차는 그룹에서 빠질 수 있어야 한다.
     */
    suspend fun end(): ApiResult<Unit> {
        val current = sessionStore.session.value ?: return ApiResult.Success(Unit)
        val result = safeUnitCall { api.end(current.groupId) }
        if (result is ApiResult.Success || (result as? ApiResult.HttpFailure)?.status == 409) {
            sessionManager.onGroupLeftLocally(current.groupId)
        } else if ((result as? ApiResult.HttpFailure)?.status == 403) {
            DhLog.w("group end 403 (no longer leader) groupId=${TextMasking.maskVehicleId(current.groupId)} → leave instead")
            return leave()
        }
        return result
    }

    private fun GroupCreateResponse.toSession(name: String, destination: Place) = GroupSession(
        groupId = groupId,
        name = name,
        leaderVehicleId = leaderVehicleId,
        destination = destination,
        topics = mqttTopics,
        joinedAtMs = System.currentTimeMillis(),
        isLeader = true,
        invite = invite.toInvite(),
    )

    private fun InviteDto.toInvite() = GroupInvite(token = token, url = url, expiresAtMs = expiresAt)
}
