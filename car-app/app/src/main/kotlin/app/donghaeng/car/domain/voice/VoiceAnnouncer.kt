package app.donghaeng.car.domain.voice

import app.donghaeng.car.adapter.voice.UtteranceSignal
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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

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
    /** F16 `VOICE_MESSAGE` — 문구("○○ 무전")는 VoicePlayback 이 재생 직전에 읽는다(중복 방지) */
    val handedToPlayback: Int = 0,
    /** F16 무전 녹음·확인·재생 중 — NORMAL·LOW 문장은 큐에서 기다린다(HIGH·CRITICAL 은 그대로) */
    val held: Boolean = false,
    /** 그룹이 끝나거나 바뀌어 버린 옛 그룹 문장 수(B060) */
    val droppedOldGroup: Int = 0,
    /** 무전 녹음을 시작하며 멈추고 다시 줄 세운 문장 수(B013) */
    val yieldedToMic: Int = 0,
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
 * - **한 번에 한 문장만 어댑터로(B011)**: 어댑터가 끝 신호를 내면([VoiceAdapter.reportsEnd] — 앱의 체인) 넘긴 문장의 끝 신호(Done·Failed·Dropped·NoAudio)를
 *   상한(길이 추정 + [endWaitExtraMs]) 안에서 기다린 뒤 다음 문장을 넘긴다 — 어댑터(Pleos 큐·Android 엔진 큐)에 LOW 가 쌓여 급제동(HIGH)이 그 뒤에 밀리지 않게.
 *   화면만(NoAudio)이면 예전처럼 길이 추정만큼 간격을 둔다. 끝 신호가 없는 어댑터(테스트 대역)는 길이 추정([msPerChar] × 글자 수 + [baseMs])만큼만 기다린다.
 * - 문장 원문(닉네임 포함)은 로그에 남기지 않는다 — 종류·길이·우선순위만.
 * - **F16 무전**: `VOICE_MESSAGE` 는 여기서 읽지 않는다 — `VoicePlayback` 이 "○○ 무전" 알림 뒤 녹음을 재생한다(같은 문구를 두 번 읽지 않게).
 *   무전 녹음·확인·수신 재생 중에는 [setHeld] 로 NORMAL·LOW 문장을 큐에 붙잡아 둔다(마이크·재생과 겹치지 않게). HIGH·CRITICAL(급제동·에어백)은 안전이 먼저라 그대로 읽는다.
 *   붙잡힌 문장도 [maxQueueWaitMs](30초) 규칙을 그대로 따른다(녹음 15초 + 확인 약 8초 안에는 풀린다).
 *   **녹음을 시작하는 순간**([yieldToMic], B013): 이미 어댑터로 넘긴 NORMAL·LOW 문장이 아직 끝나지 않았으면 그 문장만 버리고(체인 `cancel`) 원래 넣은 시각 그대로
 *   큐에 다시 세운다(무전이 끝나면 처음부터 다시 읽는다) — 붙잡기는 새로 꺼낼 문장에만 걸리므로, Pleos 처럼 시작이 5~9 s 늦는 경로에서 넘긴 문장이 녹음 한가운데
 *   나와 그룹에 보내는 무전에 섞이지 않게. 같은 순간부터 [micYieldMs] 동안은 붙잡기와 같다(상태 기계가 붙잡기를 걸기 전의 틈을 메운다).
 * - **그룹이 끝나거나 바뀌면**([groupIds], B060) 옛 그룹에서 들어와 기다리던 문장(정차·도착·합류·경로 제안…)을 버린다 — '그룹 종료' 안내는 남긴다.
 * - '음성 안내' 스위치([setEnabled])는 이 엔진의 메모리 값이다 — **저장은 하지 않는다**(RB-5). 설정 화면 값의 저장·앱 시작 때 적용은 A120 `UserSettings`
 *   (`dh_settings`, 설정 화면에서 바꿀 때만) 한 곳이 맡는다 — 진단 카드·디버그 리시버의 일시 전환이 재시작 뒤까지 남아 시연 음성이 조용히 빠지지 않게.
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
    /** 지금 그룹 id(세션) — 바뀌면 옛 그룹 문장을 버린다. 기본 = 모름(버리지 않음) */
    private val groupIds: Flow<String?> = emptyFlow(),
    /** 끝 신호를 기다리는 상한 = 길이 추정 + 이 값(Pleos 시작 5~9 s + 읽기) */
    private val endWaitExtraMs: Long = END_WAIT_EXTRA_MS,
    /** [yieldToMic] 뒤 이 시간 동안은 붙잡기와 같다 */
    private val micYieldMs: Long = MIC_YIELD_MS,
) {
    /**
     * 큐 문장 한 개. [tag] = 어댑터 꼬리표(그룹 이벤트 eventId, 로컬 문장은 `local-N`) — 끝 신호·위험 강조(VoiceCueTracker)가 이것으로 문장을 가린다.
     * [groupId] = 넣을 때의 그룹(그룹이 바뀌면 버린다). [yielded] = 무전 녹음 시작으로 멈춰 다시 줄 세울 문장.
     */
    private class Item(
        val event: ReceivedGroupEvent,
        val priority: Priority,
        val speech: String,
        val enqueuedAt: Long,
        val tag: String,
        val groupId: String?,
    ) {
        @Volatile var yielded = false
        val type: String get() = event.event.type
    }

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var job: Job? = null
    private val mutex = Mutex()
    private val queue = ArrayList<Item>()
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val recentSpeech = ArrayDeque<Pair<String, Long>>()
    private val localSeq = AtomicLong(0)

    private val _state = MutableStateFlow(VoiceAnnouncerState())
    val state: StateFlow<VoiceAnnouncerState> = _state

    @Volatile
    private var held = false

    /** [yieldToMic] 이 건 짧은 붙잡기의 끝([clock] 기준) */
    @Volatile
    private var yieldUntil = 0L

    /** 어댑터로 넘기고 끝 신호를 기다리는 문장(워커만 바꾼다) */
    @Volatile
    private var inFlight: Item? = null

    /** 지금 그룹 id — [groupIds] 를 따라간다 */
    @Volatile
    private var currentGroup: String? = null

    /**
     * F16 무전 녹음·확인·수신 재생 중이면 true — 그동안 NORMAL·LOW 문장은 큐에서 기다리고 HIGH 이상만 읽는다.
     * false 로 돌아오면 밀린 문장을 이어 읽는다(30초 넘게 밀린 것은 기존 규칙대로 버린다).
     */
    fun setHeld(hold: Boolean) {
        if (held == hold) return
        held = hold
        _state.update { it.copy(held = hold) }
        if (!hold) signal.trySend(Unit)
        DhLog.d("voice: held=$hold")
    }

    /**
     * 무전 녹음을 시작한다(PttController — `recorder.start()` 직전, B013). 넘긴 문장이 NORMAL·LOW 이고 아직 끝나지 않았으면 그 문장만 버리고
     * 원래 넣은 시각 그대로 다시 줄 세운다(무전 뒤 처음부터 다시). [micYieldMs] 동안은 붙잡기와 같다 — 그사이 상태 기계의 붙잡기([setHeld])가 걸린다.
     * HIGH·CRITICAL(급제동·에어백)은 건드리지 않는다.
     */
    fun yieldToMic() {
        yieldUntil = clock() + micYieldMs
        val f = inFlight ?: return
        if (f.priority >= Priority.HIGH || f.yielded) return
        f.yielded = true
        voice.cancel(f.tag)
        _state.update { it.copy(yieldedToMic = it.yieldedToMic + 1) }
        DhLog.i("voice: yield to mic — ${f.type} priority=${f.priority} 멈춤, 무전 뒤 다시")
    }

    /** 설정 화면의 "음성 안내" 스위치(진단 카드·디버그 리시버 포함) — 메모리 값만 바꾼다. 저장은 A120 `UserSettings` 가 설정 화면에서만(RB-5) */
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
            launch { groupIds.distinctUntilChanged().collect { onGroupChanged(it) } }
            launch { events.collect { onEvent(it) } }
            launch { worker() }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        inFlight = null
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
            val id = "local-${clock()}-${localSeq.incrementAndGet()}"
            val fake = ReceivedGroupEvent(app.donghaeng.protocol.GroupEvent(eventId = id, type = type, sourceVehicleId = null, severity = Severity.INFO, speech = text, payload = kotlinx.serialization.json.JsonObject(emptyMap())), envelopeTsMs = clock(), receivedAtMs = clock())
            mutex.withLock {
                queue += Item(fake, priority, text, clock(), id, currentGroup)
                sortQueue()
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
        if (e.event.type == GroupEventType.VOICE_MESSAGE.name) {
            // F16: 알림 문구는 VoicePlayback 이 재생 직전에 읽는다 — 여기서 또 읽으면 "○○ 무전"이 두 번 나온다
            _state.update { it.copy(handedToPlayback = it.handedToPlayback + 1) }
            return
        }
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
            queue += Item(e, priority, speech, now, e.event.eventId, currentGroup)
            sortQueue()
            _state.update { it.copy(queued = queue.size) }
        }
        signal.trySend(Unit)
    }

    /** [mutex] 안에서 — 우선순위 → 도착순 */
    private fun sortQueue() {
        queue.sortWith(compareByDescending<Item> { it.priority.ordinal }.thenBy { it.enqueuedAt })
    }

    /** 붙잡는 중인가 — 무전 상태 기계([setHeld]) 또는 녹음 시작 직후의 짧은 붙잡기([yieldToMic]) */
    private fun holding(now: Long): Boolean = held || now < yieldUntil

    /** 워커가 끝나면(정상 종료는 [stop] 의 취소뿐) 이유를 남긴다 — 큐가 조용히 멈추는 문제 추적(2026-11-03 시연 녹화 중 발견) */
    private suspend fun worker() {
        try {
            workerLoop()
        } catch (e: Throwable) {
            DhLog.w("voice: worker 종료 ${e.javaClass.simpleName}: ${e.message?.take(120)} jobActive=${job?.isActive}")
            throw e
        }
    }

    private suspend fun workerLoop() {
        while (true) {
            val now = clock()
            val item = mutex.withLock {
                // 무전 중(held)에는 HIGH 이상만 꺼낸다 — 나머지는 풀릴 때(setHeld(false) → signal, 녹음 시작 붙잡기는 시간이 지나면) 이어 읽는다
                val index = if (holding(now)) queue.indexOfFirst { it.priority >= Priority.HIGH } else if (queue.isEmpty()) -1 else 0
                if (index < 0) null else queue.removeAt(index).also { _state.update { s -> s.copy(queued = queue.size) } }
            }
            if (item == null) {
                val left = yieldUntil - clock()
                // 녹음 시작 붙잡기만 걸려 있으면 그 끝에 다시 본다(그 사이 신호가 오면 더 일찍)
                if (left > 0 && !held) withTimeoutOrNull(left + 1) { signal.receive() } else signal.receive()
                continue
            }
            // 신선도는 들어올 때 봤다. 큐에서 너무 오래 기다린 것(앞 문장이 길었던 경우)만 버린다
            if (clock() - item.enqueuedAt > maxQueueWaitMs) {
                _state.update { it.copy(skippedStale = it.skippedStale + 1) }
                DhLog.i("voice: skip (queued too long) ${item.type} wait=${clock() - item.enqueuedAt}ms")
                continue
            }
            speakAndAwait(item)
        }
    }

    /**
     * 한 문장을 넘기고 끝날 때까지 기다린다(B011) — 어댑터가 끝 신호를 내면 그 꼬리표의 끝 신호를 상한 안에서, 아니면 길이 추정만큼.
     * 녹음 시작으로 멈춘 문장(Dropped)은 원래 넣은 시각 그대로 큐에 다시 세운다(B013).
     */
    private suspend fun speakAndAwait(item: Item) {
        val est = estimateMs(item.speech)
        if (!voice.reportsEnd) {
            say(item.speech, item.priority, item.type, tag = item.tag)
            delay(est)
            return
        }
        val t0 = clock()
        val end: UtteranceSignal? = try {
            coroutineScope {
                // 신호를 놓치지 않게 구독을 먼저 시작하고(UNDISPATCHED) 넘긴다
                val waiter = async(start = CoroutineStart.UNDISPATCHED) {
                    voice.signals.first { it.tag == item.tag && it !is UtteranceSignal.Started }
                }
                inFlight = item
                say(item.speech, item.priority, item.type, tag = item.tag)
                // 넘기는 사이 녹음이 시작됐다 — 넘긴 뒤에야 어댑터가 그 꼬리표를 안다
                if (item.yielded) voice.cancel(item.tag)
                val sig = withTimeoutOrNull(est + endWaitExtraMs) { waiter.await() }
                if (sig == null) waiter.cancel()
                sig
            }
        } finally {
            inFlight = null
        }
        when {
            end == null -> DhLog.w("voice: end signal timeout ${item.type} after=${clock() - t0}ms — 다음 문장으로")
            item.yielded && end !is UtteranceSignal.Done -> {
                // 무전 녹음 때문에 멈췄다 — 원래 넣은 시각 그대로 다시(30초 규칙은 그대로)
                item.yielded = false
                mutex.withLock {
                    queue += item
                    sortQueue()
                    _state.update { it.copy(queued = queue.size) }
                }
                DhLog.i("voice: requeued after mic ${item.type}")
            }
            end is UtteranceSignal.NoAudio -> {
                // 화면 표시만 — 신호가 바로 오므로 예전처럼 길이 추정만큼 간격을 둔다
                val left = est - (clock() - t0)
                if (left > 0) delay(left)
            }
        }
    }

    /** 그룹이 끝나거나 바뀌었다 — 옛 그룹에서 들어와 기다리던 문장을 버린다('그룹 종료' 안내는 남긴다) */
    private suspend fun onGroupChanged(gid: String?) {
        val prev = currentGroup
        currentGroup = gid
        if (prev == null || prev == gid) return
        val dropped = mutex.withLock {
            val old = queue.filter { it.groupId == prev && it.type != GroupEventType.GROUP_ENDED.name }
            queue.removeAll(old.toSet())
            _state.update { it.copy(queued = queue.size, droppedOldGroup = it.droppedOldGroup + old.size) }
            old.size
        }
        if (dropped > 0) DhLog.i("voice: group changed → 옛 그룹 문장 $dropped 개 버림(그룹 종료 안내는 남김)")
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
        /** 끝 신호 대기 상한의 여유 — Pleos 부하 시 시작 5~9 s(시작 상한 15 s) */
        const val END_WAIT_EXTRA_MS = 15_000L
        /** 녹음 시작 직후 붙잡기 — 상태 기계가 붙잡기([setHeld])를 걸 때까지의 틈 */
        const val MIC_YIELD_MS = 3_000L
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
