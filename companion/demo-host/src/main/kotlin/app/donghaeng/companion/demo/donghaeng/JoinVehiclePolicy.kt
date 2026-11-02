package app.donghaeng.companion.demo.donghaeng

import app.donghaeng.companion.sdk.MyVehicle

/**
 * 그룹 합류 시트의 "어떤 차로 함께 갈까요?" 규칙(D026·D093·D078·D092·D116).
 *
 * - **이 그룹의 방장 차**(초대 미리보기의 leaderVehicleId)는 고를 수 없다 — 이미 그 그룹에 있다(예전에는 대표 차량 = 방장 차를 기본으로 골라 409).
 * - **이 폰에서 동행 참여를 꺼 둔 차**(마이카 > 동행 그룹 주행)는 고를 수 없다 — 설정이 실제로 효과가 있게.
 * - 고를 수 있는 차가 **딱 한 대**면 그 차를 미리 고른다. 여러 대면 미리 고르지 않는다 — 대표 차량이 다른 사람(같은 계정을 쓰는 사람)의
 *   차일 수도 있으므로 사용자가 직접 고른다.
 */
object JoinVehiclePolicy {

    enum class Block { LEADER_OF_THIS_GROUP, PARTICIPATION_OFF }

    data class Option(val vehicle: MyVehicle, val blocked: Block?) {
        val selectable: Boolean get() = blocked == null
    }

    fun options(vehicles: List<MyVehicle>, leaderVehicleId: String?, participationOff: Set<String>): List<Option> =
        vehicles.map { v ->
            val block = when {
                leaderVehicleId != null && v.vehicleId == leaderVehicleId -> Block.LEADER_OF_THIS_GROUP
                v.vehicleId in participationOff -> Block.PARTICIPATION_OFF
                else -> null
            }
            Option(v, block)
        }

    /**
     * 지금 선택([current])을 유지할지, 기본값을 정할지. 지금 선택이 고를 수 없는 차가 됐으면(미리보기가 늦게 와 방장 차로 밝혀짐 등) 풀어 준다.
     */
    fun selection(options: List<Option>, current: String?): String? {
        val selectable = options.filter { it.selectable }
        if (current != null && selectable.any { it.vehicle.vehicleId == current }) return current
        return selectable.singleOrNull()?.vehicle?.vehicleId
    }

    /** 차 목록의 태그 — 목록은 모두 내 계정의 차다(서버 /me/vehicles 는 owner 기준). '공유받은 차량'이라고 쓰지 않는다 */
    fun tag(option: Option): String = when (option.blocked) {
        Block.LEADER_OF_THIS_GROUP -> "이 그룹 방장 차"
        Block.PARTICIPATION_OFF -> "참여 꺼 둠"
        null -> if (option.vehicle.isPrimary) "대표 차량" else "내 차"
    }
}
