package app.donghaeng.car.surface

import app.donghaeng.car.core.common.DhLog
import app.donghaeng.car.domain.session.GroupSession
import app.donghaeng.car.domain.session.ReceivedGroupEvent
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
 * - [mode] = [SurfacePolicy.mode](그룹 세션, 오버레이 권한), [panelVisible] = PANEL 이고 동행 화면이 전면이 아님.
 * - [panelUi] = [FloatingPanelMapper] 결과(그룹이 있을 때만, 1초 틱으로 이벤트 10초 창을 갱신).
 * - [recentEvents] = 앱 시작 이후 받은 그룹 이벤트(실제 수신 시각) — 그룹이 바뀌면 비운다.
 * - [corner] = 패널 모서리(SharedPreferences "dh_surface"), [isDriving] = 드래그 허용 판단(정차 중에만).
 */
class SurfaceState(
    private val session: StateFlow<GroupSession?>,
    private val groupState: StateFlow<GroupState?>,
    private val events: Flow<ReceivedGroupEvent>,
    private val myVehicleId: () -> String?,
    private val connected: Flow<Boolean>,
    private val driving: Flow<Boolean>,
    private val canDrawOverlays: StateFlow<Boolean>,
    private val appResumed: StateFlow<Boolean>,
    private val positions: PanelPositionStore,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
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

    /** 무전 상태(F16 다음 작업이 채운다) — 지금은 항상 IDLE */
    val ptt = MutableStateFlow(PttUi())

    @OptIn(ExperimentalCoroutinesApi::class)
    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        _corner.value = runCatching { positions.load() }.getOrDefault(PanelCorner.TOP_END)
        job = scope.launch {
            launch { combine(session, canDrawOverlays) { s, c -> SurfacePolicy.mode(s != null, c) }.collect { _mode.value = it } }
            launch { combine(_mode, appResumed) { m, r -> SurfacePolicy.panelVisible(m, r) }.collect { _panelVisible.value = it } }
            launch { driving.collect { _isDriving.value = it } }
            launch { connected.collect { _connected.value = it } }
            launch { session.map { it?.groupId }.distinctUntilChanged().collect { _recent.value = emptyList() } }
            launch { events.collect { noteEvent(it) } }
            launch {
                session.flatMapLatest { s ->
                    if (s == null) flowOf(null)
                    else combine(groupState, _recent, _connected, ptt, ticker()) { g, ev, c, p, now ->
                        FloatingPanelMapper.map(s, g, myVehicleId(), ev, p, c, now, zone())
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

    fun setCorner(corner: PanelCorner) {
        positions.save(corner)
        _corner.value = corner
        DhLog.i("surface: panel corner=$corner (saved)")
    }

    /** 진단·디버그 한 줄(민감 정보 없음) */
    fun describe(): String =
        "mode=${_mode.value} panelVisible=${_panelVisible.value} canDraw=${canDrawOverlays.value} appResumed=${appResumed.value} " +
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
