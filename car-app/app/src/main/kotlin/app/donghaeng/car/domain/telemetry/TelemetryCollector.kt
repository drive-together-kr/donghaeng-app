package app.donghaeng.car.domain.telemetry

import app.donghaeng.car.core.common.CoordinateMasking
import app.donghaeng.car.core.common.DhLog
import app.donghaeng.car.domain.model.Fix
import app.donghaeng.car.domain.model.Gear
import app.donghaeng.car.domain.navi.NaviMonitorState
import app.donghaeng.car.domain.session.GroupSession
import app.donghaeng.car.domain.vehicle.VehicleSignalState
import app.donghaeng.protocol.Energy
import app.donghaeng.protocol.Eta
import app.donghaeng.protocol.Telemetry
import app.donghaeng.protocol.Topics
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/** 진단용 상태(좌표는 마스킹) */
data class TelemetryState(
    val running: Boolean = false,
    /** 활성 그룹이 있어 발행 중인가 */
    val active: Boolean = false,
    val groupName: String? = null,
    val sentCount: Int = 0,
    val failedCount: Int = 0,
    /** 위치가 없거나 오래돼 건너뛴 틱 */
    val skippedNoFix: Int = 0,
    val lastSentTs: Long? = null,
    val lastSummary: String? = null,
    val etaSentCount: Int = 0,
    val energySentCount: Int = 0,
    val topicMasked: String? = null,
)

/**
 * 텔레메트리 발행기 (T23, docs/03 §5.1): 활성 그룹이 있는 동안 1Hz 로 `dh/v1/vehicle/{vid}/telemetry` 에 QoS 0 발행.
 *
 * - 위치: [latestFix] (LocationFeed 의 최신 Fix). 없거나 [fixMaxAgeMs] 보다 오래됐으면 그 틱은 건너뛴다(가짜 위치를 보내지 않는다).
 * - 속도: 차량 신호(Car API) 가 있으면 그것, 없으면 위치 계산값([fallbackSpeedKph]). 기어: 차량 신호(UNKNOWN 은 null).
 * - ETA: 내비 TripInfo(남은 거리·시간)가 [etaMaxAgeMs] 안에 있으면 **30초마다 또는 변화 시**(거리 ≥ [etaDistanceDeltaM] m 또는 시간 ≥ [etaTimeDeltaS] s) 포함.
 * - 에너지: 연료/SoC 가 **1%p 라도 바뀌면** 포함, 변화가 없어도 [energyResendMs] 마다 한 번 다시 보낸다(서버 재시작 대비).
 * - 발행 실패(연결 없음)는 세지만 재전송하지 않는다(1초 뒤 다음 값이 간다).
 * 의존은 전부 함수/StateFlow 라 단위 테스트에서 가짜로 바꿀 수 있다. 실제 배선은 `di/SignalsModule`.
 */
class TelemetryCollector(
    private val session: StateFlow<GroupSession?>,
    private val vehicleId: () -> String?,
    private val latestFix: () -> Fix?,
    private val fallbackSpeedKph: () -> Float?,
    private val vehicle: StateFlow<VehicleSignalState>,
    private val navi: StateFlow<NaviMonitorState>,
    private val publish: (topic: String, telemetry: Telemetry) -> Boolean,
    private val intervalMs: Long = INTERVAL_MS,
    private val fixMaxAgeMs: Long = FIX_MAX_AGE_MS,
    private val etaIntervalMs: Long = ETA_INTERVAL_MS,
    private val etaMaxAgeMs: Long = ETA_MAX_AGE_MS,
    private val energyResendMs: Long = ENERGY_RESEND_MS,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var job: Job? = null

    private val _state = MutableStateFlow(TelemetryState())
    val state: StateFlow<TelemetryState> = _state

    // 마지막으로 보낸 ETA/에너지 (변화 판정용)
    private var lastEtaSentTs: Long? = null
    private var lastEtaSent: Eta? = null
    private var lastEnergySentTs: Long? = null
    private var lastEnergySent: Energy? = null

    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        _state.update { it.copy(running = true) }
        job = scope.launch {
            session.collectLatest { s ->
                if (s == null) {
                    _state.update { it.copy(active = false, groupName = null) }
                    resetMarkers()
                    return@collectLatest
                }
                DhLog.i("telemetry: 그룹 활성 → 1Hz 발행 시작 (${s.name})")
                _state.update { it.copy(active = true, groupName = s.name) }
                resetMarkers()
                while (isActive) {
                    runCatching { tick() }.onFailure { e -> DhLog.w("telemetry tick 실패 ${e.javaClass.simpleName}: ${e.message}") }
                    delay(intervalMs)
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        _state.update { it.copy(running = false, active = false) }
    }

    private fun resetMarkers() {
        lastEtaSentTs = null
        lastEtaSent = null
        lastEnergySentTs = null
        lastEnergySent = null
    }

    /** 한 틱 — 테스트에서 직접 부를 수 있다 */
    fun tick() {
        val now = clock()
        val vid = vehicleId() ?: run {
            DhLog.d("telemetry: 자격 증명 없음 → 건너뜀")
            return
        }
        val fix = latestFix()
        if (fix == null || now - fix.ts > fixMaxAgeMs) {
            _state.update { it.copy(skippedNoFix = it.skippedNoFix + 1) }
            return
        }
        val v = vehicle.value
        val speed = v.speedKph ?: fallbackSpeedKph()
        val gear = v.gear?.takeIf { it != Gear.UNKNOWN }?.name

        val eta = buildEta(navi.value, now)
        val includeEta = eta != null && (
            lastEtaSentTs == null ||
                now - lastEtaSentTs!! >= etaIntervalMs ||
                etaChanged(lastEtaSent, eta)
            )

        val energy = if (v.fuelPercent != null || v.evSoc != null) Energy(fuelPct = v.fuelPercent, socPct = v.evSoc) else null
        val includeEnergy = energy != null && (
            lastEnergySentTs == null ||
                energy != lastEnergySent ||
                now - lastEnergySentTs!! >= energyResendMs
            )

        val telemetry = Telemetry(
            lat = fix.lat,
            lon = fix.lon,
            heading = fix.heading,
            speedKph = speed,
            locSource = fix.source,
            gear = gear,
            eta = if (includeEta) eta else null,
            energy = if (includeEnergy) energy else null,
        )
        val topic = Topics.vehicleTelemetry(vid)
        val ok = publish(topic, telemetry)
        if (ok) {
            if (includeEta) { lastEtaSentTs = now; lastEtaSent = eta }
            if (includeEnergy) { lastEnergySentTs = now; lastEnergySent = energy }
        }
        val summary = summarize(telemetry)
        _state.update {
            it.copy(
                sentCount = if (ok) it.sentCount + 1 else it.sentCount,
                failedCount = if (ok) it.failedCount else it.failedCount + 1,
                lastSentTs = if (ok) now else it.lastSentTs,
                lastSummary = summary,
                etaSentCount = if (ok && includeEta) it.etaSentCount + 1 else it.etaSentCount,
                energySentCount = if (ok && includeEnergy) it.energySentCount + 1 else it.energySentCount,
                topicMasked = topic.replace(vid, vid.take(4) + "…" + vid.takeLast(2)),
            )
        }
        val n = _state.value.sentCount
        if (ok && (n == 1 || n % LOG_EVERY == 0)) DhLog.i("telemetry #$n $summary")
        if (!ok && _state.value.failedCount % LOG_EVERY == 1) DhLog.w("telemetry publish 실패(연결 없음?) failed=${_state.value.failedCount}")
    }

    private fun buildEta(n: NaviMonitorState, now: Long): Eta? {
        val ts = n.lastTripTs ?: return null
        if (now - ts > etaMaxAgeMs) return null
        if (n.lastRemainDistanceM == null && n.lastRemainTimeS == null) return null
        return Eta(
            destTs = n.lastRemainTimeS?.let { ts + it * 1_000L },
            remainM = n.lastRemainDistanceM,
            remainS = n.lastRemainTimeS,
        )
    }

    private fun etaChanged(prev: Eta?, next: Eta): Boolean {
        if (prev == null) return true
        val dM = if (prev.remainM != null && next.remainM != null) abs(prev.remainM!! - next.remainM!!) else Int.MAX_VALUE
        val dS = if (prev.remainS != null && next.remainS != null) abs(prev.remainS!! - next.remainS!!) else Int.MAX_VALUE
        return dM >= ETA_DISTANCE_DELTA_M || dS >= ETA_TIME_DELTA_S
    }

    private fun summarize(t: Telemetry): String = buildString {
        append(CoordinateMasking.mask(t.lat, t.lon))
        append(" src=").append(t.locSource)
        append(" speed=").append(t.speedKph?.let { "%.1f".format(it) } ?: "?")
        append(" heading=").append(t.heading?.let { "%.0f".format(it) } ?: "?")
        append(" gear=").append(t.gear ?: "-")
        t.eta?.let { append(" eta=").append(it.remainM ?: "?").append("m/").append(it.remainS ?: "?").append("s") }
        t.energy?.let { append(" energy=fuel:").append(it.fuelPct ?: "-").append(" soc:").append(it.socPct ?: "-") }
    }

    companion object {
        const val INTERVAL_MS = 1_000L
        const val FIX_MAX_AGE_MS = 10_000L
        const val ETA_INTERVAL_MS = 30_000L
        const val ETA_MAX_AGE_MS = 120_000L
        const val ENERGY_RESEND_MS = 60_000L
        const val ETA_DISTANCE_DELTA_M = 500
        const val ETA_TIME_DELTA_S = 60
        private const val LOG_EVERY = 10
    }
}
