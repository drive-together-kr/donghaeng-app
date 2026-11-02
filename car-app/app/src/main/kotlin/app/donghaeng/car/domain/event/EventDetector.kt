package app.donghaeng.car.domain.event

import app.donghaeng.car.core.common.Geo
import app.donghaeng.car.domain.model.NaviEvent
import app.donghaeng.car.domain.model.Place
import app.donghaeng.protocol.EnergyKind
import app.donghaeng.protocol.VehicleEventType
import kotlin.math.roundToInt

/** 판정된 차량 이벤트 (docs/03 §5.2 의 data 와 1:1) */
sealed class DetectedEvent(val type: VehicleEventType) {
    data class LowEnergy(val kind: EnergyKind, val pct: Int) : DetectedEvent(VehicleEventType.LOW_ENERGY)
    data class HardBrake(val decelMps2: Double, val speedBeforeKph: Double) : DetectedEvent(VehicleEventType.HARD_BRAKE)
    object AirbagDeployed : DetectedEvent(VehicleEventType.AIRBAG_DEPLOYED) { override fun toString() = "AirbagDeployed" }
    data class Stopped(val durationS: Int) : DetectedEvent(VehicleEventType.STOPPED)
    data class Resumed(val durationS: Int) : DetectedEvent(VehicleEventType.RESUMED)
    data class WaypointArrived(val name: String, val lat: Double, val lon: Double) : DetectedEvent(VehicleEventType.WAYPOINT_ARRIVED)
    data class DestinationArrived(val name: String) : DetectedEvent(VehicleEventType.DESTINATION_ARRIVED)
}

/** 1Hz 입력 한 틱. 모르는 값은 null */
data class Sample(
    val tsMs: Long,
    val speedKph: Float?,
    val brakePressed: Boolean?,
    val fuelPct: Int?,
    val socPct: Int?,
    val airbagDeployed: Boolean?,
    val lat: Double?,
    val lon: Double?,
)

/**
 * 차량 이벤트 판정기 (T26, docs/03 §5.2, docs/05 §3.3). 순수 로직 — 입력은 [onSample](1Hz)·[onNavi], 출력은 [DetectedEvent] 목록.
 *
 * - LOW_ENERGY: 잔량(연료가 있으면 연료, 아니면 SoC) ≤ [lowEnergyThresholdPct] 최초 진입 시, 이후 [lowEnergyStepPct]%p 내려갈 때마다. [lowEnergyResetPct] 이상으로 오르면(주유) 초기화.
 * - HARD_BRAKE: 브레이크 ON && 직전 샘플(0.5~1.5초 전) 대비 감속 ≥ [hardBrakeDecelMps2] && 직전 속도 ≥ [hardBrakeMinSpeedKph]. 쿨다운 [hardBrakeCooldownMs].
 * - STOPPED: 속도 < [stopSpeedKph] 가 [stoppedAfterMs] 지속 → 1회. RESUMED: 그 뒤 속도 ≥ [resumeSpeedKph] → 총 정차 시간과 함께 1회.
 * - AIRBAG_DEPLOYED: false→true 에지 1회(초기화 없음).
 * - DESTINATION_ARRIVED: 내비 `onDestinationArrived`, 또는 그룹 목적지 반경 [arrivalRadiusM] m 안 + 속도 < 3 km/h (docs/10 R4 보조). **목적지당 1회**.
 *   A005: '그룹이 가는 목적지'(그룹 목적지 + 따라간 선두 경로의 목적지 [setFollowedDestination])에 도착했을 때만 알린다 — 내비 목적지를 주유소 등으로
 *   바꿔 도착한 건 그룹 소식이 아니다. 내비 도착은 내비가 준 목적지 좌표(없으면 마지막 위치)가 그 목적지들 중 하나에서 [naviMatchRadiusM]
 *   (위치로 볼 땐 [naviPositionMatchRadiusM]) 안일 때만 발행하고, 아니면 무시한다(래치도 걸지 않는다 — 나중의 진짜 도착을 막지 않게).
 *   좌표 정보가 하나도 없으면(내비가 목적지를 안 줌 + 위치 없음) 예전처럼 그룹 목적지 도착으로 본다.
 * - WAYPOINT_ARRIVED: 내비 `onWayPointArrived` (좌표는 경유지 좌표, 없으면 현재 위치).
 */
class EventDetector(
    private val lowEnergyThresholdPct: Int = 15,
    private val lowEnergyStepPct: Int = 5,
    private val lowEnergyResetPct: Int = 20,
    private val hardBrakeDecelMps2: Double = 4.0,
    private val hardBrakeMinSpeedKph: Float = 40f,
    private val hardBrakeCooldownMs: Long = 30_000L,
    private val stopSpeedKph: Float = 1f,
    private val stoppedAfterMs: Long = 60_000L,
    private val resumeSpeedKph: Float = 5f,
    private val arrivalRadiusM: Double = 150.0,
    private val naviMatchRadiusM: Double = 500.0,
    private val naviPositionMatchRadiusM: Double = 1_000.0,
) {
    // ---- 상태 (진단용으로 읽을 수 있다)
    var lastEnergyAlertPct: Int? = null
        private set
    var stoppedSinceMs: Long? = null
        private set
    var stoppedAnnounced: Boolean = false
        private set
    var lastHardBrakeMs: Long? = null
        private set

    /** 지금 그룹 목적지 도착을 이미 알렸나 */
    val arrivedAnnounced: Boolean get() = destination?.let { keyOf(it) in announcedArrivals } == true
    private var airbagAnnounced = false
    private var destination: Place? = null

    /** 따라간 선두 경로의 목적지(그룹 목적지와 다를 수 있다, A005) */
    private var followed: Place? = null

    /** 도착을 알린 목적지(좌표 키) — 목적지당 1회 */
    private val announcedArrivals = HashSet<String>()
    private var prevSpeedKph: Float? = null
    private var prevTsMs: Long? = null
    private var prevBrake: Boolean? = null
    private var lastLat: Double? = null
    private var lastLon: Double? = null

    /** 그룹 목적지 설정. 다른 좌표면 그 목적지의 도착 판정은 새로 한다(목적지별 1회) */
    fun setDestination(place: Place?) {
        destination = place
    }

    /** 따라간 선두 경로의 목적지(RouteSyncEngine 이 수락·발행한 목적지). null = 그룹 목적지만 */
    fun setFollowedDestination(place: Place?) {
        followed = place
    }

    /** 그룹이 바뀌거나 끝났을 때 — 에너지 경고·정차 상태는 유지(차량 상태이지 그룹 상태가 아니다), 도착 판정만 초기화 */
    fun resetForNewGroup() {
        announcedArrivals.clear()
        followed = null
    }

    fun onSample(s: Sample): List<DetectedEvent> {
        val out = ArrayList<DetectedEvent>(2)
        lowEnergy(s)?.let(out::add)
        hardBrake(s)?.let(out::add)
        stopResume(s)?.let(out::add)
        airbag(s)?.let(out::add)
        arrivalByProximity(s)?.let(out::add)
        prevSpeedKph = s.speedKph ?: prevSpeedKph
        prevTsMs = if (s.speedKph != null) s.tsMs else prevTsMs
        prevBrake = s.brakePressed ?: prevBrake
        if (s.lat != null && s.lon != null) { lastLat = s.lat; lastLon = s.lon }
        return out
    }

    fun onNavi(e: NaviEvent): List<DetectedEvent> = when (e) {
        is NaviEvent.WaypointArrived -> {
            val name = e.name ?: e.waypoint?.name ?: "경유지"
            val lat = e.waypoint?.lat ?: lastLat
            val lon = e.waypoint?.lon ?: lastLon
            if (lat != null && lon != null) listOf(DetectedEvent.WaypointArrived(name, lat, lon)) else emptyList()
        }
        is NaviEvent.DestinationArrived -> naviArrival(e)
        else -> emptyList()
    }

    /** A005: 내비 도착이 그룹이 가는 목적지인지 가린다 */
    private fun naviArrival(e: NaviEvent.DestinationArrived): List<DetectedEvent> {
        val targets = groupTargets()
        val naviDest = e.destination
        val lat = lastLat
        val lon = lastLon
        val target: Place? = when {
            targets.isEmpty() -> null
            naviDest != null -> targets.firstOrNull { Geo.distanceM(naviDest.lat, naviDest.lon, it.lat, it.lon) <= naviMatchRadiusM }
                ?: return emptyList<DetectedEvent>().also { ignoredNaviArrivals++ }
            lat != null && lon != null -> targets.firstOrNull { Geo.distanceM(lat, lon, it.lat, it.lon) <= naviPositionMatchRadiusM }
                ?: return emptyList<DetectedEvent>().also { ignoredNaviArrivals++ }
            // 좌표 정보가 없다 — 예전처럼 그룹 목적지 도착으로 본다
            else -> targets.first()
        }
        val key = target?.let(::keyOf) ?: NO_TARGET_KEY
        if (key in announcedArrivals) return emptyList()
        announcedArrivals += key
        return listOf(DetectedEvent.DestinationArrived(e.name ?: naviDest?.name ?: target?.name ?: "목적지"))
    }

    /** 그룹 목적지 밖이라 무시한 내비 도착 수(진단·테스트용) */
    var ignoredNaviArrivals: Int = 0
        private set

    private fun groupTargets(): List<Place> = listOfNotNull(destination, followed).distinctBy(::keyOf)

    private fun keyOf(p: Place): String = "%.5f,%.5f".format(java.util.Locale.ROOT, p.lat, p.lon)

    private fun lowEnergy(s: Sample): DetectedEvent? {
        val (kind, pct) = when {
            s.fuelPct != null -> EnergyKind.FUEL to s.fuelPct
            s.socPct != null -> EnergyKind.EV to s.socPct
            else -> return null
        }
        if (pct >= lowEnergyResetPct) {
            lastEnergyAlertPct = null
            return null
        }
        if (pct > lowEnergyThresholdPct) return null
        val last = lastEnergyAlertPct
        return if (last == null || pct <= last - lowEnergyStepPct) {
            lastEnergyAlertPct = pct
            DetectedEvent.LowEnergy(kind, pct)
        } else {
            null
        }
    }

    private fun hardBrake(s: Sample): DetectedEvent? {
        val speed = s.speedKph ?: return null
        val prevSpeed = prevSpeedKph ?: return null
        val prevTs = prevTsMs ?: return null
        val dtS = (s.tsMs - prevTs) / 1000.0
        if (dtS < 0.5 || dtS > 1.5) return null
        val braking = s.brakePressed == true || prevBrake == true
        if (!braking) return null
        if (prevSpeed < hardBrakeMinSpeedKph) return null
        val decel = (prevSpeed - speed) / 3.6 / dtS
        if (decel < hardBrakeDecelMps2) return null
        val last = lastHardBrakeMs
        if (last != null && s.tsMs - last < hardBrakeCooldownMs) return null
        lastHardBrakeMs = s.tsMs
        return DetectedEvent.HardBrake(decelMps2 = (decel * 10).roundToInt() / 10.0, speedBeforeKph = (prevSpeed * 10).roundToInt() / 10.0)
    }

    private fun stopResume(s: Sample): DetectedEvent? {
        val speed = s.speedKph ?: return null
        if (speed < stopSpeedKph) {
            val since = stoppedSinceMs ?: s.tsMs.also { stoppedSinceMs = it }
            if (!stoppedAnnounced && s.tsMs - since >= stoppedAfterMs) {
                stoppedAnnounced = true
                return DetectedEvent.Stopped(durationS = ((s.tsMs - since) / 1000).toInt())
            }
            return null
        }
        if (speed >= resumeSpeedKph) {
            val since = stoppedSinceMs
            val announced = stoppedAnnounced
            stoppedSinceMs = null
            stoppedAnnounced = false
            if (announced && since != null) return DetectedEvent.Resumed(durationS = ((s.tsMs - since) / 1000).toInt())
        }
        return null
    }

    private fun airbag(s: Sample): DetectedEvent? {
        if (s.airbagDeployed != true || airbagAnnounced) return null
        airbagAnnounced = true
        return DetectedEvent.AirbagDeployed
    }

    private fun arrivalByProximity(s: Sample): DetectedEvent? {
        val lat = s.lat ?: return null
        val lon = s.lon ?: return null
        val speed = s.speedKph ?: 0f
        if (speed >= ARRIVED_MAX_SPEED_KPH) return null
        val d = groupTargets().firstOrNull { keyOf(it) !in announcedArrivals && Geo.distanceM(lat, lon, it.lat, it.lon) <= arrivalRadiusM } ?: return null
        announcedArrivals += keyOf(d)
        return DetectedEvent.DestinationArrived(d.name)
    }

    private companion object {
        const val ARRIVED_MAX_SPEED_KPH = 3f

        /** 좌표 없는 내비 도착(목적지 모름)의 래치 키 */
        const val NO_TARGET_KEY = "navi"
    }
}
