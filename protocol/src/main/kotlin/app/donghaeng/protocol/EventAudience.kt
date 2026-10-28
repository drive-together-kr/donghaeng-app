package app.donghaeng.protocol

/**
 * 그룹 이벤트 수신 대상 규칙 (docs/03 §5.4 `audience`) — 서버(대상 고르기)·차량 앱(수신 필터)·companion(알림)이 **같은 정의**를 쓴다.
 *
 * - [GroupEvent.audience] null = 그룹 전체, 목록 = 그 vehicleId 들만, 빈 목록 = 아무도 알리지 않는다(이벤트는 기록·피드용으로 남는다).
 * - 앞뒤로 대상을 고르는 종류([BEHIND_ONLY_TYPES] = `HARD_BRAKE`): "앞쪽 ○○ 차가 급제동"은 **출처보다 경로상 뒤에 있는 차**만 들어야 한다.
 *   "뒤" = 그룹 스냅샷 `distanceToLeaderM` 이 출처보다 큰 멤버 — 플로팅 패널 간격(상대 − 나, 양수 = "뒤")과 같은 기준(선두 0, 클수록 뒤).
 * - 가정: 선두가 맨 앞이다(`distanceToLeaderM` 은 선두까지 직선 거리라, 선두를 앞지른 차는 뒤로 계산된다 — 패널 간격과 같은 한계).
 *   출처나 어떤 멤버의 값을 모르면(위치 없음·스냅샷 없음) 그 멤버는 **대상에 넣는다** — 경고를 놓치는 것보다 한 번 더 듣는 편이 낫다.
 */
object EventAudience {

    /** 앞뒤 관계로 대상을 고르는 종류 */
    val BEHIND_ONLY_TYPES: Set<String> = setOf(GroupEventType.HARD_BRAKE.name)

    /** 판정 근거 — 진단 로그용 */
    enum class Basis {
        /** 그룹 전체 이벤트(audience 없음, 앞뒤 무관 종류) */
        GROUP,

        /** 서버가 정한 [GroupEvent.audience] */
        AUDIENCE,

        /** audience 없는 앞뒤 종류(옛 서버·로컬 주입) — 내 스냅샷으로 판정 */
        SNAPSHOT,

        /** 판정에 필요한 값(내 id·출처·스냅샷·거리)을 몰라 대상으로 친다 */
        UNKNOWN,
    }

    data class Verdict(val forMe: Boolean, val basis: Basis)

    fun isBehindOnly(type: String): Boolean = type in BEHIND_ONLY_TYPES

    /** 멤버(선두까지 [memberM])가 출처([sourceM])보다 뒤인가 — 어느 쪽이든 모르면 true(대상에 넣는다) */
    fun isBehind(memberM: Int?, sourceM: Int?): Boolean = memberM == null || sourceM == null || memberM > sourceM

    /**
     * 서버: [sourceVehicleId] 보다 뒤에 있는 멤버 vehicleId(출처 제외, [members] 순서 유지).
     * 출처가 명단에 없거나 값을 모르면 나머지 전원, 값을 모르는 멤버는 포함. 뒤차가 없으면 빈 목록.
     */
    fun behind(sourceVehicleId: String, members: List<MemberState>): List<String> {
        val sourceM = members.firstOrNull { it.vehicleId == sourceVehicleId }?.distanceToLeaderM
        return members.filter { it.vehicleId != sourceVehicleId && isBehind(it.distanceToLeaderM, sourceM) }.map { it.vehicleId }
    }

    /** [decide] 의 결과만 */
    fun isForMe(event: GroupEvent, myVehicleId: String?, members: List<MemberState>?): Boolean =
        decide(event, myVehicleId, members).forMe

    /**
     * 내 차([myVehicleId])가 [event] 를 알려야(음성·카드·패널 소식 줄·피드) 하는 대상인가.
     *
     * 1. audience 가 있으면 `myVehicleId ∈ audience`(내 id 를 모르면 대상).
     * 2. audience 가 없고 앞뒤 종류면(옛 서버·디버그 주입) 내 최신 스냅샷 [members] 로 판정: 출처보다 뒤면 대상, 내 차가 출처면 아님,
     *    내 id·출처·스냅샷·둘 중 한쪽 거리를 모르면 대상.
     * 3. 그 밖은 그룹 전체(대상).
     *
     * 내 차 출처 이벤트를 거르는 규칙(음성 OWN_SKIPPED_TYPES·카드·표면)은 각 소비자가 따로 갖는다 — 여기는 "누구에게 보낸 것인가"만 본다.
     */
    fun decide(event: GroupEvent, myVehicleId: String?, members: List<MemberState>?): Verdict {
        val audience = event.audience
        if (audience != null) {
            return if (myVehicleId == null) Verdict(true, Basis.UNKNOWN) else Verdict(myVehicleId in audience, Basis.AUDIENCE)
        }
        if (!isBehindOnly(event.type)) return Verdict(true, Basis.GROUP)
        val source = event.sourceVehicleId
        if (myVehicleId == null || source == null || members == null) return Verdict(true, Basis.UNKNOWN)
        if (source == myVehicleId) return Verdict(false, Basis.SNAPSHOT)
        val me = members.firstOrNull { it.vehicleId == myVehicleId }?.distanceToLeaderM
        val src = members.firstOrNull { it.vehicleId == source }?.distanceToLeaderM
        if (me == null || src == null) return Verdict(true, Basis.UNKNOWN)
        return Verdict(isBehind(me, src), Basis.SNAPSHOT)
    }
}
