package app.donghaeng.car.feature.events

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.donghaeng.car.core.common.DhLog
import app.donghaeng.car.core.network.ApiResult
import app.donghaeng.car.domain.group.GroupRepository
import app.donghaeng.car.domain.ptt.PttFormat
import app.donghaeng.car.domain.session.MqttSessionManager
import app.donghaeng.car.domain.session.ReceivedGroupEvent
import app.donghaeng.car.feature.drive.DriveFormat
import app.donghaeng.car.feature.drive.Tone
import app.donghaeng.car.surface.SurfaceFeed
import app.donghaeng.protocol.EventAudience
import app.donghaeng.protocol.GroupEvent
import app.donghaeng.protocol.GroupEventType
import app.donghaeng.protocol.Severity
import app.donghaeng.protocol.rest.MemberLeftReason
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject

/** 피드 한 줄 */
data class FeedEvent(
    val eventId: String,
    val type: String,
    val typeLabel: String,
    val severity: Severity,
    val speech: String?,
    /** 본문 요약(연료 %·감속도·장소·"1분째 정차"·무전 길이 …). 원문 key=value 는 보이지 않는다(1-3·E119) */
    val detail: String?,
    /** 발생 시각(봉투 ts, 서버 시계) — 서버 조회분은 시각을 모른다(null) */
    val envelopeTsMs: Long?,
    /** "3분 전"(서버 시계 − 봉투 ts, 5초마다 다시 계산). 서버 조회분은 null */
    val ageText: String?,
    /** 1분 안에 생긴 소식 — "방금" 꼬리표 */
    val live: Boolean,
    /** 내 차가 출처인 소식 — "(내 차)" 꼬리표(4-6) */
    val mine: Boolean = false,
    /** 아이콘·색 톤(대시보드 지난 소식과 같은 규칙, 4-4) */
    val tone: Tone = Tone.INFO,
) {
    /** 제목 — 서버 음성 문구, 없으면 종류 라벨(+ 요약) */
    val title: String get() = speech?.trim()?.takeIf { it.isNotEmpty() } ?: listOfNotNull(typeLabel, detail).joinToString(" · ")

    /** 부제 — 종류·요약(제목에 이미 있으면 뺀다 — "…온 메시지. 휴게소 들렀다 가요 / 동승자 메시지 · 휴게소 들렀다 가요" 중복, 4-6) */
    val subtitle: String
        get() {
            val hasSpeech = !speech.isNullOrBlank()
            val d = detail?.takeIf { hasSpeech && !speech!!.contains(it) }
            return listOfNotNull(if (hasSpeech) typeLabel else null, d).joinToString("  ·  ")
        }
}

data class EventsUiState(
    val groupName: String? = null,
    val events: List<FeedEvent> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

/**
 * 이벤트 피드 (T26).
 *
 * - 실시간분 = [SurfaceFeed.recentEvents] — 앱 시작 이후 받은 그룹 이벤트(**봉투 ts**·받은 시각 포함, 내 차가 대상인 것, 그룹이 바뀌면 비움).
 *   예전엔 `groupEvents`(replay 20)를 화면을 연 순간 시각으로 찍어 30분 전 급제동도 "0초 전 · 실시간"으로 보였다(E042·4-5).
 * - 나이 = 서버 시계(ClockSync, [SurfaceFeed.serverNow]) − 봉투 ts — 대시보드 '최근 소식'·패널과 같은 값. 화면이 열려 있는 동안 [TICK_MS] 마다 다시 계산한다.
 * - 서버 조회분(`GET /groups/{id}` 의 `recentEvents`, 앱 시작 전 소식)은 시각이 없어(계약에 ts 없음) 나이를 비우고 실시간분 뒤에 둔다.
 * - 내 차가 대상이 아닌 이벤트([EventAudience] — 뒤차의 급제동 등)는 둘 다에서 뺀다. 내 차가 출처인 소식은 남기고 "(내 차)" 꼬리표.
 */
@HiltViewModel
class EventsViewModel @Inject constructor(
    private val sessionManager: MqttSessionManager,
    private val repo: GroupRepository,
    private val surface: SurfaceFeed,
) : ViewModel() {

    private val fetched = MutableStateFlow<List<GroupEvent>>(emptyList())
    private val loading = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)
    private var tickMs = TICK_MS

    /** "N분 전" 을 다시 계산하는 틱 */
    private val ticker = flow {
        while (true) {
            emit(Unit)
            delay(tickMs)
        }
    }

    init {
        refresh()
    }

    val uiState: StateFlow<EventsUiState> = combine(
        sessionManager.session,
        surface.recentEvents,
        fetched,
        combine(loading, error) { l, e -> l to e },
        ticker,
    ) { session, recent, fetchedList, (isLoading, err), _ ->
        val serverNow = surface.serverNow()
        val me = sessionManager.myVehicleId
        val live = recent.sortedWith(compareByDescending<ReceivedGroupEvent> { it.envelopeTsMs }.thenByDescending { it.receivedAtMs })
        val liveIds = live.map { it.event.eventId }.toSet()
        val rows = live.map { it.event.toFeed(envelopeTsMs = it.envelopeTsMs, serverNowMs = serverNow, myVehicleId = me) } +
            fetchedList.filterNot { it.eventId in liveIds }.map { it.toFeed(envelopeTsMs = null, serverNowMs = serverNow, myVehicleId = me) }
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
                // 예외 클래스 이름은 화면에 보이지 않는다(E119) — 로그에만
                is ApiResult.UnexpectedFailure -> {
                    DhLog.w("events feed: 조회 실패 ${r.summary}")
                    error.value = "최근 소식을 불러오지 못했어요. 잠시 후 다시 시도해 주세요"
                }
            }
            loading.value = false
            DhLog.i("events feed: fetched=${fetched.value.size} live=${surface.recentEvents.value.size}")
        }
    }

    /** 테스트용: 나이 다시 계산 주기 */
    fun configureForTest(tickMs: Long) {
        this.tickMs = tickMs
    }

    companion object {
        const val MAX = 50
        const val TICK_MS = 5_000L

        /** 이보다 최근이면 "방금" 꼬리표 */
        const val LIVE_WINDOW_MS = 60_000L

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
            GroupEventType.MEMBER_LEFT -> "나감"
            GroupEventType.LEADER_CHANGED -> "선두 변경"
            GroupEventType.MEMBER_OFFLINE -> "연결 끊김"
            GroupEventType.REST_STOP_PROPOSAL -> "휴게소 제안"
            GroupEventType.REST_STOP_VOTE_RESULT -> "휴게소 투표"
            GroupEventType.DEVIATION -> "경로 이탈"
            GroupEventType.PASSENGER_MESSAGE -> "동승자 메시지"
            GroupEventType.VOICE_MESSAGE -> "무전"
            GroupEventType.GROUP_ENDED -> "그룹 종료"
            else -> type
        }

        /** "1분 20초" / "40초" / "2분" — 정차·재출발 요약 */
        fun durationText(seconds: Long): String {
            val s = seconds.coerceAtLeast(0)
            return when {
                s < 60 -> "${s}초"
                s % 60 == 0L -> "${s / 60}분"
                else -> "${s / 60}분 ${s % 60}초"
            }
        }

        /**
         * 본문에서 사람이 읽을 요약 한 조각 — **알려진 종류만**. 모르는 종류·키는 null(예전엔 첫 payload 를 "role=MEMBER"·"voiceId=vm_…"·"vehicleId=veh_…"
         * 원문으로 보였다, 1-3·E119). 차량 id·토큰류 값은 절대 내보내지 않는다.
         */
        fun detailOf(e: GroupEvent): String? {
            fun str(key: String): String? = (e.payload[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }
            fun num(key: String): Long? = str(key)?.toDoubleOrNull()?.toLong()
            return when (e.type) {
                GroupEventType.LOW_ENERGY.name -> str("pct")?.let { "$it%" }
                GroupEventType.HARD_BRAKE.name -> str("decelMps2")?.let { d -> "${d} m/s²" + (str("speedBeforeKph")?.let { s -> " · ${s.substringBefore('.')} km/h →" } ?: "") }
                GroupEventType.WAYPOINT_ARRIVED.name, GroupEventType.DESTINATION_ARRIVED.name -> str("name")
                GroupEventType.STOPPED.name -> num("durationS")?.let { "${durationText(it)}째 정차" }
                GroupEventType.RESUMED.name -> num("durationS")?.let { "${durationText(it)} 쉬고 출발" }
                GroupEventType.PASSENGER_MESSAGE.name -> str("text")
                GroupEventType.ROUTE_CHANGED.name -> {
                    val dest = ((e.payload["destination"] as? JsonObject)?.get("name") as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }
                    val wps = (e.payload["waypoints"] as? JsonArray)?.size ?: 0
                    listOfNotNull(dest, if (wps > 0) "경유지 ${wps}곳" else null).joinToString(" · ").ifEmpty { null }
                }
                GroupEventType.VOICE_MESSAGE.name -> num("durationMs")?.let { PttFormat.clock(it) }
                GroupEventType.MEMBER_JOINED.name -> if (str("role") == "LEADER") "선두" else null
                GroupEventType.MEMBER_LEFT.name -> when (str("reason")) {
                    MemberLeftReason.UNPAIRED, MemberLeftReason.OWNER_CHANGED -> "차량 연결 해제"
                    MemberLeftReason.SWITCHED_GROUP -> "다른 그룹으로"
                    else -> null
                }
                else -> null
            }
        }

        fun GroupEvent.toFeed(envelopeTsMs: Long?, serverNowMs: Long, myVehicleId: String?): FeedEvent {
            val ageMs = envelopeTsMs?.let { (serverNowMs - it).coerceAtLeast(0) }
            return FeedEvent(
                eventId = eventId,
                type = type,
                typeLabel = labelOf(type),
                severity = severity,
                speech = speech,
                detail = detailOf(this),
                envelopeTsMs = envelopeTsMs,
                ageText = ageMs?.let(DriveFormat::ago),
                live = ageMs != null && ageMs < LIVE_WINDOW_MS,
                mine = myVehicleId != null && sourceVehicleId == myVehicleId,
                tone = DriveFormat.eventTone(type),
            )
        }
    }
}
