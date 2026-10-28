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
import app.donghaeng.protocol.EventAudience
import app.donghaeng.protocol.GroupState
import app.donghaeng.protocol.Severity
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
    /** Notification.category — navigation / msg (Pleos 알림 표시기는 일반 앱의 이 둘(+call)만 헤드업으로 띄운다. `status`·`car_warning` 은 안 뜬다, docs/10 R16) */
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
 * - 대상 아님 제외([EventAudience.isForMe]): 급제동은 출처보다 뒤에 있는 차만(서버 `audience`, 없으면 [decide] 의 스냅샷으로 앞뒤 판정, 모르면 대상).
 * - 신선도: 서버 시계 기준 [FRESHNESS_MS](60초)보다 오래된 이벤트(재연결 뒤 밀려 온 옛 소식)는 카드로 띄우지 않는다.
 * - 카테고리: 메시지 → msg, 그 밖(저잔량·급제동·도착·합류·끊김) → navigation. Pleos 알림 표시기(ai.umos.notification)는 일반 앱 알림 중
 *   `call`·`msg`·`navigation`(·`connect.system`)만 헤드업 카드로 띄우고 `status` 는 알림 목록에만 넣는다(2026-10-28 실측 — 표시기 로그 `needHeadUpNotification`).
 *   그룹 주행 소식은 길 안내에 딸린 정보라 navigation 으로 둔다. 카드는 [TIMEOUT_MS](8초) 뒤 저절로 사라진다.
 */
object EventCardPolicy {
    const val FRESHNESS_MS = 60_000L
    const val TIMEOUT_MS = 8_000L
    const val BASE_ID = 4_100

    const val CATEGORY_NAVIGATION = "navigation"
    const val CATEGORY_MESSAGE = "msg"

    /** 대상 종류 — 목록 순서가 알림 id 를 정한다 */
    val TARGET_TYPES: List<String> = listOf(
        "LOW_ENERGY", "HARD_BRAKE", "AIRBAG_DEPLOYED", "WAYPOINT_ARRIVED", "DESTINATION_ARRIVED",
        "MEMBER_JOINED", "MEMBER_OFFLINE", "PASSENGER_MESSAGE", "VOICE_MESSAGE",
    )

    fun notificationIdOf(type: String): Int = BASE_ID + TARGET_TYPES.indexOf(type).coerceAtLeast(0)

    fun categoryOf(type: String): String = when (type) {
        "PASSENGER_MESSAGE", "VOICE_MESSAGE" -> CATEGORY_MESSAGE
        else -> CATEGORY_NAVIGATION
    }

    fun decide(e: ReceivedGroupEvent, myVehicleId: String?, serverNowMs: Long, groupState: GroupState? = null): CardDecision {
        val type = e.event.type
        if (type !in TARGET_TYPES) return CardDecision.Skip("not-target")
        if (myVehicleId != null && e.event.sourceVehicleId == myVehicleId) return CardDecision.Skip("own")
        if (!EventAudience.isForMe(e.event, myVehicleId, groupState?.members)) return CardDecision.Skip("not-audience")
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
 * 알림 카드 빈도 제한 (docs/05 §5.2) — QoS1 at-least-once 중복 수신·재접속 때 몰려오는 60초 이내 이벤트·연결 들락날락(MEMBER_OFFLINE)으로
 * 헤드업이 반복되거나 8초 안에 여러 장 쌓이지 않게 한다. 음성(VoiceAnnouncer 10 s 중복 창)과 별개로 카드에만 적용.
 *
 * - 같은 `eventId` 는 한 번만(최근 [maxIds]개 기억, LRU).
 * - 같은 종류·같은 출처는 [sameKeyWindowMs](30초) 안에 한 장. 메시지(PASSENGER_MESSAGE·VOICE_MESSAGE)는 내용이 매번 달라 이 규칙에서 뺀다(eventId 중복만 거른다).
 * - 전체로는 [burstWindowMs](8초 = 카드 수명) 안에 [burstMax](2장)까지. 단 위험(ALERT — 에어백)은 이 한도와 상관없이 띄운다.
 *
 * [check] 로 거를 이유를 묻고(null = 통과), 실제로 올린 뒤 [record] 한다(알림이 꺼져 못 올린 건 한도를 쓰지 않는다).
 */
class CardThrottle(
    private val maxIds: Int = MAX_IDS,
    private val sameKeyWindowMs: Long = SAME_KEY_WINDOW_MS,
    private val burstWindowMs: Long = EventCardPolicy.TIMEOUT_MS,
    private val burstMax: Int = BURST_MAX,
) {
    private val seenIds = object : LinkedHashMap<String, Unit>(maxIds, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?): Boolean = size > maxIds
    }
    private val lastByKey = HashMap<String, Long>()
    private val posts = ArrayDeque<Long>()

    @Synchronized
    fun check(e: ReceivedGroupEvent, nowMs: Long): String? {
        if (seenIds.containsKey(e.event.eventId)) return "duplicate"
        val key = keyOf(e)
        val last = key?.let { lastByKey[it] }
        if (last != null && nowMs - last < sameKeyWindowMs) return "same-recent"
        if (e.event.severity != Severity.ALERT) {
            while (posts.isNotEmpty() && nowMs - posts.first() >= burstWindowMs) posts.removeFirst()
            if (posts.size >= burstMax) return "burst"
        }
        return null
    }

    @Synchronized
    fun record(e: ReceivedGroupEvent, nowMs: Long) {
        seenIds[e.event.eventId] = Unit
        keyOf(e)?.let { lastByKey[it] = nowMs }
        posts.addLast(nowMs)
        if (lastByKey.size > maxIds) lastByKey.entries.removeAll { nowMs - it.value >= sameKeyWindowMs }
    }

    private fun keyOf(e: ReceivedGroupEvent): String? =
        if (e.event.type in MESSAGE_TYPES) null else e.event.type + "|" + (e.event.sourceVehicleId ?: "-")

    companion object {
        const val MAX_IDS = 128
        const val SAME_KEY_WINDOW_MS = 30_000L
        const val BURST_MAX = 2
        val MESSAGE_TYPES = setOf("PASSENGER_MESSAGE", "VOICE_MESSAGE")
    }
}

/**
 * 그룹 이벤트를 받아 알림 카드로 띄운다 (docs/05 §5.2). 모드 [SurfaceMode.PANEL]·[SurfaceMode.CARDS_ONLY] 모두에서 동작하고 [SurfaceMode.OFF](그룹 없음)면 아무것도 안 한다.
 * 판단은 [EventCardPolicy], 빈도 제한은 [CardThrottle], 게시는 [CardPoster].
 */
class EventCardNotifier(
    private val events: Flow<ReceivedGroupEvent>,
    private val myVehicleId: () -> String?,
    private val serverNow: () -> Long,
    private val mode: StateFlow<SurfaceMode>,
    private val groupName: () -> String?,
    private val poster: CardPoster,
    /** 최신 그룹 스냅샷 — audience 없는 급제동의 앞뒤 판정([EventCardPolicy.decide]) */
    private val groupState: () -> GroupState? = { null },
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** 빈도 제한용 기기 시계 */
    private val clock: () -> Long = System::currentTimeMillis,
    private val throttle: CardThrottle = CardThrottle(),
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

    /** 이벤트 한 건 처리(디버그 리시버·테스트에서 직접 부를 수 있다). 올렸으면 true. 수집 코루틴과 디버그 리시버가 함께 부를 수 있어 직렬화한다 */
    @Synchronized
    fun onEvent(e: ReceivedGroupEvent): Boolean {
        if (mode.value == SurfaceMode.OFF) return skip("no-group", e)
        return when (val d = EventCardPolicy.decide(e, myVehicleId(), serverNow(), groupState())) {
            is CardDecision.Skip -> skip(d.reason, e)
            is CardDecision.Post -> {
                val now = clock()
                val limited = throttle.check(e, now)
                if (limited != null) return skip(limited, e)
                val ok = runCatching { poster.post(d.card, groupName()) }
                    .onFailure { DhLog.w("surface: card post 실패 ${it.javaClass.simpleName}") }
                    .getOrDefault(false)
                if (ok) {
                    throttle.record(e, now)
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

/**
 * 채널 [CHANNEL_ID](IMPORTANCE_HIGH — 헤드업 카드, **소리·진동 없음**)로 8초짜리 카드를 올린다. 알림 권한이 없으면 false.
 * 같은 이벤트를 VoiceAnnouncer 가 TTS 로 읽으므로 카드는 소리를 내지 않는다(알림음과 음성이 겹치지 않게). 채널 소리 설정은 만든 뒤 바꿀 수 없어
 * 소리가 켜진 채로 만들어진 옛 채널 [LEGACY_CHANNEL_ID] 는 지우고 새 id 로 만든다.
 */
class AndroidCardPoster(context: Context) : CardPoster {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(NotificationManager::class.java)
    @Volatile private var channelReady = false
    @Volatile private var warnedDisabled = false

    private fun ensureChannel() {
        if (channelReady || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) { channelReady = true; return }
        if (manager.getNotificationChannel(LEGACY_CHANNEL_ID) != null) {
            runCatching { manager.deleteNotificationChannel(LEGACY_CHANNEL_ID) }
                .onSuccess { DhLog.i("surface: 옛 카드 채널 $LEGACY_CHANNEL_ID 삭제(소리 있음)") }
                .onFailure { DhLog.w("surface: 옛 카드 채널 삭제 실패 ${it.javaClass.simpleName}") }
        }
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.surface_events_channel_name), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = context.getString(R.string.surface_events_channel_desc)
                    setShowBadge(false)
                    // 헤드업은 IMPORTANCE_HIGH 만으로 뜬다 — 안내는 음성이 맡는다
                    setSound(null, null)
                    enableVibration(false)
                    enableLights(false)
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
        const val CHANNEL_ID = "dh_events_silent"
        /** 2026-10-28 첫 빌드의 카드 채널(기본 알림음) — 남아 있으면 지운다 */
        const val LEGACY_CHANNEL_ID = "dh_events"
    }
}
