package app.donghaeng.car.domain.session

import app.donghaeng.protocol.GroupJoined

/**
 * 서버가 보는 이 차의 그룹 소속 — 재접속·앱 시작 때 로컬 세션을 서버와 맞추는 데 쓴다(A000/A001, docs/03 계약 1).
 * MQTT cmd(`GROUP_JOINED`·`GROUP_ENDED`)는 영속 세션(300 s) 안에서만 다시 오므로, 그보다 오래 꺼져 있던 차는 이것으로 놓친 변화를 따라잡는다.
 */
sealed interface ServerMembership {
    /** 활성 그룹이 있다(cmd `GROUP_JOINED` 본문과 같은 형태) */
    data class Active(val group: GroupJoined) : ServerMembership

    /** 활성 그룹이 없다(204) */
    data object None : ServerMembership

    /** 서버가 `GET /v1/vehicles/me/group` 을 모른다(404·405 — 옛 서버). 저장된 그룹 하나만 [GroupMembershipSource.checkGroup] 으로 확인한다 */
    data object Unsupported : ServerMembership

    /** 네트워크·5xx·401 등 — 아무것도 바꾸지 않고 다음 연결 때 다시 */
    data class Unknown(val summary: String) : ServerMembership
}

/** 저장된 그룹 하나의 서버 상태(계약 1 을 모르는 서버 폴백) */
enum class GroupCheck {
    /** ACTIVE 이고 내 차가 멤버다 */
    ACTIVE_MEMBER,

    /** 끝났거나(status ENDED·409·410) 내 차가 멤버가 아니다(members 에 없음·404) — 로컬 세션은 유령 */
    GONE,

    /** 확인 못 함(네트워크 등) */
    UNKNOWN,
}

/** 소속 조회 창구. 구현: `domain/group/RestGroupMembershipSource`(차량 JWT REST) / [DISABLED](테스트 기본값) */
interface GroupMembershipSource {
    suspend fun myActiveGroup(): ServerMembership

    suspend fun checkGroup(groupId: String, myVehicleId: String): GroupCheck

    companion object {
        /** 조회하지 않는다 — reconcile 이 아무것도 바꾸지 않는다(기존 단위 테스트·조회 불가 환경) */
        val DISABLED: GroupMembershipSource = object : GroupMembershipSource {
            override suspend fun myActiveGroup(): ServerMembership = ServerMembership.Unknown("disabled")
            override suspend fun checkGroup(groupId: String, myVehicleId: String): GroupCheck = GroupCheck.UNKNOWN
        }
    }
}
