package app.donghaeng.car.surface

import app.donghaeng.car.core.common.DhLog
import app.donghaeng.car.domain.session.GroupSession
import app.donghaeng.car.domain.session.ReceivedGroupEvent
import app.donghaeng.car.domain.voice.VoiceCue
import app.donghaeng.car.feature.drive.ContinuousDriveTimer
import app.donghaeng.car.feature.drive.EventBannerUi
import app.donghaeng.car.feature.drive.EventHighlight
import app.donghaeng.car.feature.drive.FloatingPanelUi
import app.donghaeng.car.feature.drive.PttUi
import app.donghaeng.protocol.EventAudience
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
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * 주행 중 표면의 상태 홀더(싱글턴, docs/05 §5.2). 플로팅 패널 서비스·알림 카드·정차 대시보드·디버그 리시버가 같은 값을 본다.
 *
 * - [mode] = [SurfacePolicy.mode](그룹 세션, 오버레이 권한), [panelVisible] = PANEL 이고 동행 화면이 보이지 않음(started 기준).
 * - [panelUi] = [FloatingPanelMapper] 결과(그룹이 있을 때만, 1초 틱으로 이벤트 10초 창을 갱신, 신선도·나이는 [serverNow] 기준).
 * - [recentEvents] = 앱 시작 이후 받은 그룹 이벤트(실제 수신 시각) 중 **내 차가 대상인 것**([EventAudience] — 급제동은 출처보다 뒤에 있는 차만,
 *   audience 가 없으면 받는 순간의 스냅샷으로 앞뒤 판정) — 그룹이 바뀌면 비운다. 패널 소식 줄·정차 대시보드 띠·지난 소식이 모두 여기서 나온다.
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
    /** 음성 경로·지연·시작 신호(VoiceCueTracker) — 위험 이벤트 강조. 바뀌면 틱을 기다리지 않고 패널을 다시 그린다 */
    private val voiceCues: StateFlow<VoiceCue> = MutableStateFlow(VoiceCue()),
    /**
     * 그룹 상태를 실제로 받은 시각(기기 벽시계 — `MqttSessionManager.groupStateAt`, 정차 대시보드의 "N분 전"과 같은 값). 패널 '연결 끊김 · N분 전'의 나이를
     * 이것으로 잰다. null 이면(아직 연결 안 함·테스트) 패널이 그 스냅샷을 처음 본 시각으로 대신한다
     */
    private val groupStateAt: StateFlow<Long?> = MutableStateFlow(null),
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

    /**
     * 무전 상태(F16) — `PttRuntime` 이 보내기 상태 기계(PttController) + 받은 무전 재생(VoicePlayback)을 `PttUiMapper` 로 합쳐 넣는다.
     * 패널([panelUi])과 정차 대시보드가 같은 값을 본다.
     */
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
            launch { session.map { it?.groupId }.distinctUntilChanged().collect { _recent.value = emptyList(); lateAtReceipt.value = emptyMap() } }
            launch { events.collect { noteEvent(it) } }
            launch {
                session.flatMapLatest { s ->
                    if (s == null) flowOf(null)
                    else {
                        // E038: 그룹 상태를 받은 시각 — 연결이 끊겨 스냅샷이 멈추면 나이가 늘어 패널이 '연결 끊김 · N분 전'으로 바뀐다.
                        // 수신 시각([groupStateAt])이 있으면 그것을 쓴다(대시보드와 같은 값 — 세션이 다시 방출돼도 0 으로 돌아가지 않는다).
                        // 없을 때만 이 흐름이 그 스냅샷 객체를 처음 본 시각으로 대신한다
                        var lastState: GroupState? = null
                        var firstSeenMs = clock()
                        val stateWithAt = combine(groupState, groupStateAt) { g, at -> g to at }
                        combine(stateWithAt, _recent, _connected, ptt, ticker()) { (g, at), ev, c, p, now ->
                            if (g !== lastState) { lastState = g; firstSeenMs = now }
                            FloatingPanelMapper.map(
                                s, g, myVehicleId(), ev, p, c, nowMs = now, zone = zone(), serverNowMs = serverClock(), voiceCue = voiceCue(),
                                stateAgeMs = stateAgeMs(g, at, firstSeenMs, now),
                            )
                        }
                    }
                }.collect { ui -> logHighlight(ui?.event); _panelUi.value = ui }
            }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel(); job = null
    }

    /**
     * 이벤트 한 건 기록(디버그 리시버도 부른다) — 최신 먼저, eventId 중복 제거. 내 차가 대상이 아니면([EventAudience.decide]) 버린다.
     * 앞뒤 판정은 받는 순간의 스냅샷으로 한 번만 한다(나중에 차 순서가 바뀌어도 목록이 흔들리지 않게). 기록했으면 true
     */
    fun noteEvent(e: ReceivedGroupEvent): Boolean {
        val audience = EventAudience.decide(e.event, myVehicleId(), groupState.value?.members)
        if (!audience.forMe) {
            DhLog.i("surface: skip not-for-me ${e.event.type} basis=${audience.basis}")
            return false
        }
        // 위험 이벤트 강조의 '늦을 게 분명'은 받은 순간 한 번만 정한다(EventHighlightPolicy.noteReceipt) — 목록보다 먼저 넣어 첫 그림부터 쓰게
        lateAtReceipt.update { EventHighlightPolicy.noteReceipt(it, e.event.type, e.event.eventId, voiceCues.value) }
        _recent.update { (listOf(e) + it).distinctBy { r -> r.event.eventId }.take(MAX_EVENTS) }
        return true
    }

    override fun serverNow(): Long = serverClock()

    /** 위험 이벤트 eventId → 받은 순간 '늦을 게 분명'이었나 */
    private val lateAtReceipt = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    /** 음성 값 + 받은 순간 판정 — 패널 소식 줄·정차 대시보드 띠가 같은 값을 본다 */
    override fun voiceCue(): VoiceCue = voiceCues.value.copy(lateAtReceipt = lateAtReceipt.value)

    private var lastHighlight: EventHighlight = EventHighlight.NONE

    /** 위험 이벤트 강조가 바뀔 때만 한 줄 — `surface: event highlight BLINK|STEADY|NONE tone=… age=…s path=… avg=…ms` (문구·닉네임 없음) */
    private fun logHighlight(e: EventBannerUi?) {
        val h = e?.highlight ?: EventHighlight.NONE
        if (h == lastHighlight) return
        lastHighlight = h
        val cue = voiceCues.value
        DhLog.i("surface: event highlight $h tone=${e?.tone} age=${e?.ageS ?: "-"}s path=${cue.path ?: "-"} avg=${cue.avgLatencyMs ?: "-"}ms")
    }

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
            "event=${_panelUi.value?.event != null} highlight=${_panelUi.value?.event?.highlight ?: EventHighlight.NONE} recent=${_recent.value.size}"

    /** 1초 틱 + 음성 신호가 바뀔 때(위험 이벤트의 음성이 시작되면 깜빡임을 바로 멈추게) */
    private fun ticker(): Flow<Long> = merge(
        flow {
            while (true) {
                emit(clock())
                delay(tickMs)
            }
        },
        voiceCues.drop(1).map { clock() },
    )

    companion object {
        const val MAX_EVENTS = 20

        /** 패널 그룹 상태 나이(E038) — 상태가 없으면 null, 수신 시각이 있으면 그 기준, 없으면 처음 본 시각 기준. 음수는 0 */
        fun stateAgeMs(state: GroupState?, receivedAtMs: Long?, firstSeenMs: Long, nowMs: Long): Long? {
            if (state == null) return null
            return (nowMs - (receivedAtMs ?: firstSeenMs)).coerceAtLeast(0)
        }
    }
}
