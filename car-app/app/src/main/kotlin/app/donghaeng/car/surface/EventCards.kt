package app.donghaeng.car.surface

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import app.donghaeng.car.MainActivity
import app.donghaeng.car.R
import app.donghaeng.car.core.common.DhLog
import app.donghaeng.car.domain.session.ReceivedGroupEvent
import app.donghaeng.car.feature.drive.DriveFormat
import app.donghaeng.car.feature.drive.Tone
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 알림 카드 한 장 — 문구([title])는 화면에만, 로그에는 종류·id·카테고리만 */
data class EventCard(
    val type: String,
    /** 같은 종류는 같은 id → 새 카드가 이전 카드를 교체한다(쌓이지 않게) */
    val notificationId: Int,
    /** Notification.category — navigation / msg / status (`car_warning` 은 시스템 권한이 필요해 쓰지 않는다, docs/10 R16) */
    val category: String,
    val title: String,
    val tone: Tone,
)

sealed interface CardDecision {
    data class Post(val card: EventCard) : CardDecision
    data class Skip(val reason: String) : CardDecision
}

/**
 * 그룹 이벤트 → 알림 카드 판단 (docs/05 §5.2) — 순수 함수.
 *
 * - 대상: 저잔량·급제동·에어백·경유지/목적지 도착·합류·연결 끊김·동승자 메시지(+ 다음 작업 F16 의 `VOICE_MESSAGE` — 문자열 비교라 enum 이 없어도 된다).
 * - 내 차가 출처인 이벤트 제외(내 차 일은 내가 안다).
 * - 신선도: 서버 시계 기준 [FRESHNESS_MS](60초)보다 오래된 이벤트(재연결 뒤 밀려 온 옛 소식)는 카드로 띄우지 않는다.
 * - 카테고리: 도착 → navigation, 메시지 → msg, 그 밖 → status. 카드는 [TIMEOUT_MS](8초) 뒤 저절로 사라진다.
 */
object EventCardPolicy {
    const val FRESHNESS_MS = 60_000L
    const val TIMEOUT_MS = 8_000L
    const val BASE_ID = 4_100

    const val CATEGORY_NAVIGATION = "navigation"
    const val CATEGORY_MESSAGE = "msg"
    const val CATEGORY_STATUS = "status"

    /** 대상 종류 — 목록 순서가 알림 id 를 정한다 */
    val TARGET_TYPES: List<String> = listOf(
        "LOW_ENERGY", "HARD_BRAKE", "AIRBAG_DEPLOYED", "WAYPOINT_ARRIVED", "DESTINATION_ARRIVED",
        "MEMBER_JOINED", "MEMBER_OFFLINE", "PASSENGER_MESSAGE", "VOICE_MESSAGE",
    )

    fun notificationIdOf(type: String): Int = BASE_ID + TARGET_TYPES.indexOf(type).coerceAtLeast(0)

    fun categoryOf(type: String): String = when (type) {
        "WAYPOINT_ARRIVED", "DESTINATION_ARRIVED" -> CATEGORY_NAVIGATION
        "PASSENGER_MESSAGE", "VOICE_MESSAGE" -> CATEGORY_MESSAGE
        else -> CATEGORY_STATUS
    }

    fun decide(e: ReceivedGroupEvent, myVehicleId: String?, serverNowMs: Long): CardDecision {
        val type = e.event.type
        if (type !in TARGET_TYPES) return CardDecision.Skip("not-target")
        if (myVehicleId != null && e.event.sourceVehicleId == myVehicleId) return CardDecision.Skip("own")
        val age = serverNowMs - e.envelopeTsMs
        if (age > FRESHNESS_MS) return CardDecision.Skip("stale")
        return CardDecision.Post(
            EventCard(
                type = type,
                notificationId = notificationIdOf(type),
                category = categoryOf(type),
                title = DriveFormat.eventText(type, e.event.speech),
                tone = DriveFormat.eventTone(type),
            ),
        )
    }
}

/** 카드를 실제로 올리는 쪽 — 기기: [AndroidCardPoster], 테스트: 기록용 가짜 */
fun interface CardPoster {
    /** 올렸으면 true(알림이 꺼져 있으면 false) */
    fun post(card: EventCard, groupName: String?): Boolean
}

data class EventCardState(val running: Boolean = false, val posted: Int = 0, val skipped: Map<String, Int> = emptyMap(), val lastType: String? = null)

/**
 * 그룹 이벤트를 받아 알림 카드로 띄운다 (docs/05 §5.2). 모드 [SurfaceMode.PANEL]·[SurfaceMode.CARDS_ONLY] 모두에서 동작하고 [SurfaceMode.OFF](그룹 없음)면 아무것도 안 한다.
 * 판단은 [EventCardPolicy], 게시는 [CardPoster].
 */
class EventCardNotifier(
    private val events: Flow<ReceivedGroupEvent>,
    private val myVehicleId: () -> String?,
    private val serverNow: () -> Long,
    private val mode: StateFlow<SurfaceMode>,
    private val groupName: () -> String?,
    private val poster: CardPoster,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var job: Job? = null

    private val _state = MutableStateFlow(EventCardState())
    val state: StateFlow<EventCardState> = _state

    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        _state.update { it.copy(running = true) }
        job = scope.launch { events.collect { onEvent(it) } }
    }

    @Synchronized
    fun stop() {
        job?.cancel(); job = null
        _state.update { it.copy(running = false) }
    }

    /** 이벤트 한 건 처리(디버그 리시버·테스트에서 직접 부를 수 있다). 올렸으면 true */
    fun onEvent(e: ReceivedGroupEvent): Boolean {
        if (mode.value == SurfaceMode.OFF) return skip("no-group", e)
        return when (val d = EventCardPolicy.decide(e, myVehicleId(), serverNow())) {
            is CardDecision.Skip -> skip(d.reason, e)
            is CardDecision.Post -> {
                val ok = runCatching { poster.post(d.card, groupName()) }
                    .onFailure { DhLog.w("surface: card post 실패 ${it.javaClass.simpleName}") }
                    .getOrDefault(false)
                if (ok) {
                    _state.update { it.copy(posted = it.posted + 1, lastType = d.card.type) }
                    DhLog.i("surface: card ${d.card.type} id=${d.card.notificationId} category=${d.card.category} mode=${mode.value}")
                    true
                } else {
                    skip("not-posted", e)
                }
            }
        }
    }

    private fun skip(reason: String, e: ReceivedGroupEvent): Boolean {
        _state.update { it.copy(skipped = it.skipped + (reason to (it.skipped[reason] ?: 0) + 1)) }
        if (reason != "not-target") DhLog.d("surface: card skip ${e.event.type} reason=$reason")
        return false
    }
}

/** 채널 `dh_events`(IMPORTANCE_HIGH) 로 8초짜리 카드를 올린다. 알림 권한이 없으면 false */
class AndroidCardPoster(context: Context) : CardPoster {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(NotificationManager::class.java)
    @Volatile private var channelReady = false
    @Volatile private var warnedDisabled = false

    private fun ensureChannel() {
        if (channelReady || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) { channelReady = true; return }
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.surface_events_channel_name), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = context.getString(R.string.surface_events_channel_desc)
                    setShowBadge(false)
                },
            )
        }
        channelReady = true
    }

    override fun post(card: EventCard, groupName: String?): Boolean {
        ensureChannel()
        if (!manager.areNotificationsEnabled()) {
            if (!warnedDisabled) DhLog.w("surface: 알림이 꺼져 있어 카드를 못 띄움(POST_NOTIFICATIONS 미승인?) — 음성·패널만")
            warnedDisabled = true
            return false
        }
        val open = PendingIntent.getActivity(
            context, card.notificationId,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(MainActivity.EXTRA_SCREEN, "drive"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val icon = when (card.tone) {
            Tone.DANGER, Tone.CAUTION -> android.R.drawable.ic_dialog_alert
            else -> android.R.drawable.ic_dialog_info
        }
        val n: Notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle(card.title)
            .apply { if (!groupName.isNullOrBlank()) setContentText(groupName) }
            .setCategory(card.category)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setTimeoutAfter(EventCardPolicy.TIMEOUT_MS)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setContentIntent(open)
            .build()
        return try {
            manager.notify(card.notificationId, n)
            true
        } catch (e: SecurityException) {
            DhLog.w("surface: notify SecurityException ${e.message?.take(80)}")
            false
        }
    }

    companion object {
        const val CHANNEL_ID = "dh_events"
    }
}
