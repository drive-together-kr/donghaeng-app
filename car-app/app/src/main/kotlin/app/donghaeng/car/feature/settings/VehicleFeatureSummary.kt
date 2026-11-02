package app.donghaeng.car.feature.settings

import app.donghaeng.car.domain.model.CapabilityDomain
import app.donghaeng.car.domain.model.VehicleCapabilities

/**
 * 진단 맨 위 '이 차에서 쓰는 차량 기능' 카드(5-2) — capability 실측값을 사람 말로 요약한다(순수 — 단위 테스트).
 * 지원 = 동행이 쓰는 기능(급제동 감지·저잔량 알림·도착 예상), 미지원 = 이 차가 지원하지 않아 화면·안내에서 자동으로 숨긴 것, 모름 = 확인 못 함.
 * 차량 ID(CarInfo)는 페어링용이라 목록에서 뺀다.
 */
object VehicleFeatureSummary {
    data class Summary(val used: List<String>, val hidden: List<String>, val unknown: List<String>)

    /** 표시 순서·이름 — 운전자가 아는 말로 */
    private val ORDER = listOf(
        CapabilityDomain.SPEED to "속도",
        CapabilityDomain.GEAR to "기어",
        CapabilityDomain.BRAKE to "브레이크",
        CapabilityDomain.TURN_SIGNAL to "방향지시등",
        CapabilityDomain.EV_BATTERY to "EV 배터리",
        CapabilityDomain.FUEL to "연료",
        CapabilityDomain.HAZARD to "비상등",
        CapabilityDomain.AIRBAG to "에어백",
    )

    fun nameOf(domain: CapabilityDomain): String = ORDER.firstOrNull { it.first == domain }?.second ?: domain.label

    fun of(caps: VehicleCapabilities?): Summary? {
        caps ?: return null
        val byDomain = caps.entries.associateBy { it.domain }
        val used = mutableListOf<String>()
        val hidden = mutableListOf<String>()
        val unknown = mutableListOf<String>()
        ORDER.forEach { (domain, name) ->
            when (byDomain[domain]?.supported) {
                true -> used += name
                false -> hidden += name
                null -> if (byDomain.containsKey(domain)) unknown += name
            }
        }
        return Summary(used, hidden, unknown)
    }
}
