package app.donghaeng.car.domain.location

import android.content.Context
import app.donghaeng.car.adapter.location.CompositeLocationAdapter
import app.donghaeng.car.adapter.location.LocationAdapter
import app.donghaeng.car.adapter.location.LocationChainStatus
import app.donghaeng.car.core.common.CoordinateMasking
import app.donghaeng.car.core.common.DhLog
import app.donghaeng.car.core.common.LocationPermissions
import app.donghaeng.car.domain.model.Fix
import app.donghaeng.protocol.LocSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** 내 최신 위치를 구독하는 창구 — 화면·수집기가 [LocationFeed] 대신 이 인터페이스에 의존하면 테스트에서 바꿔 끼울 수 있다 (T25) */
interface MyFixSource {
    val fixes: SharedFlow<Fix>
}

/** 진단용 위치 피드 상태(좌표는 마스킹 문자열만) */
data class LocationFeedState(
    val running: Boolean = false,
    val chain: LocationChainStatus = LocationChainStatus(),
    val sourceOrder: List<LocSource> = emptyList(),
    val lastFixMasked: String? = null,
    val lastHeading: Float? = null,
    val lastAccuracy: Float? = null,
    val lastFixTs: Long? = null,
    val lastSource: LocSource? = null,
    /** 연속 위치로 계산한 속도(km/h). 샘플 부족이면 null */
    val speedKph: Float? = null,
    val fineLocationGranted: Boolean = false,
    val fusedLocationGranted: Boolean = false,
    val notificationsGranted: Boolean = false,
    val restarts: Int = 0,
    val lastError: String? = null,
)

/**
 * 앱 전체의 위치 1Hz 스트림 (T21). [LocationAdapter](체인)를 한 번만 구독해 [fixes] 로 나눠 주고, [SpeedEstimator] 로 속도를 붙인다.
 *
 * - 소비자: 진단 화면([state]), T23 TelemetryCollector([fixes]·[speedKph]), T26 EventDetector.
 * - 권한이 바뀌면([restart]) 체인을 처음부터 다시 탐색한다. 스트림이 끝나거나 예외면 [restartDelayMs] 뒤 재구독.
 * - 로그: 소스 전환은 체인이, 여기서는 Fix [LOG_EVERY_FIXES] 개마다 한 줄(마스킹) + 속도.
 */
@Singleton
class LocationFeed @Inject constructor(
    private val adapter: LocationAdapter,
    @ApplicationContext private val context: Context,
) : MyFixSource {
    private var dispatcher: CoroutineDispatcher = Dispatchers.Default
    private var restartDelayMs: Long = RESTART_DELAY_MS
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private val estimator = SpeedEstimator()

    private val _fixes = MutableSharedFlow<Fix>(replay = 1, extraBufferCapacity = 16)

    /** 최신 Fix 1개 replay. 느린 구독자가 있으면 오래된 것부터 버린다 */
    override val fixes: SharedFlow<Fix> = _fixes

    private val _speedKph = MutableStateFlow<Float?>(null)
    val speedKph: StateFlow<Float?> = _speedKph

    private val _local = MutableStateFlow(LocationFeedState())

    /** 체인 상태 + 피드 상태 합본 */
    val state: StateFlow<LocationFeedState> = combine(
        _local,
        (adapter as? CompositeLocationAdapter)?.status ?: flowOf(LocationChainStatus()),
    ) { local, chain -> local.copy(chain = chain) }
        .stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, LocationFeedState())

    fun configureForTest(dispatcher: CoroutineDispatcher, restartDelayMs: Long) {
        this.dispatcher = dispatcher
        this.restartDelayMs = restartDelayMs
    }

    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        refreshPermissions()
        _local.update { it.copy(sourceOrder = (adapter as? CompositeLocationAdapter)?.sourceOrder ?: emptyList()) }
        job = scope.launch(dispatcher) {
            var first = true
            while (isActive) {
                if (!first) {
                    _local.update { it.copy(running = false, restarts = it.restarts + 1) }
                    delay(restartDelayMs)
                }
                first = false
                try {
                    DhLog.i("location feed: subscribe")
                    _local.update { it.copy(running = true) }
                    adapter.locations().collect { onFix(it) }
                    DhLog.w("location feed: stream completed — 재구독 예정")
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DhLog.w("location feed: stream failed ${e.javaClass.simpleName}: ${e.message} — 재구독 예정")
                    _local.update { it.copy(lastError = "${e.javaClass.simpleName}: ${e.message}") }
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        estimator.reset()
        _speedKph.value = null
        _local.update { it.copy(running = false) }
    }

    /** 권한 승인 뒤 등 — 체인을 처음부터 다시 탐색 */
    fun restart() {
        DhLog.i("location feed: restart")
        stop()
        start()
    }

    fun refreshPermissions() {
        _local.update {
            it.copy(
                fineLocationGranted = LocationPermissions.fineGranted(context),
                fusedLocationGranted = LocationPermissions.fusedGranted(context),
                notificationsGranted = LocationPermissions.notificationsGranted(context),
            )
        }
    }

    private var fixCount = 0

    private fun onFix(fix: Fix) {
        if (_local.value.lastSource != null && _local.value.lastSource != fix.source) estimator.reset()
        val speed = estimator.push(fix)
        _speedKph.value = speed
        fixCount++
        _local.update {
            it.copy(
                lastFixMasked = CoordinateMasking.mask(fix.lat, fix.lon),
                lastHeading = fix.heading,
                lastAccuracy = fix.accuracy,
                lastFixTs = fix.ts,
                lastSource = fix.source,
                speedKph = speed,
            )
        }
        if (fixCount == 1 || fixCount % LOG_EVERY_FIXES == 0) {
            DhLog.i("location feed: fix #$fixCount ${fix.toLogString()} speed=${speed?.let { "%.1f".format(it) } ?: "?"}km/h")
        }
        _fixes.tryEmit(fix)
    }

    private companion object {
        const val RESTART_DELAY_MS = 5_000L
        const val LOG_EVERY_FIXES = 10
    }
}
