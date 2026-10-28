package app.donghaeng.car.feature.events

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.donghaeng.car.core.common.DhLog
import app.donghaeng.car.core.network.ApiResult
import app.donghaeng.car.domain.group.GroupRepository
import app.donghaeng.car.domain.session.MqttSessionManager
import app.donghaeng.protocol.EventAudience
import app.donghaeng.protocol.GroupEvent
import app.donghaeng.protocol.GroupEventType
import app.donghaeng.protocol.Severity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

/** 피드 한 줄 */
data class FeedEvent(
    val eventId: String,
    val type: String,
    val typeLabel: String,
    val severity: Severity,
    val speech: String?,
    /** 본문 요약(연료 %·감속도·장소 …) */
    val detail: String?,
    /** MQTT 로 받은 시각(서버 조회분은 null) */
    val receivedAtMs: Long?,
    val ageText: String?,
    /** MQTT 실시간 수신분 */
    val live: Boolean,
)

data class EventsUiState(
    val groupName: String? = null,
    val events: List<FeedEvent> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

/**
 * 이벤트 피드 (T26): `MqttSessionManager.groupEvents`(replay 20) 로 실시간 수신한 것과 `GET /groups/{id}` 의 `recentEvents` 를 eventId 로 합친다.
 * 실시간분이 먼저(최신순), 그 뒤 서버 조회분. 내 차가 대상이 아닌 이벤트([EventAudience] — 뒤차의 급제동 등)는 둘 다에서 뺀다
 * (audience 가 없으면 이 ViewModel 이 받을 때(replay 20 — 화면을 다시 열면 다시 판정)·조회할 때의 스냅샷으로 앞뒤 판정, 모르면 넣는다.
 * `[]`·급제동한 차 자신의 급제동은 여기에도 없다 — 기록은 서버 DB·REST 원문에만, docs/05).
 */
@HiltViewModel
class EventsViewModel @Inject constructor(
    private val sessionManager: MqttSessionManager,
    private val repo: GroupRepository,
) : ViewModel() {

    private data class Live(val event: GroupEvent, val receivedAtMs: Long)

    private val live = MutableStateFlow<List<Live>>(emptyList())
    private val fetched = MutableStateFlow<List<GroupEvent>>(emptyList())
    private val loading = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)
    private var clock: () -> Long = System::currentTimeMillis

    init {
        viewModelScope.launch {
            sessionManager.groupEvents.collect { e ->
                if (!EventAudience.isForMe(e, sessionManager.myVehicleId, sessionManager.groupState.value?.members)) return@collect
                live.update { (listOf(Live(e, clock())) + it).distinctBy { l -> l.event.eventId }.take(MAX) }
            }
        }
        refresh()
    }

    val uiState: StateFlow<EventsUiState> = combine(sessionManager.session, live, fetched, loading, error) { session, liveList, fetchedList, isLoading, err ->
        val now = clock()
        val liveIds = liveList.map { it.event.eventId }.toSet()
        val rows = liveList.map { it.event.toFeed(receivedAtMs = it.receivedAtMs, now = now, live = true) } +
            fetchedList.filterNot { it.eventId in liveIds }.map { it.toFeed(receivedAtMs = null, now = now, live = false) }
        EventsUiState(groupName = session?.name, events = rows.take(MAX), loading = isLoading, error = err)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EventsUiState())

    /** 서버의 최근 20개 다시 읽기 */
    fun refresh() {
        val session = sessionManager.session.value ?: return
        if (loading.value) return
        loading.value = true
        error.value = null
        viewModelScope.launch {
            when (val r = repo.detail(session.groupId)) {
                is ApiResult.Success -> {
                    val members = (sessionManager.groupState.value ?: r.value.snapshot)?.members
                    fetched.value = r.value.recentEvents.filter { EventAudience.isForMe(it, sessionManager.myVehicleId, members) }
                }
                is ApiResult.HttpFailure -> error.value = r.error?.message ?: "최근 소식을 불러오지 못했어요 (HTTP ${r.status})"
                is ApiResult.NetworkFailure -> error.value = "서버에 연결할 수 없어요"
                is ApiResult.UnexpectedFailure -> error.value = "예상치 못한 오류가 났어요 (${r.cause.javaClass.simpleName})"
            }
            loading.value = false
            DhLog.i("events feed: fetched=${fetched.value.size} live=${live.value.size}")
        }
    }

    fun configureForTest(clock: () -> Long) {
        this.clock = clock
    }

    companion object {
        const val MAX = 50

        /** 이벤트 종류 한국어 라벨 */
        fun labelOf(type: String): String = when (GroupEventType.entries.firstOrNull { it.name == type }) {
            GroupEventType.LOW_ENERGY -> "저잔량"
            GroupEventType.HARD_BRAKE -> "급제동"
            GroupEventType.HAZARD_ON -> "비상등"
            GroupEventType.AIRBAG_DEPLOYED -> "에어백"
            GroupEventType.ROUTE_CHANGED -> "경로 변경"
            GroupEventType.WAYPOINT_ARRIVED -> "경유지 도착"
            GroupEventType.DESTINATION_ARRIVED -> "목적지 도착"
            GroupEventType.STOPPED -> "정차"
            GroupEventType.RESUMED -> "재출발"
            GroupEventType.PRESET_MESSAGE -> "프리셋 메시지"
            GroupEventType.MEMBER_JOINED -> "합류"
            GroupEventType.MEMBER_LEFT -> "이탈"
            GroupEventType.LEADER_CHANGED -> "선두 변경"
            GroupEventType.MEMBER_OFFLINE -> "연결 끊김"
            GroupEventType.REST_STOP_PROPOSAL -> "휴게소 제안"
            GroupEventType.REST_STOP_VOTE_RESULT -> "휴게소 투표"
            GroupEventType.DEVIATION -> "경로 이탈"
            GroupEventType.PASSENGER_MESSAGE -> "동승자 메시지"
            GroupEventType.GROUP_ENDED -> "그룹 종료"
            else -> type
        }

        /** 본문에서 사람이 읽을 요약 한 조각 */
        fun detailOf(e: GroupEvent): String? {
            fun str(key: String): String? = (e.payload[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }
            return when (e.type) {
                GroupEventType.LOW_ENERGY.name -> str("pct")?.let { "$it%" }
                GroupEventType.HARD_BRAKE.name -> str("decelMps2")?.let { d -> "${d} m/s²" + (str("speedBeforeKph")?.let { s -> " · ${s.substringBefore('.')} km/h →" } ?: "") }
                GroupEventType.WAYPOINT_ARRIVED.name, GroupEventType.DESTINATION_ARRIVED.name -> str("name")
                GroupEventType.STOPPED.name, GroupEventType.RESUMED.name -> str("durationS")?.let { "${it}초" }
                GroupEventType.PASSENGER_MESSAGE.name -> str("text")
                GroupEventType.GROUP_ENDED.name -> str("reason")
                else -> runCatching { e.payload.entries.firstOrNull()?.let { (k, v) -> "$k=${v.jsonPrimitive.content}" } }.getOrNull()
            }
        }

        fun GroupEvent.toFeed(receivedAtMs: Long?, now: Long, live: Boolean) = FeedEvent(
            eventId = eventId,
            type = type,
            typeLabel = labelOf(type),
            severity = severity,
            speech = speech,
            detail = detailOf(this),
            receivedAtMs = receivedAtMs,
            ageText = receivedAtMs?.let { ts ->
                val s = ((now - ts) / 1_000).coerceAtLeast(0)
                if (s < 60) "${s}초 전" else "${s / 60}분 전"
            },
            live = live,
        )
    }
}
