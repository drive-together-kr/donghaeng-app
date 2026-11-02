package app.donghaeng.car.domain.group

import app.donghaeng.car.core.network.ApiResult
import app.donghaeng.car.core.network.GroupApi
import app.donghaeng.car.core.network.VehicleSelfApi
import app.donghaeng.car.core.network.parseErrorBody
import app.donghaeng.car.core.network.safeApiCall
import app.donghaeng.car.domain.session.GroupCheck
import app.donghaeng.car.domain.session.GroupMembershipSource
import app.donghaeng.car.domain.session.ServerMembership
import app.donghaeng.protocol.GroupJoined
import app.donghaeng.protocol.rest.GroupDetailResponse
import kotlinx.coroutines.CancellationException
import retrofit2.Response
import java.io.IOException

/**
 * 차량 JWT REST 로 소속을 묻는다 (docs/03 계약 1, A000/A001).
 * - `GET /v1/vehicles/me/group` → 200 [ServerMembership.Active] / 204 [ServerMembership.None] / 404·405 [ServerMembership.Unsupported](옛 서버)
 * - 폴백 `GET /v1/groups/{id}` → status ENDED·내 차 없음·404·409·410 = [GroupCheck.GONE]
 */
class RestGroupMembershipSource(
    private val selfApi: VehicleSelfApi,
    private val groupApi: GroupApi,
) : GroupMembershipSource {

    override suspend fun myActiveGroup(): ServerMembership = try {
        classifyMyGroup(selfApi.myGroup())
    } catch (e: IOException) {
        ServerMembership.Unknown("network ${e.javaClass.simpleName}")
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ServerMembership.Unknown("unexpected ${e.javaClass.simpleName}")
    }

    override suspend fun checkGroup(groupId: String, myVehicleId: String): GroupCheck =
        classifyDetail(safeApiCall { groupApi.getGroup(groupId) }, myVehicleId)

    companion object {
        /** 계약 1 응답 분류(순수 함수 — 단위 테스트) */
        fun classifyMyGroup(response: Response<GroupJoined>): ServerMembership {
            if (response.isSuccessful) {
                val body = response.body()
                // 204(본문 없음) 또는 200 인데 본문 없음 → 그룹 없음
                return if (body == null) ServerMembership.None else ServerMembership.Active(body)
            }
            val code = response.code()
            if (code == 404 || code == 405) return ServerMembership.Unsupported
            val err = runCatching { parseErrorBody(response.errorBody()?.string()) }.getOrNull()
            return ServerMembership.Unknown("HTTP $code ${err?.code ?: ""}".trim())
        }

        /** `GET /groups/{id}` 결과 분류(순수 함수 — 단위 테스트) */
        fun classifyDetail(result: ApiResult<GroupDetailResponse>, myVehicleId: String): GroupCheck = when (result) {
            is ApiResult.Success -> {
                val d = result.value
                if (d.status == "ENDED" || d.members.none { it.vehicleId == myVehicleId }) GroupCheck.GONE else GroupCheck.ACTIVE_MEMBER
            }
            is ApiResult.HttpFailure -> if (result.status == 404 || result.status == 409 || result.status == 410) GroupCheck.GONE else GroupCheck.UNKNOWN
            is ApiResult.Failure -> GroupCheck.UNKNOWN
        }
    }
}
