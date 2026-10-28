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
import app.donghaeng.car.domain.model.NextTurnResult
import app.donghaeng.car.domain.model.toLogString
import app.donghaeng.car.domain.session.ReceivedGroupEvent
import app.donghaeng.car.feature.drive.DriveFormat
import app.donghaeng.car.feature.drive.Tone
import app.donghaeng.protocol.Severity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

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
 * - 신선도: 서버 시계 기준 [FRESHNESS_MS](60초)보다 오래된 이벤트(재연결 뒤 밀려 온 옛 소식)는 카드로 띄우지 않는다.
 * - 카테고리: 메시지 → msg, 그 밖(저잔량·급제동·도착·합류·끊김) → navigation. Pleos 알림 표시기(ai.umos.notification)는 일반 앱 알림 중
 *   `call`·`msg`·`navigation`(·`connect.system`)만 헤드업 카드로 띄우고 `status` 는 알림 목록에만 넣는다(2026-10-28 실측 — 표시기 로그 `needHeadUpNotification`).
 *   그룹 주행 소식은 길 안내에 딸린 정보라 navigation 으로 둔다. 카드는 [TIMEOUT_MS](5초) 뒤 저절로 사라진다(2026-10-28 사용자 지시 8초 → 5초 —
 *   헤드업 카드가 순정 내비 상단 TBT 위에 겹치는 시간을 줄인다).
 * - 이 판단은 '카드로 띄울 만한가'까지다. 어느 모드에서 띄우는지(카드 + 음성 모드만)·회전 직전에 미루는지는 [EventCardNotifier] 가 정한다.
 */
object EventCardPolicy {
    const val FRESHNESS_MS = 60_000L
    const val TIMEOUT_MS = 5_000L
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

/**
 * 카드 진행 상태(디버그 `surface` 한 줄·테스트) — 문구 없이 수만.
 * [held] = 지금 회전 직전이라 미뤄 둔 카드 수, [deferred]·[released]·[expired] = 누적(미룸·회전 후 올림·120초 넘어 버림), [lastTbt] = 마지막으로 읽은 TBT.
 */
data class EventCardState(
    val running: Boolean = false,
    val posted: Int = 0,
    val skipped: Map<String, Int> = emptyMap(),
    val lastType: String? = null,
    val held: Int = 0,
    val deferred: Int = 0,
    val released: Int = 0,
    val expired: Int = 0,
    val lastTbt: String? = null,
) {
    override fun toString(): String =
        "posted=$posted held=$held deferred=$deferred released=$released expired=$expired lastTbt=${lastTbt ?: "-"} last=${lastType ?: "-"} skipped=$skipped running=$running"
}

/** [EventCardNotifier.onEvent] 결과 */
enum class CardOutcome { POSTED, DEFERRED, SKIPPED }

/**
 * 알림 카드 빈도 제한 (docs/05 §5.2) — QoS1 at-least-once 중복 수신·재접속 때 몰려오는 60초 이내 이벤트·연결 들락날락(MEMBER_OFFLINE)으로
 * 헤드업이 반복되거나 카드 수명(5초) 안에 여러 장 쌓이지 않게 한다. 음성(VoiceAnnouncer 10 s 중복 창)과 별개로 카드에만 적용.
 *
 * - 같은 `eventId` 는 한 번만(최근 [maxIds]개 기억, LRU).
 * - 같은 종류·같은 출처는 [sameKeyWindowMs](30초) 안에 한 장. 메시지(PASSENGER_MESSAGE·VOICE_MESSAGE)는 내용이 매번 달라 이 규칙에서 뺀다(eventId 중복만 거른다).
 * - 전체로는 [burstWindowMs](5초 = 카드 수명 [EventCardPolicy.TIMEOUT_MS]) 안에 [burstMax](2장)까지. 단 위험(ALERT — 에어백)은 이 한도와 상관없이 띄운다.
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
 * 그룹 이벤트를 받아 알림 카드로 띄운다 (docs/05 §5.2, 사용자 지시 2026-10-28).
 *
 * - **카드는 '카드 + 음성' 대체 모드([SurfaceMode.CARDS_ONLY] — 플로팅 창 권한 없음)에서만.** [SurfaceMode.PANEL] 이면 같은 소식을 패널 소식 줄과
 *   음성이 전하므로 카드를 올리지 않는다(skip `panel-mode`). [SurfaceMode.OFF](그룹 없음)면 아무것도 안 한다.
 * - 판단은 [EventCardPolicy], 빈도 제한은 [CardThrottle], 게시는 [CardPoster].
 * - **TBT 게이트([TurnCardGate]):** 올리기 직전에 [nextTurn] 으로 다음 회전까지 거리를 한 번 묻고 300 m 이내면 카드를 미룬다(음성은 VoiceAnnouncer 가
 *   따로 이미 읽었다). 미룬 카드가 있는 동안만 [recheckMs](1초)마다 다시 물어 다음 회전이 300 m 밖이 되거나 안내가 끝나면 미룬 순서대로 올리고
 *   (회전을 지났어도 다음 회전이 또 300 m 안이면 계속 미룬다), 120초 넘게 밀린 카드는 버린다. 미룬 카드가 없을 때 TBT 를 모르면 바로 올린다 —
 *   미룬 카드가 있으면 새 카드도 줄 끝에 서고, '모름'이 3번 연달아 와야 놓는다.
 *
 * 로그: `surface: card <종류> id=… category=… mode=… tbt=…`(올림), `surface: card deferred type=… tbt=…m kind=… why=near|tbt-unknown|burst`,
 * `surface: card released type=… reason=far|no-guidance|unknown tbt=… after=…s`, `surface: card still held — …`(연속 회전),
 * `surface: card expired type=…`, `surface: card skip <종류> reason=…`(debug 빌드).
 */
class EventCardNotifier(
    private val events: Flow<ReceivedGroupEvent>,
    private val myVehicleId: () -> String?,
    private val serverNow: () -> Long,
    private val mode: StateFlow<SurfaceMode>,
    private val groupName: () -> String?,
    private val poster: CardPoster,
    /** 다음 회전 조회(기기: [TurnProbe.read]). 기본 = 모름 → 게이트 없이 바로 올린다 */
    private val nextTurn: suspend () -> NextTurnResult = { NextTurnResult.Unknown },
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** 빈도 제한·미룬 시간용 시계(기기: 단조 시계 SystemClock.elapsedRealtime) */
    private val clock: () -> Long = System::currentTimeMillis,
    private val throttle: CardThrottle = CardThrottle(),
    private val gate: TurnCardGate = TurnCardGate(),
    private val recheckMs: Long = TurnCardGate.RECHECK_MS,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var job: Job? = null

    /** 수집 코루틴·디버그 리시버·재확인 루프가 함께 부르므로 한 번에 하나씩(TBT 조회 포함 — 최대 약 1초) */
    private val mutex = Mutex()

    /** 미룬 카드가 있는 동안만 도는 재확인 루프 — [mutex] 안에서만 바꾼다 */
    @Volatile private var recheckJob: Job? = null

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
        recheckJob?.cancel(); recheckJob = null
        _state.update { it.copy(running = false) }
    }

    /** 이벤트 한 건 처리(디버그 리시버·테스트에서 직접 부를 수 있다) */
    suspend fun onEvent(e: ReceivedGroupEvent): CardOutcome = mutex.withLock {
        val m = mode.value
        if (m == SurfaceMode.OFF) return@withLock skip("no-group", e)
        when (val d = EventCardPolicy.decide(e, myVehicleId(), serverNow())) {
            is CardDecision.Skip -> skip(d.reason, e)
            is CardDecision.Post -> {
                // 패널 모드: 패널 소식 줄 + 음성으로만 — 헤드업 카드가 순정 내비 TBT 를 가리지 않게
                if (m == SurfaceMode.PANEL) return@withLock skip("panel-mode", e)
                val limited = throttle.check(e, clock())
                // 빈도 한도(burst)에 걸려도 미룬 카드 줄이 있으면 버리지 않고 줄 끝에 세운다 — 줄이 풀릴 때 옛 소식만 남고 새 소식이 사라지지 않게
                if (limited != null && !(limited == "burst" && gate.heldCount > 0)) return@withLock skip(limited, e)
                val reading = readTurn()
                val now = clock()
                if (gate.heldCount == 0 && !gate.isNear(reading)) {
                    // 미룬 카드가 없고 다음 회전이 300 m 밖이거나 안내가 없거나 모른다 → 바로
                    gate.observe(reading, now)
                    publishGate()
                    return@withLock if (post(d.card, e, now)) CardOutcome.POSTED else CardOutcome.SKIPPED
                }
                // 회전 직전이거나 이미 미룬 카드가 있다 → 새 카드도 줄에 세우고(같은 종류는 그 자리에서 최신으로 교체) 지금 읽은 TBT 로 줄을 정리한다.
                // 놓을 때는 미룬 순서대로라 이미 미뤄 둔 카드가 새 카드보다 먼저 나간다.
                val replaced = gate.hold(d.card, e, now)
                if (replaced != null) DhLog.d("surface: card replaced type=${d.card.type} (같은 종류는 최신 1장)")
                val u = gate.observe(reading, now)
                val posted = settle(u, now, fresh = e.event.eventId)
                publishGate()
                when {
                    gate.isHolding(e.event.eventId) -> {
                        val why = when {
                            gate.isNear(reading) -> "near"
                            u.released.isEmpty() -> "tbt-unknown" // 미룬 카드가 있는데 모름(아직 3번 연달아는 아님) — 바로 띄우지 않고 줄에서 기다린다
                            else -> "burst" // 줄이 막 풀려 5초 2장 한도를 썼다 — 다음 확인에서
                        }
                        DhLog.i("surface: card deferred type=${d.card.type} tbt=${tbtLog(reading)} why=$why held=${gate.heldCount}")
                        _state.update { it.copy(deferred = it.deferred + 1) }
                        ensureRecheck()
                        CardOutcome.DEFERRED
                    }
                    e.event.eventId in posted -> CardOutcome.POSTED
                    else -> CardOutcome.SKIPPED
                }
            }
        }
    }

    /** 로그용 TBT 표기 — 회전이 있으면 `120m kind=TURN_LEFT`, 아니면 `none`/`unknown` (문구는 남기지 않는다) */
    private fun tbtLog(r: NextTurnResult): String =
        if (r is NextTurnResult.Ahead) "${r.turn.distanceM}m kind=${r.turn.kind}" else r.toLogString()

    /** 다음 회전 조회 — 실패는 모름 */
    private suspend fun readTurn(): NextTurnResult = try {
        nextTurn()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        DhLog.d("surface: tbt 조회 실패 ${e.javaClass.simpleName}")
        NextTurnResult.Unknown
    }

    /**
     * 게이트가 놓은 카드는 미룬 순서대로 올리고(빈도 제한 burst 면 다시 넣어 다음 확인에), 오래 밀린 카드는 버린다. 올린 카드의 eventId 를 돌려준다.
     * [fresh] = 방금 줄에 세운 새 카드(미룬 적이 없으니 released 로 세지 않는다).
     */
    private fun settle(u: TurnCardGate.Update, now: Long, fresh: String? = null): Set<String> {
        u.expired.forEach {
            DhLog.i("surface: card expired type=${it.card.type} after=${(now - it.sinceMs) / 1000}s (120초 넘게 밀림 — 버림)")
            _state.update { s -> s.copy(expired = s.expired + 1) }
        }
        val tbt = gate.lastReading?.toLogString() ?: "-"
        if (u.turnChanged) DhLog.i("surface: card still held — 회전이 바뀌었지만 다음 회전도 300 m 안 tbt=$tbt held=${gate.heldCount}")
        if (u.released.isEmpty()) return emptySet()
        val posted = HashSet<String>()
        u.released.forEach { h ->
            val id = h.event.event.eventId
            when (val limited = throttle.check(h.event, now)) {
                null -> {
                    if (id != fresh) {
                        DhLog.i("surface: card released type=${h.card.type} reason=${u.reason} tbt=$tbt after=${(now - h.sinceMs) / 1000}s")
                        _state.update { s -> s.copy(released = s.released + 1) }
                    }
                    if (post(h.card, h.event, now)) posted += id
                }
                "burst" -> gate.putBack(h)
                else -> skip(limited, h.event)
            }
        }
        if (gate.heldCount > 0) ensureRecheck()
        return posted
    }

    /** 미룬 카드가 있으면 재확인 루프를 띄운다 — [mutex] 안에서 부른다 */
    private fun ensureRecheck() {
        if (recheckJob != null || gate.heldCount == 0) return
        recheckJob = scope.launch {
            while (true) {
                delay(recheckMs)
                val more = mutex.withLock { recheck().also { if (!it) recheckJob = null } }
                if (!more) break
            }
        }
    }

    /** 미룬 카드 재확인 한 번 — 아직 남았으면 true. [mutex] 안에서 부른다 */
    private suspend fun recheck(): Boolean {
        if (gate.heldCount == 0) return false
        val m = mode.value
        if (m != SurfaceMode.CARDS_ONLY) {
            // 그사이 플로팅 창 권한이 생겼거나(→ 패널 소식 줄) 그룹이 끝났다 — 미룬 카드는 버린다
            val dropped = gate.clear()
            DhLog.i("surface: card dropped n=${dropped.size} types=${dropped.map { it.card.type }} reason=mode-$m")
            dropped.forEach { skip("mode-$m", it.event) }
            publishGate()
            return false
        }
        val reading = readTurn()
        val now = clock()
        settle(gate.observe(reading, now), now)
        publishGate()
        return gate.heldCount > 0
    }

    private fun post(card: EventCard, e: ReceivedGroupEvent, now: Long): Boolean {
        val ok = runCatching { poster.post(card, groupName()) }
            .onFailure { DhLog.w("surface: card post 실패 ${it.javaClass.simpleName}") }
            .getOrDefault(false)
        return if (ok) {
            throttle.record(e, now)
            _state.update { it.copy(posted = it.posted + 1, lastType = card.type) }
            DhLog.i("surface: card ${card.type} id=${card.notificationId} category=${card.category} mode=${mode.value} tbt=${gate.lastReading?.toLogString() ?: "-"}")
            true
        } else {
            skip("not-posted", e)
            false
        }
    }

    private fun publishGate() {
        _state.update { it.copy(held = gate.heldCount, lastTbt = gate.lastReading?.toLogString()) }
    }

    private fun skip(reason: String, e: ReceivedGroupEvent): CardOutcome {
        _state.update { it.copy(skipped = it.skipped + (reason to (it.skipped[reason] ?: 0) + 1)) }
        if (reason != "not-target") DhLog.d("surface: card skip ${e.event.type} reason=$reason")
        return CardOutcome.SKIPPED
    }
}

/**
 * 채널 [CHANNEL_ID](IMPORTANCE_HIGH — 헤드업 카드, **소리·진동 없음**)로 5초짜리 카드([EventCardPolicy.TIMEOUT_MS])를 올린다. 알림 권한이 없으면 false.
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
