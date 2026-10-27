package app.donghaeng.car.domain.model

/** 기어 상태 (AAOS GEAR_SELECTION 매핑). 미지원·알 수 없음은 Flow 에서 null 로 흘린다. */
enum class Gear { P, R, N, D, UNKNOWN }

/**
 * 방향지시등 상태.
 * HAZARD 는 좌·우 동시 표시를 SDK 가 구분해 줄 때만 쓴다 — capability 결과로 지원 여부를 확인하고
 * 미지원이면 비상등 기능은 UI 에서 숨긴다(docs/05 §3.3, docs/10 R3).
 */
enum class TurnSignalState { OFF, LEFT, RIGHT, HAZARD }

/**
 * capability 점검 항목 (docs/09 §3 SDK 실측 체크리스트의 행). [label] 은 로그·진단 화면에 그대로 찍힌다.
 */
enum class CapabilityDomain(val label: String) {
    CAR_INFO("CarInfo"),
    FUEL("Fuel"),
    EV_BATTERY("EvBattery"),
    BRAKE("Brake"),
    TURN_SIGNAL("TurnSignal"),
    HAZARD("Hazard"),
    AIRBAG("Airbag"),
    SPEED("Speed"),
    GEAR("Gear"),
}

/** 점검 결과가 어디서 왔는지. UNCHECKED = 아직 확인 코드가 없음(예: T22 Car API). */
enum class CapabilitySource { PLEOS_SDK, CAR_API, FAKE, UNCHECKED }

/**
 * capability 한 항목의 결과.
 *
 * @param supported true = 지원, false = 미지원(UI 에서 숨김), **null = 확인 못 함**(SDK 초기화 실패·타임아웃·아직 미구현).
 *                  null 과 false 를 구분해야 "SDK 가 없어서 모른다" 와 "SDK 가 없다고 답했다" 를 로그에서 가릴 수 있다.
 * @param detail    accessType·예외 요약 등 사람이 읽는 상세. 좌표·개인정보 없음.
 */
data class CapabilityEntry(
    val domain: CapabilityDomain,
    val supported: Boolean?,
    val detail: String,
    val source: CapabilitySource,
) {
    /** 로그·표에 쓰는 세 값 문자열: true / false / unknown */
    val supportedText: String
        get() = when (supported) {
            true -> "true"
            false -> "false"
            null -> "unknown"
        }

    /** DH_CAP 로그 한 줄 형식: `Fuel supported=true detail=accessType=READ source=PLEOS_SDK` */
    fun format(): String = "${domain.label} supported=$supportedText detail=$detail source=$source"
}

/**
 * `checkXxxCapabilities` 결과 모음 (docs/05 §3, docs/09 §3).
 * false·null 인 항목은 UI 에서 숨기고 로그만 남긴다 — 크래시 금지(CLAUDE.md 규칙 4).
 *
 * @param entries 도메인별 결과. 같은 도메인이 두 번 들어오면 첫 항목을 쓴다.
 * @param raw     어댑터 식별 등 부가 정보(디버그 화면·로그용). 좌표·개인정보 없음.
 */
data class VehicleCapabilities(
    val entries: List<CapabilityEntry> = emptyList(),
    val raw: Map<String, String> = emptyMap(),
) {
    fun entry(domain: CapabilityDomain): CapabilityEntry? = entries.firstOrNull { it.domain == domain }

    /** 명시적으로 true 일 때만 지원으로 본다(unknown 은 미지원 취급 → 기능 숨김). */
    fun isSupported(domain: CapabilityDomain): Boolean = entry(domain)?.supported == true

    val vehicleId: Boolean get() = isSupported(CapabilityDomain.CAR_INFO)
    val fuelPercent: Boolean get() = isSupported(CapabilityDomain.FUEL)
    val evStateOfCharge: Boolean get() = isSupported(CapabilityDomain.EV_BATTERY)
    val brakePedal: Boolean get() = isSupported(CapabilityDomain.BRAKE)
    val turnSignal: Boolean get() = isSupported(CapabilityDomain.TURN_SIGNAL)
    val hazard: Boolean get() = isSupported(CapabilityDomain.HAZARD)
    val airbag: Boolean get() = isSupported(CapabilityDomain.AIRBAG)
    val speed: Boolean get() = isSupported(CapabilityDomain.SPEED)
    val gear: Boolean get() = isSupported(CapabilityDomain.GEAR)

    companion object {
        /** 아무것도 모름 — 어댑터가 아직 점검하지 않았을 때의 빈 값 */
        val NONE = VehicleCapabilities()

        /** 모든 도메인을 같은 사유로 "확인 못 함"(null) 처리한다 — SDK 초기화 실패 등. */
        fun allUnknown(reason: String, source: CapabilitySource = CapabilitySource.UNCHECKED, raw: Map<String, String> = emptyMap()) =
            VehicleCapabilities(
                entries = CapabilityDomain.entries.map { CapabilityEntry(it, supported = null, detail = reason, source = source) },
                raw = raw,
            )
    }
}
