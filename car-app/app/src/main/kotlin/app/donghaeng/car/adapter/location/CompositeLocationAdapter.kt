package app.donghaeng.car.adapter.location

import app.donghaeng.car.core.common.DhLog
import app.donghaeng.car.domain.model.Fix
import app.donghaeng.protocol.LocSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** 진단용 체인 상태(좌표 없음) */
data class LocationChainStatus(
    val active: LocSource? = null,
    /** 소스 전환 횟수(처음 선택 포함) */
    val switches: Int = 0,
    val fixCount: Int = 0,
    val lastFixTs: Long? = null,
    /** 마지막 탐색에서 본 소스별 가용성 */
    val availability: Map<LocSource, Boolean> = emptyMap(),
    val lastStop: String? = null,
    val lastError: String? = null,
)

/**
 * 위치 소스 우선순위 체인 (T21, docs/05 §3.2).
 *
 * 1. 순서대로 [LocationSource.isAvailable] 인 첫 소스를 구독한다.
 * 2. 첫 Fix 가 [firstFixTimeoutMs] 안에 안 오거나, 이후 [LocationSource.staleTimeoutMs] 동안 끊기거나, 스트림이 끝나거나 예외면 다음 순위로 넘어간다.
 *    마지막 소스(Fake)까지 전부 실패하면 [retryDelayMs] 뒤 처음부터 다시.
 * 3. 하위 소스를 쓰는 동안에는 [reprobeIntervalMs] 마다 상위 소스를 **병렬로** 짧게 구독해 보고, Fix 가 실제로 오면 그쪽으로 올라간다
 *    (탐색 중에도 현재 소스의 Fix 는 계속 흘러 1Hz 스트림에 구멍이 나지 않는다).
 *
 * 발행되는 [Fix.source] 는 실제 소스 값이므로 텔레메트리에 그대로 실린다.
 */
class CompositeLocationAdapter(
    private val sources: List<LocationSource>,
    private val firstFixTimeoutMs: Long = 10_000L,
    private val reprobeIntervalMs: Long = 60_000L,
    private val retryDelayMs: Long = 5_000L,
) : LocationAdapter {

    init {
        require(sources.isNotEmpty()) { "위치 소스가 하나는 있어야 한다" }
    }

    private val _status = MutableStateFlow(LocationChainStatus())
    val status: StateFlow<LocationChainStatus> = _status

    val sourceOrder: List<LocSource> get() = sources.map { it.source }

    override fun locations(): Flow<Fix> = channelFlow {
        var index = 0
        while (isActive) {
            val src = sources[index]
            val available = runCatching { src.isAvailable() }.getOrElse { e ->
                DhLog.w("location: ${src.source} isAvailable 실패 ${e.javaClass.simpleName}")
                false
            }
            _status.update { it.copy(availability = it.availability + (src.source to available)) }
            if (!available) {
                DhLog.i("location: ${src.source} 사용 불가 → 다음 순위")
                index = if (index < sources.lastIndex) index + 1 else {
                    _status.update { it.copy(active = null, lastStop = "all unavailable") }
                    delay(retryDelayMs)
                    0
                }
                continue
            }
            _status.update { it.copy(active = src.source, switches = it.switches + 1) }
            DhLog.i("location: 활성 소스 = ${src.source} (${index + 1}/${sources.size})")
            val stop = collectFrom(src, index)
            DhLog.i("location: ${src.source} 중단 — $stop")
            _status.update { it.copy(lastStop = "${src.source}: $stop") }
            index = when (stop) {
                is Stop.SwitchTo -> stop.index
                else -> if (index < sources.lastIndex) index + 1 else {
                    _status.update { it.copy(active = null) }
                    delay(retryDelayMs)
                    0
                }
            }
        }
    }

    private sealed class Stop {
        object NoFirstFix : Stop() { override fun toString() = "no first fix" }
        object Stale : Stop() { override fun toString() = "stale" }
        object Completed : Stop() { override fun toString() = "completed" }
        data class Failed(val error: String) : Stop() { override fun toString() = "failed $error" }
        data class SwitchTo(val index: Int) : Stop() { override fun toString() = "switch to higher #$index" }
    }

    /** 상위 소스 탐색 성공 시 현재 업스트림을 이 예외로 취소한다 */
    private class SwitchSignal(val index: Int) : CancellationException("switch to higher source $index")

    private suspend fun ProducerScope<Fix>.collectFrom(src: LocationSource, index: Int): Stop = coroutineScope {
        var error: String? = null
        val upstream = src.locations()
            .catch { e ->
                error = "${e.javaClass.simpleName}: ${e.message}"
                DhLog.w("location: ${src.source} 스트림 오류 ${e.javaClass.simpleName}")
            }
            .produceIn(this)
        // 하위 소스 사용 중이면 주기적으로 상위 소스를 병렬 탐색
        val prober = if (index > 0) launch {
            while (true) {
                delay(reprobeIntervalMs)
                val higher = probeHigher(index)
                if (higher != null) {
                    upstream.cancel(SwitchSignal(higher))
                    break
                }
            }
        } else null

        var gotAny = false
        var timeout = firstFixTimeoutMs
        val stop: Stop
        try {
            while (true) {
                val r = withTimeoutOrNull(timeout) { upstream.receiveCatching() }
                if (r == null) {
                    stop = if (gotAny) Stop.Stale else Stop.NoFirstFix
                    break
                }
                if (r.isClosed) {
                    val cause = r.exceptionOrNull()
                    stop = when {
                        cause is SwitchSignal -> Stop.SwitchTo(cause.index)
                        error != null -> Stop.Failed(error!!)
                        else -> Stop.Completed
                    }
                    break
                }
                val fix = r.getOrThrow()
                gotAny = true
                timeout = src.staleTimeoutMs
                send(fix)
                _status.update { it.copy(fixCount = it.fixCount + 1, lastFixTs = fix.ts) }
            }
        } finally {
            prober?.cancel()
            upstream.cancel()
        }
        if (stop is Stop.Failed) _status.update { it.copy(lastError = stop.error) }
        stop
    }

    /** index 보다 상위 소스 중 실제로 Fix 를 주는 첫 번째의 인덱스. 없으면 null */
    private suspend fun probeHigher(index: Int): Int? {
        for (j in 0 until index) {
            val s = sources[j]
            val available = runCatching { s.isAvailable() }.getOrDefault(false)
            _status.update { it.copy(availability = it.availability + (s.source to available)) }
            if (!available) continue
            val fix = runCatching { withTimeoutOrNull(firstFixTimeoutMs) { s.locations().first() } }.getOrNull()
            if (fix != null) {
                DhLog.i("location: 상위 소스 ${s.source} 복구 감지 → 전환")
                return j
            }
        }
        return null
    }

}
