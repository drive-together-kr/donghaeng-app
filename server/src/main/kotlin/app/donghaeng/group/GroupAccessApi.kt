package app.donghaeng.group

import app.donghaeng.group.repo.GroupMemberRepository
import app.donghaeng.group.repo.GroupRepository
import app.donghaeng.identity.IdentityApi
import app.donghaeng.identity.VehicleInfo
import org.springframework.stereotype.Component

/**
 * 그룹에 딸린 자원(F16 무전 — `voice` 패키지)을 다루는 모듈이 호출자 자격을 묻는 창구 (docs/06 §2: 모듈 간 `XxxApi`).
 *
 * 규칙은 그룹 API(`GroupService`)와 같다 — 차량 토큰: 페어링이 살아 있고 토큰 `owner` = 현재 소유자(아니면 403 `FORBIDDEN`),
 * 그룹이 없거나 현재 멤버가 아니면 404 `NOT_FOUND`(존재 숨김), 멤버지만 끝난 그룹이면 409 `GROUP_ENDED`.
 * 실패는 group 모듈의 오류(`ApiException`)를 그대로 던져 오류 코드·문구가 그룹 API 와 같게 한다.
 */
interface GroupAccessApi {

    /**
     * 업로드 자격: 차량 [caller] 가 [groupId] 의 현재 멤버이고 그룹이 진행 중인가 — 403 → 404 → 409 순.
     * 그룹 행을 `FOR SHARE` 로 잡아 **호출자 트랜잭션이 끝날 때까지** 종료·나가기(`FOR UPDATE`)와 직렬화한다.
     * 그래서 반드시 `@Transactional` 안에서 부른다(끝나는 그룹에 자원이 남거나 `GROUP_ENDED` 뒤에 이벤트가 나가지 않게).
     *
     * 잠금 순서 = **차량 행(`FOR KEY SHARE`, identity) → 그룹 행(`FOR SHARE`)** — 재페어링(`VehicleRegistry.register`: 차량 행 `FOR UPDATE`
     * → `evictVehicle`: 그룹 행 `FOR UPDATE`)과 같은 순서다. 호출자가 그 뒤 차량을 참조(FK)하는 행을 넣어도 FK 검사가 이미 쥔 차량 행 잠금을 쓰므로
     * 그룹 행을 쥔 채 차량 행을 기다리지 않는다(예전 순서는 같은 차 재페어링과 엇갈리면 교착 40P01 → 업로드 500).
     *
     * @return 보낸 차 정보(별명 — 음성 문구용). 차량 행을 잡은 뒤 읽은 값이라 소유권 이전(재페어링)은 호출자 트랜잭션이 끝날 때까지 기다린다(해제·별명 변경은 막지 않는다)
     */
    fun lockActiveMemberVehicle(caller: Caller.Vehicle, groupId: String): VehicleInfo

    /**
     * 조회 자격: 현재 멤버 차량 자신, 또는 현재 멤버 차량의 (해제되지 않은) 소유자 — `GET /groups/{id}` 와 같은 규칙.
     * 해제된 차량 토큰은 403, 자격이 없으면 404.
     *
     * @return 그룹이 진행 중이면 true, 끝났으면 false(종료 시점 멤버는 끝난 그룹도 볼 수 있다 — 자원의 공개 여부는 호출자가 정한다)
     */
    fun requireViewer(caller: Caller, groupId: String): Boolean
}

@Component
class DefaultGroupAccessApi(
    private val groups: GroupRepository,
    private val members: GroupMemberRepository,
    private val identity: IdentityApi,
) : GroupAccessApi {

    override fun lockActiveMemberVehicle(caller: Caller.Vehicle, groupId: String): VehicleInfo {
        // 차량 행을 그룹 행보다 먼저 잡는다(재페어링과 같은 순서 — 인터페이스 KDoc)
        val vehicle = requireActive(caller, identity.lockVehicleForShare(caller.vehicleId))
        val group = groups.findByIdForShare(groupId) ?: throw GroupErrors.groupNotFound()
        // 멤버 확인은 잠금 뒤에 읽는다 — 그 사이 나가기가 커밋됐으면 여기서 404
        if (members.findActive(groupId, vehicle.vehicleId) == null) throw GroupErrors.groupNotFound()
        if (!group.active) throw GroupErrors.groupEnded()
        return vehicle
    }

    override fun requireViewer(caller: Caller, groupId: String): Boolean {
        if (caller is Caller.Vehicle) requireActive(caller, identity.vehicle(caller.vehicleId))
        val group = groups.findById(groupId) ?: throw GroupErrors.groupNotFound()
        val current = members.listActive(groupId)
        val allowed = when (caller) {
            is Caller.Vehicle -> current.any { it.vehicleId == caller.vehicleId }
            is Caller.User -> identity.vehicles(current.map { it.vehicleId }).values.any { it.ownerId == caller.userId && it.active }
        }
        if (!allowed) throw GroupErrors.groupNotFound()
        return group.active
    }

    /** `GroupService.requireActiveVehicle` 와 같은 규칙: 없거나 해제됐거나 토큰 발급 뒤 소유자가 바뀐 차량은 403 */
    private fun requireActive(caller: Caller.Vehicle, found: VehicleInfo?): VehicleInfo {
        val vehicle = found ?: throw GroupErrors.vehicleRevoked()
        if (!vehicle.active) throw GroupErrors.vehicleRevoked()
        if (caller.ownerId != null && caller.ownerId != vehicle.ownerId) throw GroupErrors.vehicleRevoked()
        return vehicle
    }
}
