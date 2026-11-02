package app.donghaeng.car.domain.event

import app.donghaeng.car.core.common.DhLog
import app.donghaeng.car.core.common.TextMasking
import app.donghaeng.car.domain.model.Fix
import app.donghaeng.car.domain.model.Place
import kotlinx.coroutines.flow.distinctUntilChangedBy
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
    /** 따라간 선두 경로의 목적지(RouteSyncEngine.state.followedDestination) — 도착 판정 대상에 더한다(A005) */
    private val followedDestination: () -> Place? = { null },
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var job: Job? = null

    /**
     * A059: [EventDetector] 는 동기화되지 않은 상태를 가진다 — 내비 수집 코루틴과 1Hz 틱(Dispatchers.Default 의 다른 스레드)이
     * 같은 순간 '도착 미알림'을 읽고 둘 다 발행하지 않게 판정기 접근을 모두 이 잠금 안에서 한다(suspend 없음).
     */
    private val detectorLock = Any()

    private val _state = MutableStateFlow(EventEngineState())
    val state: StateFlow<EventEngineState> = _state

    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        _state.update { it.copy(running = true) }
        job = scope.launch {
            launch {
                naviEvents.collect { e ->
                    synchronized(detectorLock) {
                        if (session.value == null) return@collect
                        detector.setFollowedDestination(followedDestination())
                        detector.onNavi(e).forEach { emit(it) }
                    }
                }
            }
            var lastGroupId: String? = null
            // A054: 같은 그룹·같은 목적지의 세션 갱신(초대 재발급·같은 GROUP_JOINED 재수신)으로는 판정을 다시 시작하지 않는다 —
            // 예전엔 resetForNewGroup 이 '도착 알림'을 지워 도착해 서 있던 차가 DESTINATION_ARRIVED 를 다시 보냈다
            session.distinctUntilChangedBy { s -> s?.let { it.groupId to (it.destination.lat to it.destination.lon) } }.collectLatest { s ->
                if (s == null) {
                    _state.update { it.copy(active = false) }
                    synchronized(detectorLock) { detector.setDestination(null) }
                    lastGroupId = null
                    return@collectLatest
                }
                // 규칙 8: 그룹 이름(기본값에 방장 닉네임이 들어간다)은 남기지 않는다(A090)
                DhLog.i("event engine: 그룹 활성 → 판정 시작 (groupId=${TextMasking.maskVehicleId(s.groupId)} nameLen=${s.name.length})")
                synchronized(detectorLock) {
                    if (s.groupId != lastGroupId) detector.resetForNewGroup()
                    detector.setDestination(s.destination)
                }
                lastGroupId = s.groupId
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

    /** 한 틱 — 테스트에서 직접 부를 수 있다. 판정기 접근은 [detectorLock] 안에서(A059) */
    fun tick() = synchronized(detectorLock) {
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
        detector.setFollowedDestination(followedDestination())
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
