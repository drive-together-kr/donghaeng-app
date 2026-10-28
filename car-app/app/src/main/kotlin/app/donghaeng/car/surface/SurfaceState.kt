package app.donghaeng.car.surface

import app.donghaeng.car.core.common.DhLog
import app.donghaeng.car.domain.session.GroupSession
import app.donghaeng.car.domain.session.ReceivedGroupEvent
import app.donghaeng.car.feature.drive.ContinuousDriveTimer
import app.donghaeng.car.feature.drive.FloatingPanelUi
import app.donghaeng.car.feature.drive.PttUi
import app.donghaeng.protocol.GroupState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * 주행 중 표면의 상태 홀더(싱글턴, docs/05 §5.2). 플로팅 패널 서비스·알림 카드·정차 대시보드·디버그 리시버가 같은 값을 본다.
 *
 * - [mode] = [SurfacePolicy.mode](그룹 세션, 오버레이 권한), [panelVisible] = PANEL 이고 동행 화면이 보이지 않음(started 기준).
 * - [panelUi] = [FloatingPanelMapper] 결과(그룹이 있을 때만, 1초 틱으로 이벤트 10초 창을 갱신, 신선도·나이는 [serverNow] 기준).
 * - [recentEvents] = 앱 시작 이후 받은 그룹 이벤트(실제 수신 시각) — 그룹이 바뀌면 비운다.
 * - [corner] = 패널 모서리(SharedPreferences "dh_surface"), [isDriving] = 드래그 허용 판단(정차 중에만).
 * - [continuousDriveMs] = 시작부터 주행 여부를 따라가는 [ContinuousDriveTimer](대시보드가 안 보이는 주행 중에도 센다).
 */
class SurfaceState(
    private val session: StateFlow<GroupSession?>,
    private val groupState: StateFlow<GroupState?>,
    private val events: Flow<ReceivedGroupEvent>,
    private val myVehicleId: () -> String?,
    private val connected: Flow<Boolean>,
    private val driving: Flow<Boolean>,
    private val canDrawOverlays: StateFlow<Boolean>,
    private val appVisible: StateFlow<Boolean>,
    private val positions: PanelPositionStore,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clock: () -> Long = System::currentTimeMillis,
    /** 서버 시계 기준 지금(ClockSync.serverNow) — 기본 = [clock] */
    private val serverClock: () -> Long = clock,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    /** 연속 주행 타이머용 단조 시계(기기: SystemClock.elapsedRealtime) — 벽시계 보정(NTP·GNSS·수동)에 흔들리지 않게. 기본 = [clock] */
    private val driveClock: () -> Long = clock,
    private val tickMs: Long = 1_000L,
) : SurfaceFeed {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var job: Job? = null

    private val _mode = MutableStateFlow(SurfaceMode.OFF)
    override val mode: StateFlow<SurfaceMode> = _mode.asStateFlow()

    private val _panelVisible = MutableStateFlow(false)
    val panelVisible: StateFlow<Boolean> = _panelVisible.asStateFlow()

    private val _panelUi = MutableStateFlow<FloatingPanelUi?>(null)
    val panelUi: StateFlow<FloatingPanelUi?> = _panelUi.asStateFlow()

    private val _recent = MutableStateFlow<List<ReceivedGroupEvent>>(emptyList())
    override val recentEvents: StateFlow<List<ReceivedGroupEvent>> = _recent.asStateFlow()

    private val _isDriving = MutableStateFlow(false)
    val isDriving: StateFlow<Boolean> = _isDriving.asStateFlow()

    private val _connected = MutableStateFlow(false)

    private val _corner = MutableStateFlow(PanelCorner.TOP_END)
    val corner: StateFlow<PanelCorner> = _corner.asStateFlow()

    private val driveTimer = ContinuousDriveTimer()

    /** 무전 상태(F16 다음 작업이 채운다) — 지금은 항상 IDLE */
    val ptt = MutableStateFlow(PttUi())

    @OptIn(ExperimentalCoroutinesApi::class)
    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        _corner.value = runCatching { positions.load() }.getOrDefault(PanelCorner.TOP_END)
        job = scope.launch {
            launch { combine(session, canDrawOverlays) { s, c -> SurfacePolicy.mode(s != null, c) }.collect { _mode.value = it } }
            launch { combine(_mode, appVisible) { m, v -> SurfacePolicy.panelVisible(m, v) }.collect { _panelVisible.value = it } }
            launch { driving.collect { _isDriving.value = it; driveTimer.onDriving(it, driveClock()) } }
            launch { connected.collect { _connected.value = it } }
            launch { session.map { it?.groupId }.distinctUntilChanged().collect { _recent.value = emptyList() } }
            launch { events.collect { noteEvent(it) } }
            launch {
                session.flatMapLatest { s ->
                    if (s == null) flowOf(null)
                    else combine(groupState, _recent, _connected, ptt, ticker()) { g, ev, c, p, now ->
                        FloatingPanelMapper.map(s, g, myVehicleId(), ev, p, c, nowMs = now, zone = zone(), serverNowMs = serverClock())
                    }
                }.collect { _panelUi.value = it }
            }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel(); job = null
    }

    /** 이벤트 한 건 기록(디버그 리시버도 부른다) — 최신 먼저, eventId 중복 제거 */
    fun noteEvent(e: ReceivedGroupEvent) {
        _recent.update { (listOf(e) + it).distinctBy { r -> r.event.eventId }.take(MAX_EVENTS) }
    }

    override fun serverNow(): Long = serverClock()

    override fun continuousDriveMs(): Long? = if (job?.isActive == true) driveTimer.drivenMs(driveClock()) else null

    fun setCorner(corner: PanelCorner) {
        positions.save(corner)
        _corner.value = corner
        DhLog.i("surface: panel corner=$corner (saved)")
    }

    /** 진단·디버그 한 줄(민감 정보 없음) */
    fun describe(): String =
        "mode=${_mode.value} panelVisible=${_panelVisible.value} canDraw=${canDrawOverlays.value} appVisible=${appVisible.value} " +
            "driving=${_isDriving.value} corner=${_corner.value} cars=${_panelUi.value?.cars?.size ?: 0} more=${_panelUi.value?.moreCount ?: 0} " +
            "event=${_panelUi.value?.event != null} recent=${_recent.value.size}"

    private fun ticker(): Flow<Long> = flow {
        while (true) {
            emit(clock())
            delay(tickMs)
        }
    }

    companion object {
        const val MAX_EVENTS = 20
    }
}
