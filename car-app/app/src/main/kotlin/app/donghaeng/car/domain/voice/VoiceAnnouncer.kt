package app.donghaeng.car.domain.voice

import app.donghaeng.car.adapter.voice.VoiceAdapter
import app.donghaeng.car.core.common.DhLog
import app.donghaeng.car.domain.model.Priority
import app.donghaeng.car.domain.session.ReceivedGroupEvent
import app.donghaeng.protocol.EventAudience
import app.donghaeng.protocol.GroupEventType
import app.donghaeng.protocol.GroupState
import app.donghaeng.protocol.Severity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 진단용 상태 */
data class VoiceAnnouncerState(
    val running: Boolean = false,
    val enabled: Boolean = true,
    val queued: Int = 0,
    val spoken: Int = 0,
    val skippedStale: Int = 0,
    val skippedOwn: Int = 0,
    /** 대상이 아닌 이벤트(급제동인데 내 차가 출처보다 앞 — [EventAudience]) */
    val skippedNotAudience: Int = 0,
    val skippedDuplicate: Int = 0,
    val skippedDisabled: Int = 0,
    val skippedNoSpeech: Int = 0,
    val lastSpokenType: String? = null,
    val lastSpokenPriority: Priority? = null,
    val lastSpokenAtMs: Long? = null,
    /** 마지막 문장 길이(원문은 로그·화면에 남기지 않는다 — 닉네임 포함) */
    val lastSpokenLen: Int? = null,
)

/**
 * 그룹 이벤트 → 음성 안내 큐 (T27, docs/05 §3·§4, PRD F5·F12).
 *
 * - **신선도**: 봉투 ts 기준 [freshnessMs](5초)보다 오래된 이벤트는 읽지 않는다(재연결 뒤 밀려 들어온 옛 이벤트 생략). 큐에서 [maxQueueWaitMs](30초) 넘게 밀린 문장도 버린다.
 * - **내 이벤트 생략**: 내 차에서 난 차량 이벤트(급제동·저잔량·정차…)는 내가 들을 필요가 없다. 동승자 메시지·휴게소 제안·그룹 종료 같은 "그룹 전체" 이벤트는 출처와 무관하게 읽는다.
 * - **대상 아님 생략**: [EventAudience.isForMe] — 서버가 정한 `audience`(급제동 = 출처보다 뒤에 있는 차)에 내 차가 없으면 읽지 않는다.
 *   audience 가 없는 급제동(옛 서버·디버그 주입)은 [groupState] 최신 스냅샷으로 앞뒤를 보고, 모르면 읽는다. "앞쪽 ○○ 차가 급제동"을 앞차가 듣지 않게.
 * - **우선순위**: 에어백 CRITICAL(재생 중 안내를 끊고 즉시) > 급제동·이탈·연결 끊김 HIGH > 저잔량·제안·동승자 메시지 NORMAL > 합류·정차·도착 LOW. 큐는 우선순위 → 도착순.
 * - **중복**: 같은 문장은 [dupWindowMs] 안에 한 번만.
 * - 한 문장을 읽는 동안은 길이로 추정한 시간([msPerChar] × 글자 수 + [baseMs]) 만큼 다음 문장을 기다린다(TTS 완료 콜백이 없는 폴백 모드에서도 순서가 지켜지도록).
 * - 문장 원문(닉네임 포함)은 로그에 남기지 않는다 — 종류·길이·우선순위만.
 */
class VoiceAnnouncer(
    private val events: Flow<ReceivedGroupEvent>,
    private val myVehicleId: () -> String?,
    private val voice: VoiceAdapter,
    /** 최신 그룹 스냅샷(MqttSessionManager.groupState) — audience 없는 급제동의 앞뒤 판정용. 모르면 null */
    private val groupState: () -> GroupState? = { null },
    private val freshnessMs: Long = FRESHNESS_MS,
    private val dupWindowMs: Long = DUP_WINDOW_MS,
    private val maxQueueWaitMs: Long = MAX_QUEUE_WAIT_MS,
    private val msPerChar: Long = MS_PER_CHAR,
    private val baseMs: Long = BASE_MS,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Item(val event: ReceivedGroupEvent, val priority: Priority, val speech: String, val enqueuedAt: Long)

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var job: Job? = null
    private val mutex = Mutex()
    private val queue = ArrayList<Item>()
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val recentSpeech = ArrayDeque<Pair<String, Long>>()

    private val _state = MutableStateFlow(VoiceAnnouncerState())
    val state: StateFlow<VoiceAnnouncerState> = _state

    /** 설정 화면의 "음성 안내" 스위치 */
    fun setEnabled(enabled: Boolean) {
        _state.update { it.copy(enabled = enabled) }
        if (!enabled) {
            voice.stop()
            scope.launch { mutex.withLock { queue.clear() }; _state.update { it.copy(queued = 0) } }
        }
        DhLog.i("voice: enabled=$enabled")
    }

    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        _state.update { it.copy(running = true) }
        job = scope.launch {
            launch { events.collect { onEvent(it) } }
            launch { worker() }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        voice.stop()
        _state.update { it.copy(running = false) }
    }

    /** 그룹 이벤트가 아닌 문장(경로 제안 cmd 등)을 같은 큐·우선순위로 읽는다. 꺼져 있으면 생략 */
    fun announce(text: String, priority: Priority, type: String) {
        if (!_state.value.enabled) {
            _state.update { it.copy(skippedDisabled = it.skippedDisabled + 1) }
            return
        }
        scope.launch {
            if (priority == Priority.CRITICAL) { mutex.withLock { queue.clear() }; say(text, priority, type); return@launch }
            val fake = ReceivedGroupEvent(app.donghaeng.protocol.GroupEvent(eventId = "local-${clock()}", type = type, sourceVehicleId = null, severity = Severity.INFO, speech = text, payload = kotlinx.serialization.json.JsonObject(emptyMap())), envelopeTsMs = clock(), receivedAtMs = clock())
            mutex.withLock {
                queue += Item(fake, priority, text, clock())
                queue.sortWith(compareByDescending<Item> { it.priority.ordinal }.thenBy { it.enqueuedAt })
                _state.update { it.copy(queued = queue.size) }
            }
            signal.trySend(Unit)
        }
    }

    /** 진단 화면 "테스트 발화"·"예시 안내 듣기"(type = SAMPLE) */
    fun speakTest(text: String = TEST_SENTENCE, type: String = "TEST") {
        scope.launch { say(text, Priority.LOW, type) }
    }

    /** 이벤트 한 건을 정책에 따라 큐에 넣는다(테스트에서 직접 부를 수 있다) */
    suspend fun onEvent(e: ReceivedGroupEvent) {
        val speech = e.event.speech?.trim().orEmpty()
        if (speech.isEmpty()) {
            _state.update { it.copy(skippedNoSpeech = it.skippedNoSpeech + 1) }
            return
        }
        if (!_state.value.enabled) {
            _state.update { it.copy(skippedDisabled = it.skippedDisabled + 1) }
            return
        }
        val type = e.event.eventType
        if (isOwnVehicleEvent(type, e.event.sourceVehicleId)) {
            _state.update { it.copy(skippedOwn = it.skippedOwn + 1) }
            DhLog.d("voice: skip own ${e.event.type}")
            return
        }
        val audience = EventAudience.decide(e.event, myVehicleId(), groupState()?.members)
        if (!audience.forMe) {
            _state.update { it.copy(skippedNotAudience = it.skippedNotAudience + 1) }
            DhLog.i("voice: skip not-for-me ${e.event.type} basis=${audience.basis}")
            return
        }
        val now = clock()
        if (now - e.envelopeTsMs > freshnessMs) {
            _state.update { it.copy(skippedStale = it.skippedStale + 1) }
            DhLog.i("voice: skip stale ${e.event.type} age=${now - e.envelopeTsMs}ms")
            return
        }
        if (isDuplicate(speech, now)) {
            _state.update { it.copy(skippedDuplicate = it.skippedDuplicate + 1) }
            DhLog.d("voice: skip duplicate ${e.event.type}")
            return
        }
        val priority = priorityOf(type, e.event.severity)
        if (priority == Priority.CRITICAL) {
            // 큐를 거치지 않고 즉시 — 어댑터가 QUEUE_FLUSH 로 끊는다
            mutex.withLock { queue.clear() }
            _state.update { it.copy(queued = 0) }
            say(speech, priority, e.event.type, tag = e.event.eventId)
            return
        }
        mutex.withLock {
            queue += Item(e, priority, speech, now)
            queue.sortWith(compareByDescending<Item> { it.priority.ordinal }.thenBy { it.enqueuedAt })
            _state.update { it.copy(queued = queue.size) }
        }
        signal.trySend(Unit)
    }

    private suspend fun worker() {
        while (true) {
            signal.receive()
            while (true) {
                val item = mutex.withLock { queue.removeFirstOrNull()?.also { _state.update { s -> s.copy(queued = queue.size) } } } ?: break
                val now = clock()
                // 신선도는 들어올 때 봤다. 큐에서 너무 오래 기다린 것(앞 문장이 길었던 경우)만 버린다
                if (now - item.enqueuedAt > maxQueueWaitMs) {
                    _state.update { it.copy(skippedStale = it.skippedStale + 1) }
                    DhLog.i("voice: skip (queued too long) ${item.event.event.type} wait=${now - item.enqueuedAt}ms")
                    continue
                }
                say(item.speech, item.priority, item.event.event.type, tag = item.event.event.eventId)
                delay(estimateMs(item.speech))
            }
        }
    }

    /** [tag] = 그룹 이벤트 eventId — 어댑터의 시작 신호(`UtteranceSignal.Started`)로 표면이 위험 이벤트 강조를 푼다(VoiceCueTracker) */
    private suspend fun say(text: String, priority: Priority, type: String, tag: String? = null) {
        runCatching { voice.speak(text, priority, tag) }
            .onFailure { DhLog.w("voice: speak 실패 ${it.javaClass.simpleName}") }
        _state.update { it.copy(spoken = it.spoken + 1, lastSpokenType = type, lastSpokenPriority = priority, lastSpokenAtMs = clock(), lastSpokenLen = text.length) }
        DhLog.i("voice: speak $type priority=$priority len=${text.length}")
    }

    private fun isDuplicate(speech: String, now: Long): Boolean {
        while (recentSpeech.isNotEmpty() && now - recentSpeech.first().second > dupWindowMs) recentSpeech.removeFirst()
        if (recentSpeech.any { it.first == speech }) return true
        recentSpeech.addLast(speech to now)
        return false
    }

    private fun isOwnVehicleEvent(type: GroupEventType?, sourceVehicleId: String?): Boolean {
        val me = myVehicleId() ?: return false
        if (sourceVehicleId != me) return false
        return type in OWN_SKIPPED_TYPES
    }

    fun estimateMs(text: String): Long = baseMs + text.length * msPerChar

    companion object {
        const val FRESHNESS_MS = 5_000L
        const val DUP_WINDOW_MS = 10_000L
        /** 큐 대기 상한 — 이보다 오래 밀린 문장은 읽지 않는다 */
        const val MAX_QUEUE_WAIT_MS = 30_000L
        /** 한국어 TTS 기본 속도 ≈ 초당 7~8자 */
        const val MS_PER_CHAR = 130L
        const val BASE_MS = 600L
        const val TEST_SENTENCE = "동행 음성 안내 테스트입니다. 앞차와 함께 안전 운전하세요."

        /** 내 차가 출처인 경우 읽지 않는 종류 — 내 차 일은 내가 안다 */
        val OWN_SKIPPED_TYPES: Set<GroupEventType> = setOf(
            GroupEventType.LOW_ENERGY, GroupEventType.HARD_BRAKE, GroupEventType.HAZARD_ON, GroupEventType.AIRBAG_DEPLOYED,
            GroupEventType.WAYPOINT_ARRIVED, GroupEventType.DESTINATION_ARRIVED, GroupEventType.STOPPED, GroupEventType.RESUMED,
            GroupEventType.MEMBER_JOINED, GroupEventType.MEMBER_LEFT, GroupEventType.MEMBER_OFFLINE, GroupEventType.DEVIATION,
            GroupEventType.ROUTE_CHANGED,
        )

        /** 종류 → 우선순위 (심각도는 보조: ALERT 면 한 단계 올린다) */
        fun priorityOf(type: GroupEventType?, severity: Severity): Priority {
            val base = when (type) {
                GroupEventType.AIRBAG_DEPLOYED -> Priority.CRITICAL
                GroupEventType.HARD_BRAKE, GroupEventType.DEVIATION, GroupEventType.MEMBER_OFFLINE, GroupEventType.HAZARD_ON -> Priority.HIGH
                GroupEventType.LOW_ENERGY, GroupEventType.REST_STOP_PROPOSAL, GroupEventType.REST_STOP_VOTE_RESULT,
                GroupEventType.PASSENGER_MESSAGE, GroupEventType.PRESET_MESSAGE, GroupEventType.ROUTE_CHANGED, GroupEventType.GROUP_ENDED,
                GroupEventType.VOICE_MESSAGE -> Priority.NORMAL
                GroupEventType.MEMBER_JOINED, GroupEventType.MEMBER_LEFT, GroupEventType.LEADER_CHANGED, GroupEventType.STOPPED,
                GroupEventType.RESUMED, GroupEventType.WAYPOINT_ARRIVED, GroupEventType.DESTINATION_ARRIVED -> Priority.LOW
                null -> Priority.LOW
            }
            return if (severity == Severity.ALERT && base < Priority.HIGH) Priority.HIGH else base
        }
    }
}
