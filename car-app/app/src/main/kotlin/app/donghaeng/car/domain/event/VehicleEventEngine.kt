package app.donghaeng.car.domain.event

import app.donghaeng.car.core.common.DhLog
import app.donghaeng.car.domain.model.Fix
import app.donghaeng.car.domain.model.NaviEvent
import app.donghaeng.car.domain.session.GroupSession
import app.donghaeng.car.domain.vehicle.VehicleSignalState
import app.donghaeng.protocol.AirbagDeployed
import app.donghaeng.protocol.DestinationArrived
import app.donghaeng.protocol.DhJson
import app.donghaeng.protocol.HardBrake
import app.donghaeng.protocol.LowEnergy
import app.donghaeng.protocol.Resumed
import app.donghaeng.protocol.Stopped
import app.donghaeng.protocol.Topics
import app.donghaeng.protocol.WaypointArrived
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** 진단용 상태 */
data class EventEngineState(
    val running: Boolean = false,
    val active: Boolean = false,
    val detectedCounts: Map<String, Int> = emptyMap(),
    val publishedCount: Int = 0,
    val failedCount: Int = 0,
    val lastEvent: String? = null,
    val lastEventTs: Long? = null,
    /** 판정기 내부 상태 요약 */
    val energyAlertPct: Int? = null,
    val stoppedForS: Long? = null,
    val samples: Int = 0,
) {
    val totalDetected: Int get() = detectedCounts.values.sum()
}

/**
 * 차량 이벤트 엔진 (T26): 활성 그룹 동안 1Hz 로 [EventDetector] 에 샘플(차량 신호 + 위치)을 넣고, 내비 도착 이벤트를 넘겨,
 * 판정된 이벤트를 `dh/v1/vehicle/{vid}/event` 에 **QoS 1** 로 발행한다(봉투 type = 이벤트 종류, docs/03 §5.2).
 * 발행 실패(연결 없음)는 세기만 한다 — 서버가 중복 억제(120s)를 하므로 재전송보다 다음 판정을 기다리는 편이 안전하다.
 * 의존은 함수/StateFlow(`di/SignalsModule` 배선) → JVM 단위 테스트 가능.
 */
class VehicleEventEngine(
    private val session: StateFlow<GroupSession?>,
    private val vehicleId: () -> String?,
    private val latestFix: () -> Fix?,
    private val fallbackSpeedKph: () -> Float?,
    private val vehicle: StateFlow<VehicleSignalState>,
    private val naviEvents: Flow<NaviEvent>,
    private val publish: (topic: String, type: String, payload: JsonObject) -> Boolean,
    private val detector: EventDetector = EventDetector(),
    private val intervalMs: Long = 1_000L,
    private val fixMaxAgeMs: Long = 10_000L,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var job: Job? = null

    private val _state = MutableStateFlow(EventEngineState())
    val state: StateFlow<EventEngineState> = _state

    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        _state.update { it.copy(running = true) }
        job = scope.launch {
            launch {
                naviEvents.collect { e ->
                    if (session.value == null) return@collect
                    detector.onNavi(e).forEach { emit(it) }
                }
            }
            session.collectLatest { s ->
                if (s == null) {
                    _state.update { it.copy(active = false) }
                    detector.setDestination(null)
                    return@collectLatest
                }
                DhLog.i("event engine: 그룹 활성 → 판정 시작 (${s.name})")
                detector.resetForNewGroup()
                detector.setDestination(s.destination)
                _state.update { it.copy(active = true) }
                while (isActive) {
                    runCatching { tick() }.onFailure { e -> DhLog.w("event engine tick 실패 ${e.javaClass.simpleName}: ${e.message}") }
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

    /** 한 틱 — 테스트에서 직접 부를 수 있다 */
    fun tick() {
        val now = clock()
        val v = vehicle.value
        val fix = latestFix()?.takeIf { now - it.ts <= fixMaxAgeMs }
        val sample = Sample(
            tsMs = now,
            speedKph = v.speedKph ?: fallbackSpeedKph(),
            brakePressed = v.brakePedal,
            fuelPct = v.fuelPercent,
            socPct = v.evSoc,
            airbagDeployed = v.airbagDeployed,
            lat = fix?.lat,
            lon = fix?.lon,
        )
        val events = detector.onSample(sample)
        _state.update {
            it.copy(
                samples = it.samples + 1,
                energyAlertPct = detector.lastEnergyAlertPct,
                stoppedForS = detector.stoppedSinceMs?.let { since -> (now - since) / 1_000 },
            )
        }
        events.forEach { emit(it) }
    }

    private fun emit(event: DetectedEvent) {
        val vid = vehicleId() ?: run {
            DhLog.w("event engine: 자격 증명 없음 → ${event.type} 버림")
            return
        }
        val payload = event.toPayload()
        val ok = publish(Topics.vehicleEvent(vid), event.type.name, payload)
        val summary = event.toString()
        _state.update {
            it.copy(
                detectedCounts = it.detectedCounts + (event.type.name to (it.detectedCounts[event.type.name] ?: 0) + 1),
                publishedCount = if (ok) it.publishedCount + 1 else it.publishedCount,
                failedCount = if (ok) it.failedCount else it.failedCount + 1,
                lastEvent = summary,
                lastEventTs = clock(),
            )
        }
        DhLog.i("vehicle event ${event.type} $summary published=$ok")
    }

    private fun DetectedEvent.toPayload(): JsonObject = when (this) {
        is DetectedEvent.LowEnergy -> DhJson.json.encodeToJsonElement(LowEnergy.serializer(), LowEnergy(kind, pct))
        is DetectedEvent.HardBrake -> DhJson.json.encodeToJsonElement(HardBrake.serializer(), HardBrake(decelMps2, speedBeforeKph))
        is DetectedEvent.AirbagDeployed -> DhJson.json.encodeToJsonElement(AirbagDeployed.serializer(), AirbagDeployed)
        is DetectedEvent.Stopped -> DhJson.json.encodeToJsonElement(Stopped.serializer(), Stopped(durationS))
        is DetectedEvent.Resumed -> DhJson.json.encodeToJsonElement(Resumed.serializer(), Resumed(durationS))
        is DetectedEvent.WaypointArrived -> DhJson.json.encodeToJsonElement(WaypointArrived.serializer(), WaypointArrived(name, lat, lon))
        is DetectedEvent.DestinationArrived -> DhJson.json.encodeToJsonElement(DestinationArrived.serializer(), DestinationArrived(name))
    }.jsonObject
}
