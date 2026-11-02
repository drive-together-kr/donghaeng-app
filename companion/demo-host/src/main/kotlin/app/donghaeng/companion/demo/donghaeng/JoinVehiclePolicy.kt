package app.donghaeng.companion.demo.donghaeng

import app.donghaeng.companion.sdk.MyVehicle

/**
 * 그룹 합류 시트의 "어떤 차로 함께 갈까요?" 규칙(D026·D093·D078·D092·D116·RD-2).
 *
 * - **이 폰에서 동행 참여를 꺼 둔 차**(마이카 > 동행 그룹 주행)는 고를 수 없다 — 설정이 실제로 효과가 있게.
 * - **이 그룹의 방장 차**(초대 미리보기의 leaderVehicleId)도 고를 수 있다 — 그 차에 타고 있는 동승자가 폰으로 그룹을 보는 경로다.
 *   그 차는 이미 그룹에 있어 서버는 409 `ALREADY_MEMBER` 를 주고, 동행 모듈이 그것을 성공(그 그룹 보기)으로 바꾼다(D024).
 *   차는 움직이지 않고, 동승자 메시지는 그 차(fromVehicleId)에서 온 것으로 읽힌다. 계정에 차가 한 대뿐인 구성(심사 기본: 차 1대 + 폰)은
 *   이 경로가 아니면 폰에서 자기 그룹을 볼 수 없다(RD-2 — 예전에는 막았다).
 * - **미리 고르기**: 방장 차가 아닌 고를 수 있는 차가 딱 한 대면 그 차, 고를 수 있는 차가 방장 차뿐이면 그 차, 여러 대면 미리 고르지 않는다 —
 *   대표 차량이 다른 사람(같은 계정을 쓰는 사람)의 차일 수도 있으므로 사용자가 직접 고른다.
 * - 미리 고른 차는 목록·미리보기가 늦게 와서 바뀌면 다시 정한다(예: 먼저 보인 목록이 방장 차 한 대뿐이라 그 차를 골랐는데, 새로 불러온 목록에
 *   방금 등록한 둘째 차가 있으면 둘째 차로). 사용자가 직접 고른 차는 고를 수 있는 한 그대로 둔다.
 */
object JoinVehiclePolicy {

    enum class Block { PARTICIPATION_OFF }

    /**
     * @param blocked 고를 수 없는 이유(null = 고를 수 있음)
     * @param leaderOfThisGroup 이 그룹의 방장 차(고를 수 있지만, 다른 차가 있으면 미리 고르지 않는다)
     */
    data class Option(val vehicle: MyVehicle, val blocked: Block?, val leaderOfThisGroup: Boolean = false) {
        val selectable: Boolean get() = blocked == null
    }

    fun options(vehicles: List<MyVehicle>, leaderVehicleId: String?, participationOff: Set<String>): List<Option> =
        vehicles.map { v ->
            Option(
                vehicle = v,
                blocked = if (v.vehicleId in participationOff) Block.PARTICIPATION_OFF else null,
                leaderOfThisGroup = leaderVehicleId != null && v.vehicleId == leaderVehicleId,
            )
        }

    /**
     * 보일 선택. [userPicked] = [current] 를 사용자가 직접 골랐다 — 그 차가 아직 고를 수 있으면 그대로 둔다.
     * 아니면(미리 고른 값이거나 고를 수 없게 됐으면) [defaultSelection] 으로 다시 정한다.
     */
    fun selection(options: List<Option>, current: String?, userPicked: Boolean = false): String? {
        if (userPicked && current != null && options.any { it.selectable && it.vehicle.vehicleId == current }) return current
        return defaultSelection(options)
    }

    /** 미리 고를 차 — 방장 차가 아닌 고를 수 있는 차가 한 대면 그 차, 고를 수 있는 차가 방장 차뿐이면 그 차, 그 밖에는 고르지 않음 */
    fun defaultSelection(options: List<Option>): String? {
        val selectable = options.filter { it.selectable }
        val others = selectable.filterNot { it.leaderOfThisGroup }
        val pick = if (others.isNotEmpty()) others.singleOrNull() else selectable.singleOrNull()
        return pick?.vehicle?.vehicleId
    }

    /** 차 목록의 태그 — 목록은 모두 내 계정의 차다(서버 /me/vehicles 는 owner 기준). '공유받은 차량'이라고 쓰지 않는다 */
    fun tag(option: Option): String = when {
        option.blocked == Block.PARTICIPATION_OFF -> "참여 꺼 둠"
        option.leaderOfThisGroup -> "이 그룹 방장 차"
        option.vehicle.isPrimary -> "대표 차량"
        else -> "내 차"
    }

    /** 차 이름 아래 한 줄 안내(없으면 null) — 방장 차를 고르면 차를 옮기는 게 아니라 그 차에 탄 채로 폰에서 그룹을 본다 */
    fun hint(option: Option): String? =
        if (option.selectable && option.leaderOfThisGroup) "이 차에 타고 있으면 고르세요 — 차는 그대로, 폰으로 그룹을 봐요" else null
}
