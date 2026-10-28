package app.donghaeng.car.domain.vehicle

import app.donghaeng.car.adapter.vehicle.VehicleAdapter
import app.donghaeng.car.core.common.DhLog
import app.donghaeng.car.domain.model.Gear
import app.donghaeng.car.domain.model.TurnSignalState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 차량 신호 최신값 + 진단용 수신 수 (T22). 값이 null 이면 아직 없음·미지원 */
data class VehicleSignalState(
    val running: Boolean = false,
    val fuelPercent: Int? = null,
    val evSoc: Int? = null,
    val brakePedal: Boolean? = null,
    val turnSignal: TurnSignalState? = null,
    val airbagDeployed: Boolean? = null,
    val speedKph: Float? = null,
    val gear: Gear? = null,
    /** 신호별 수신 수 */
    val updates: Map<String, Int> = emptyMap(),
    /** 신호별 마지막 수신 시각(epoch ms) */
    val lastTs: Map<String, Long> = emptyMap(),
    /** 신호별 스트림 상태: 구독 중 / 끝남(미지원) / 오류 */
    val streams: Map<String, String> = emptyMap(),
    val restarts: Int = 0,
) {
    val totalUpdates: Int get() = updates.values.sum()
}

/**
 * 앱 전체에서 [VehicleAdapter] 의 신호 Flow 들을 한 번만 구독해 최신값을 모은다 (T22).
 *
 * - 소비자: 진단 화면 "차량 신호 (T22)" 카드, T23 TelemetryCollector(연료·SoC·속도·기어), T26 EventDetector(브레이크·속도·저잔량).
 * - 신호마다 독립 코루틴 — 하나가 끝나거나(미지원: 빈 Flow / null 한 번) 예외여도 나머지는 계속된다. 끝난 스트림은 [retryDelayMs] 뒤 다시 구독한다
 *   (권한 승인·서비스 늦은 기동 대비). 미지원이라 매번 바로 끝나는 스트림은 재시도 간격이 길어 부담이 없다.
 * - 로그: 값이 **바뀔 때만** 한 줄(`vehicle signal fuel=61`). 1Hz 로 같은 값이 와도 조용하다.
 */
class VehicleSignals(
    private val adapter: VehicleAdapter,
    private val retryDelayMs: Long = RETRY_DELAY_MS,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var jobs: List<Job> = emptyList()

    private val _state = MutableStateFlow(VehicleSignalState())
    val state: StateFlow<VehicleSignalState> = _state

    @Synchronized
    fun start() {
        if (jobs.any { it.isActive }) return
        _state.update { it.copy(running = true) }
        jobs = listOf(
            track("fuel", adapter.fuelPercent()) { s, v -> s.copy(fuelPercent = v) },
            track("soc", adapter.evStateOfCharge()) { s, v -> s.copy(evSoc = v) },
            track("brake", adapter.brakePedal()) { s, v -> s.copy(brakePedal = v) },
            track("turn", adapter.turnSignal()) { s, v -> s.copy(turnSignal = v) },
            track("airbag", adapter.airbagDeployed()) { s, v -> s.copy(airbagDeployed = v) },
            track("speed", adapter.speedKph()) { s, v -> s.copy(speedKph = v) },
            track("gear", adapter.gear()) { s, v -> s.copy(gear = v) },
        )
    }

    @Synchronized
    fun stop() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        _state.update { it.copy(running = false) }
    }

    /** 권한 승인 뒤 등 — 전부 다시 구독 */
    fun restart() {
        DhLog.i("vehicle signals: restart")
        stop()
        _state.update { it.copy(restarts = it.restarts + 1) }
        start()
    }

    private fun <T> track(name: String, flow: Flow<T>, apply: (VehicleSignalState, T) -> VehicleSignalState): Job = scope.launch {
        var last: Any? = NONE
        while (isActive) {
            _state.update { it.copy(streams = it.streams + (name to "구독 중")) }
            try {
                flow.collect { value ->
                    if (value != last) {
                        last = value
                        // 속도처럼 초당 여러 번 바뀌는 값 — logcat 에만(T33 링 버퍼·파일을 밀어내지 않게)
                        DhLog.i("vehicle signal $name=$value", keep = false)
                    }
                    _state.update { s ->
                        apply(s, value).copy(
                            updates = s.updates + (name to (s.updates[name] ?: 0) + 1),
                            lastTs = s.lastTs + (name to clock()),
                        )
                    }
                }
                _state.update { it.copy(streams = it.streams + (name to "끝남(미지원·빈 스트림) — ${retryDelayMs / 1000}s 뒤 재시도")) }
                DhLog.d("vehicle signals: $name stream completed")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(streams = it.streams + (name to "오류 ${e.javaClass.simpleName} — 재시도")) }
                DhLog.w("vehicle signals: $name failed ${e.javaClass.simpleName}: ${e.message}")
            }
            delay(retryDelayMs)
        }
    }

    private companion object {
        const val RETRY_DELAY_MS = 30_000L
        val NONE = Any()
    }
}
