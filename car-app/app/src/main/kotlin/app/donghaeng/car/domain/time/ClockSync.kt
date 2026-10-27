package app.donghaeng.car.domain.time

import app.donghaeng.car.core.common.DhLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 서버 시계와의 차이. `serverNow = localNow + offsetMs` */
data class ClockSyncState(
    val offsetMs: Long? = null,
    /** 오프셋을 뽑은 표본의 왕복시간 — 오프셋 오차 상한(≈ rtt/2) */
    val rttMs: Long? = null,
    val samples: Int = 0,
    val syncs: Int = 0,
    /** 로컬 시계 기준 */
    val lastSyncAtMs: Long? = null,
    val lastError: String? = null,
) {
    val synced: Boolean get() = offsetMs != null
}

/**
 * 서버 시각 동기화 (T31, docs/05 §3.4).
 *
 * `GET /v1/time` 을 [probes]번 불러 **왕복시간이 가장 짧은 표본**으로 `offset = serverTime - (t0 + rtt/2)` 를 잡는다(NTP 의 단순형).
 * 시작 직후 한 번, 이후 [intervalMs] 마다(실패 시 [retryMs] 뒤) 다시 잰다. 차량 시계가 틀려도(에뮬레이터 실측 1.2~1.9 s 늦음 — 보정 없이는 지연이 음수가 된다) 메시지 지연(ms)과
 * 음성 신선도(docs/05 §6, F12)가 서버 시계 기준으로 계산되게 한다.
 *
 * @param fetchServerTimeMs 서버 시각(ms)을 돌려주는 호출. 실패하면 예외
 */
class ClockSync(
    private val fetchServerTimeMs: suspend () -> Long,
    private val clock: () -> Long = System::currentTimeMillis,
    private val probes: Int = PROBES,
    private val intervalMs: Long = INTERVAL_MS,
    private val retryMs: Long = RETRY_MS,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var job: Job? = null

    private val _state = MutableStateFlow(ClockSyncState())
    val state: StateFlow<ClockSyncState> = _state.asStateFlow()

    /** 서버 시계 기준 지금. 동기화 전이면 로컬 시계 */
    fun serverNow(): Long = clock() + (_state.value.offsetMs ?: 0L)

    /** 서버 ts → 로컬 시계 */
    fun toLocalMs(serverTs: Long): Long = serverTs - (_state.value.offsetMs ?: 0L)

    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                val ok = syncOnce()
                delay(if (ok) intervalMs else retryMs)
            }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel(); job = null
    }

    /** 진단 "지금 동기화" */
    fun resync() {
        scope.launch { syncOnce() }
    }

    /** 한 번 측정. 표본이 하나도 없으면 false */
    suspend fun syncOnce(): Boolean {
        var bestRtt = Long.MAX_VALUE
        var bestOffset = 0L
        var samples = 0
        repeat(probes) {
            val t0 = clock()
            val server = try {
                fetchServerTimeMs()
            } catch (e: Exception) {
                _state.update { it.copy(lastError = e.javaClass.simpleName) }
                return@repeat
            }
            val rtt = (clock() - t0).coerceAtLeast(0)
            val offset = server - (t0 + rtt / 2)
            samples++
            if (rtt < bestRtt) { bestRtt = rtt; bestOffset = offset }
        }
        if (samples == 0) {
            DhLog.w("clock: sync failed (${_state.value.lastError})")
            return false
        }
        _state.update { it.copy(offsetMs = bestOffset, rttMs = bestRtt, samples = it.samples + samples, syncs = it.syncs + 1, lastSyncAtMs = clock(), lastError = null) }
        DhLog.i("clock: offset=${bestOffset}ms rtt=${bestRtt}ms samples=$samples")
        return true
    }

    companion object {
        const val PROBES = 3
        const val INTERVAL_MS = 5 * 60_000L
        const val RETRY_MS = 15_000L
    }
}
