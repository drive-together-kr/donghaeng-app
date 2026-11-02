package app.donghaeng.car.domain.ptt

import app.donghaeng.car.adapter.audio.PlayOutcome
import app.donghaeng.car.adapter.audio.VoiceClipPlayer
import app.donghaeng.car.adapter.voice.SpeakResult
import app.donghaeng.car.adapter.voice.VoiceAdapter
import app.donghaeng.car.core.common.DhLog
import app.donghaeng.car.domain.model.Priority
import app.donghaeng.car.domain.session.GroupSession
import app.donghaeng.car.domain.session.ReceivedGroupEvent
import app.donghaeng.protocol.DhJson
import app.donghaeng.protocol.EventAudience
import app.donghaeng.protocol.GroupEventType
import app.donghaeng.protocol.GroupState
import app.donghaeng.protocol.VoiceMessagePayload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** 받은 무전 한 개(메타데이터 — [nickname] 은 화면용, 로그 금지) */
data class ReceivedVoice(
    val eventId: String,
    val voiceId: String,
    val groupId: String,
    val fromVehicleId: String?,
    val nickname: String?,
    /** 멤버 색 `#RRGGBB`(스냅샷) — 모르면 null */
    val colorHex: String?,
    val durationMs: Long,
    val mime: String,
    /** 서버 봉투 ts(서버 시계) */
    val envelopeTsMs: Long,
    /** 서버 speech("{닉네임} 무전") */
    val speech: String?,
    val status: PastStatus = PastStatus.QUEUED,
    /** 내려받은 캐시 파일(없으면 null — 탭하면 내려받는다) */
    val file: File? = null,
)

/** '지난 무전' 항목 상태 */
enum class PastStatus {
    QUEUED,
    PLAYED,

    /** 60초 넘게 지나 자동 재생하지 않았다(재연결 뒤 밀려 온 것) — 정차 대시보드에서 탭 재생 */
    STALE,

    /**
     * '다 못 들음' — 재생(또는 "○○ 무전" 알림) 중 무전 버튼을 눌러 끊었다. 내 녹음·확인 흐름이 끝나면(IDLE) 처음부터 자동으로 다시 틀고,
     * 다 들으면 [PLAYED] 가 된다(사용자 결정 2026-10-28). 다시 틀기 전·상한([VoicePlayback.RESUME_MAX_WAIT_MS])을 넘겨 건너뛴 것은 탭 재생
     */
    INTERRUPTED,
    DOWNLOAD_FAILED,
    PLAY_FAILED,
}

/** 지금 재생(또는 끝나고 3초 머무는) 무전 */
data class PlayingVoice(
    val voice: ReceivedVoice,
    val positionMs: Long = 0,
    /** 재생이 끝나고 [VoicePlayback.lingerMs] 동안 머무는 중 */
    val ended: Boolean = false,
)

data class VoicePlaybackState(
    val playing: PlayingVoice? = null,
    /** 재생을 기다리는 수(내려받는 중·끊겨 다시 틀 차례를 기다리는 것 포함) */
    val queued: Int = 0,
    /** 무전 보내기(녹음·확인·전송) 중이라 재생을 미루는 중 */
    val held: Boolean = false,
    /** 끊긴 무전이 내 무전 흐름이 끝나기(IDLE)를 기다리는 중 — [VoicePlayback.resumeInterrupted] 가 푼다 */
    val resumeWaiting: Boolean = false,
    /**
     * '지난 무전'(최신 먼저, 최근 [VoicePlayback.MAX_PAST]개 — 넘치면 오래된 것부터 캐시 파일과 함께 버린다) — 다 들은 것도 남긴다(다시 듣기)·
     * 60초 넘은 것·끊은 것(다 못 들음)·실패. 그룹이 끝나거나 바뀌면 캐시와 함께 지운다
     */
    val past: List<ReceivedVoice> = emptyList(),
    val received: Int = 0,
    val played: Int = 0,
    val skippedOwn: Int = 0,
    val skippedNotAudience: Int = 0,
    val stale: Int = 0,
    val downloadFailed: Int = 0,
    val playFailed: Int = 0,
)

/** 받은 무전 오디오 내려받기 — 기기: VoiceMessageClient.download → `cache/ptt/in/{voiceId}.{ext}` */
fun interface VoiceFetcher {
    /** 성공이면 파일, 실패면 null(사유는 구현이 로그) */
    suspend fun fetch(groupId: String, voiceId: String, mime: String): File?
}

/**
 * 받은 무전 재생 (F16 수신, docs/05 § 무전).
 *
 * 1. 그룹 이벤트 `VOICE_MESSAGE`([VoiceMessagePayload])만 본다. **내 차가 보낸 것은 제외**, 수신 대상은 공용 [EventAudience](무전은 audience 없음 = 그룹 전체).
 * 2. 서버 시계([serverNow], ClockSync) 기준 봉투 나이가 [freshnessMs](60초) 이내면 바로 내려받아 큐에 넣고, 넘었으면 재생하지 않고 '지난 무전'(STALE)에만.
 * 3. 큐는 도착 순. 무전 보내기 중([setHeld] — 녹음·확인·전송)에는 기다렸다가 끝난 뒤 재생한다. 재생 직전 다시 60초를 넘었으면 STALE.
 * 4. 재생 전 짧은 알림 — 서버 speech "{닉네임} 무전"(없으면 [PttPrompts.incoming])을 TTS 로 끝까지 읽고(엔진이 없으면 즉시) →
 *    [VoiceClipPlayer](MediaPlayer + `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`). VoiceAnnouncer 는 같은 문구를 읽지 않는다.
 * 5. 재생 중 [state].playing(보낸 차·위치/길이) → 끝나면 큐가 비었을 때 [lingerMs](3초) 머문 뒤 사라진다.
 * 6. 재생 중 무전 버튼을 누르면 [interrupt] — 멈추고 INTERRUPTED('다 못 들음')로 '지난 무전'에 둔다. "○○ 무전" 알림을 읽는 중·재생 준비 중이어도 멈춘다
 *    (알림+재생 한 덩어리를 코루틴 하나로 돌려 취소한다. 알림을 읽다 끊기면 그 알림 문장만 버린다 — 체인의 꼬리표 취소, B012).
 *    `resumeAfterFlow` 면(무전 버튼 누름 — 사용자 결정 2026-10-28) 끊긴 무전을 큐 맨 앞에 '다시 틀 것'으로 세우고, 내 녹음·확인 흐름이 IDLE 로 끝나
 *    [resumeInterrupted] 가 불릴 때까지(PttRuntime 이 부른다) **다른 대기 무전보다 먼저** 기다렸다가 알림부터 처음부터 다시 튼다 → 다 들으면 PLAYED.
 *    60초 신선도는 보지 않고 대신 끊긴 때부터 [RESUME_MAX_WAIT_MS](2분) 상한 — 넘으면 다시 틀지 않고 '다 못 들음'으로 남긴다(탭 재생).
 *    상한이 지나도록 풀리지 않으면 스스로 연다(뒤의 무전이 영영 막히지 않게).
 * 7. 그룹이 바뀌거나 끝나면 큐(다시 틀 것 포함)·목록·캐시 파일을 지우고 재생 중(알림 포함)인 것도 멈춘다(서버도 그룹 종료 때 지운다).
 *    내려받는 중이던 항목은 버리고 워커는 계속 돈다 — 새 그룹의 무전은 그대로 재생된다.
 * 8. '지난 무전'은 다 들은 것도 남기고 최근 [MAX_PAST](20)개만 — 넘치면 오래된 것부터 캐시 파일과 함께 버린다.
 *
 * 로그: `ptt: voice received voiceId=… age=…s dur=…ms`, `ptt: play start voiceId=… manual=… resume=…`, `ptt: playing from=<vehicleId 끝 4자리> voiceId=… dur=…`,
 * `ptt: playback interrupted voiceId=… why=… status=INTERRUPTED resume=after-flow`, `ptt: resume released why=…`, `ptt: resume voiceId=… waited=…s → 처음부터`,
 * `ptt: voice stale …`, `ptt: voice skip own|not-for-me`. 닉네임·오디오 내용은 남기지 않는다(규칙 8).
 */
class VoicePlayback(
    private val events: Flow<ReceivedGroupEvent>,
    private val myVehicleId: () -> String?,
    private val groupState: () -> GroupState?,
    private val session: StateFlow<GroupSession?>,
    private val serverNow: () -> Long,
    private val fetcher: VoiceFetcher,
    private val player: VoiceClipPlayer,
    private val voice: VoiceAdapter,
    /** 설정 "음성 안내"가 꺼져 있으면 "○○ 무전" 알림만 생략(녹음은 재생) */
    private val announceEnabled: () -> Boolean = { true },
    /** 다 재생한 무전 캐시 파일 정리 대상 폴더(그룹 종료 때 비운다) — null 이면 정리 안 함(테스트) */
    private val cacheDir: File? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val freshnessMs: Long = FRESHNESS_MS,
    val lingerMs: Long = LINGER_MS,
    private val gapMs: Long = GAP_MS,
    private val announceTimeoutMs: Long = ANNOUNCE_TIMEOUT_MS,
    private val resumeMaxWaitMs: Long = RESUME_MAX_WAIT_MS,
) {
    /**
     * 큐 항목. [manual] = 탭 다시 듣기(신선도 안 봄), [resume] = 무전 버튼으로 끊겨 내 흐름이 끝나면 처음부터 다시 틀 것([interruptedAtMs] = 끊긴 때, 서버 시계)
     */
    private class Item(
        val voice: ReceivedVoice,
        val file: Deferred<File?>,
        val manual: Boolean,
        val resume: Boolean = false,
        val interruptedAtMs: Long = 0,
    )

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var job: Job? = null
    private val mutex = Mutex()
    private val queue = ArrayDeque<Item>()
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val seen = LinkedHashSet<String>()
    private val held = MutableStateFlow(false)
    /** 끊긴 무전을 다시 틀어도 되는가 — [interrupt](resumeAfterFlow) 가 닫고 [resumeInterrupted]·그룹 변경·상한이 연다 */
    private val resumeGate = MutableStateFlow(true)
    /** 큐 맨 앞이 바뀔 수 있는 변경(탭 다시 듣기·그룹 변경·다시 틀 것 넣기)마다 올린다 — 문 앞에서 기다리는 워커를 깨운다 */
    private val queueVersion = MutableStateFlow(0L)
    @Volatile private var playingNow: Item? = null
    @Volatile private var interruptedBy: String? = null
    /** 지금 끊는 것이 '흐름이 끝나면 다시 틀 것'인가 — [interrupt] 가 세우고 [playOne] 이 지운다 */
    @Volatile private var resumeRequested = false
    /** 지금 무전 한 개의 알림+재생 코루틴 — [interrupt]·그룹 변경이 취소한다 */
    @Volatile private var currentPlay: Job? = null

    private val _state = MutableStateFlow(VoicePlaybackState())
    val state: StateFlow<VoicePlaybackState> = _state.asStateFlow()

    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            launch { session.map { it?.groupId }.distinctUntilChanged().collect { onGroupChanged() } }
            launch { events.collect { onEvent(it) } }
            launch { worker() }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel(); job = null
        player.stop()
    }

    /** 무전 보내기(녹음·확인·전송) 중이면 true — 그동안 재생을 미룬다 */
    fun setHeld(hold: Boolean) {
        if (held.value == hold) return
        held.value = hold
        _state.update { it.copy(held = hold) }
        if (!hold) signal.trySend(Unit)
    }

    /** 실제로 소리를 내는 중인가(머무는 3초 제외) */
    val isPlaying: Boolean get() = playingNow != null && _state.value.playing?.ended == false

    /**
     * 재생 중인 무전을 끊는다(무전 버튼을 눌러 녹음을 시작할 때) — 알림을 읽는 중·재생 준비 중이어도. '지난 무전'에 INTERRUPTED('다 못 들음')로 남는다.
     * [resumeAfterFlow] 면 내 무전 흐름이 끝나 [resumeInterrupted] 가 불릴 때 처음부터 다시 튼다(다른 대기 무전보다 먼저).
     */
    fun interrupt(why: String, resumeAfterFlow: Boolean = false) {
        if (playingNow == null) return
        if (resumeAfterFlow) {
            // 문을 먼저 닫는다 — 끊긴 무전이 큐에 다시 서는 순간 바로 재생되지 않게(누름이 상태 기계에 닿기 전일 수 있다)
            resumeRequested = true
            resumeGate.value = false
        }
        stopCurrent(why)
    }

    /**
     * 끊긴 무전을 다시 틀어도 된다 — 내 녹음·확인 흐름이 IDLE 로 끝났다(보냄·취소·보관·짧음·실패 어느 쪽이든, PttRuntime 이 사이클 번호로 가려 부른다).
     * 기다리는 것이 없으면 아무 일도 하지 않는다.
     */
    fun resumeInterrupted(why: String) {
        if (resumeGate.value) return
        resumeGate.value = true
        _state.update { it.copy(resumeWaiting = false) }
        DhLog.i("ptt: resume released why=$why")
        signal.trySend(Unit)
    }

    /** 알림+재생 코루틴을 취소한다 — 순서: 사유 먼저(playOne 이 코루틴을 세운 뒤 사유를 다시 본다), 그다음 취소 */
    private fun stopCurrent(why: String) {
        interruptedBy = why
        currentPlay?.cancel()
        player.stop()
    }

    /** '지난 무전' 다시 듣기(정차 대시보드 탭) — 파일이 없으면 내려받는다. 신선도는 보지 않는다. 큐에 넣었으면 true */
    suspend fun replay(voiceId: String): Boolean = mutex.withLock {
        val v = _state.value.past.firstOrNull { it.voiceId == voiceId } ?: return@withLock false
        val file = v.file?.takeIf { it.isFile }
        val deferred: Deferred<File?> = if (file != null) CompletableDeferred(file) else scope.async { fetchSafely(v) }
        // 끊겨 다시 틀 차례를 기다리던 무전이면 그 자리를 탭 재생이 대신한다(두 번 나오지 않게)
        queue.removeAll { it.voice.voiceId == voiceId }
        queue.addFirst(Item(v.copy(status = PastStatus.QUEUED), deferred, manual = true))
        queueVersion.value++
        _state.update { s -> s.copy(past = s.past.filterNot { it.voiceId == voiceId }) }
        publishQueue()
        DhLog.i("ptt: replay voiceId=$voiceId cached=${file != null}")
        signal.trySend(Unit)
        true
    }

    /** [mutex] 안에서 — 대기 수·끊긴 무전 대기 여부를 상태에 */
    private fun publishQueue() {
        val waiting = !resumeGate.value && queue.any { it.resume }
        _state.update { it.copy(queued = queue.size, resumeWaiting = waiting) }
    }

    /** 이벤트 한 건(테스트·디버그 리시버가 직접 부를 수 있다) — 큐에 넣었거나 '지난 무전'에 둔 voiceId, 무시면 null */
    suspend fun onEvent(e: ReceivedGroupEvent): String? {
        if (e.event.type != GroupEventType.VOICE_MESSAGE.name) return null
        val payload = try {
            DhJson.payloadAs(e.event.payload, VoiceMessagePayload.serializer())
        } catch (ex: Exception) {
            DhLog.w("ptt: voice payload 해석 실패 ${ex.javaClass.simpleName}")
            return null
        }
        val me = myVehicleId()
        if (me != null && e.event.sourceVehicleId == me) {
            _state.update { it.copy(skippedOwn = it.skippedOwn + 1) }
            DhLog.d("ptt: voice skip own voiceId=${payload.voiceId}")
            return null
        }
        val audience = EventAudience.decide(e.event, me, groupState()?.members)
        if (!audience.forMe) {
            _state.update { it.copy(skippedNotAudience = it.skippedNotAudience + 1) }
            DhLog.i("ptt: voice skip not-for-me basis=${audience.basis}")
            return null
        }
        val gid = session.value?.groupId ?: return null
        return mutex.withLock {
            if (!seen.add(e.event.eventId) || queue.any { it.voice.voiceId == payload.voiceId } || _state.value.past.any { it.voiceId == payload.voiceId }) {
                return@withLock null
            }
            while (seen.size > MAX_SEEN) seen.remove(seen.first())
            val member = groupState()?.members?.firstOrNull { it.vehicleId == e.event.sourceVehicleId }
            val v = ReceivedVoice(
                eventId = e.event.eventId, voiceId = payload.voiceId, groupId = gid, fromVehicleId = e.event.sourceVehicleId,
                nickname = member?.nickname, colorHex = member?.color, durationMs = payload.durationMs.toLong(), mime = payload.mime,
                envelopeTsMs = e.envelopeTsMs, speech = e.event.speech,
            )
            val age = serverNow() - e.envelopeTsMs
            _state.update { it.copy(received = it.received + 1) }
            if (age > freshnessMs) {
                DhLog.i("ptt: voice stale voiceId=${v.voiceId} age=${age / 1000}s → 지난 무전")
                addPast(v.copy(status = PastStatus.STALE))
                _state.update { it.copy(stale = it.stale + 1) }
                return@withLock v.voiceId
            }
            DhLog.i("ptt: voice received voiceId=${v.voiceId} age=${age / 1000}s dur=${v.durationMs}ms mime=${v.mime}")
            queue.addLast(Item(v, scope.async { fetchSafely(v) }, manual = false))
            _state.update { it.copy(queued = queue.size) }
            signal.trySend(Unit)
            v.voiceId
        }
    }

    private suspend fun fetchSafely(v: ReceivedVoice): File? = try {
        fetcher.fetch(v.groupId, v.voiceId, v.mime)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        DhLog.w("ptt: voice fetch 실패 ${e.javaClass.simpleName}")
        null
    }

    private suspend fun worker() {
        while (true) {
            signal.receive()
            while (true) {
                // 무전 보내기 중이면 끝날 때까지 기다린다
                held.first { !it }
                val (item, version) = mutex.withLock { queue.firstOrNull() to queueVersion.value }
                if (item == null) break
                if (item.resume && !resumeGate.value) {
                    // 끊긴 무전 — 내 무전 흐름이 IDLE 로 끝날 때까지(뒤의 다른 대기 무전도 그 뒤에). 문이 열리거나 큐 맨 앞이 바뀌면 다시 본다.
                    // 상한까지 풀리지 않으면 스스로 연다 — 뒤의 무전이 영영 막히지 않게(그 끊긴 무전은 아래에서 상한으로 건너뛴다)
                    val left = (item.interruptedAtMs + resumeMaxWaitMs - serverNow()).coerceAtLeast(0)
                    val woke = withTimeoutOrNull(left + 1) {
                        combine(resumeGate, queueVersion) { open, v -> open || v != version }.first { it }
                    }
                    if (woke == null && !resumeGate.value) {
                        resumeGate.value = true
                        _state.update { it.copy(resumeWaiting = false) }
                        DhLog.w("ptt: resume gate timeout (${resumeMaxWaitMs / 1000}s) → 엶")
                    }
                    continue
                }
                val file = try {
                    item.file.await()
                } catch (e: CancellationException) {
                    // 그룹이 바뀌어 이 항목의 내려받기가 취소됐다(onGroupChanged). 워커 자신이 멈춘 게 아니면 버리고 다음으로 — 워커가 끝나면 이후 무전이 하나도 재생되지 않는다
                    currentCoroutineContext().ensureActive()
                    mutex.withLock {
                        queue.remove(item)
                        publishQueue()
                    }
                    DhLog.i("ptt: voice dropped (download cancelled) voiceId=${item.voice.voiceId}")
                    continue
                }
                // 기다리는 사이 탭 다시 듣기가 이 항목을 대신했으면(같은 파일) 큐에 없다
                val stillQueued = mutex.withLock { queue.remove(item).also { publishQueue() } }
                if (item.voice.groupId != session.value?.groupId) {
                    // 내려받기가 끝나는 사이 그룹이 바뀌었다 — 끝난 그룹의 무전은 재생하지 않는다
                    file?.delete()
                    DhLog.i("ptt: voice dropped (group changed) voiceId=${item.voice.voiceId}")
                    continue
                }
                if (!stillQueued) continue
                if (file == null) {
                    DhLog.w("ptt: voice download failed voiceId=${item.voice.voiceId}")
                    mutex.withLock { addPast(item.voice.copy(status = PastStatus.DOWNLOAD_FAILED)) }
                    _state.update { it.copy(downloadFailed = it.downloadFailed + 1) }
                    continue
                }
                if (held.value) {
                    // 내려받는 사이 무전 보내기가 시작됐다 — 맨 앞에 다시 세우고 기다린다
                    mutex.withLock {
                        queue.addFirst(Item(item.voice, CompletableDeferred(file), item.manual, item.resume, item.interruptedAtMs))
                        publishQueue()
                    }
                    continue
                }
                if (item.resume) {
                    // 끊긴 무전 — 60초 신선도 대신 끊긴 때부터 상한(2분). 넘으면 '다 못 들음'으로 남긴다(탭 재생)
                    val waited = serverNow() - item.interruptedAtMs
                    if (waited > resumeMaxWaitMs) {
                        DhLog.i("ptt: resume skipped voiceId=${item.voice.voiceId} waited=${waited / 1000}s (> ${resumeMaxWaitMs / 1000}s) → 지난 무전(다 못 들음)")
                        mutex.withLock { if (_state.value.past.none { it.voiceId == item.voice.voiceId }) file.delete() }
                        continue
                    }
                    DhLog.i("ptt: resume voiceId=${item.voice.voiceId} waited=${waited / 1000}s → 처음부터")
                    // 다시 트는 동안은 '지난 무전'에서 뺀다(끝나면 결과 상태로 다시 들어간다)
                    mutex.withLock { _state.update { s -> s.copy(past = s.past.filterNot { it.voiceId == item.voice.voiceId }) } }
                } else {
                    val age = serverNow() - item.voice.envelopeTsMs
                    if (!item.manual && age > freshnessMs) {
                        DhLog.i("ptt: voice stale at play voiceId=${item.voice.voiceId} age=${age / 1000}s → 지난 무전")
                        mutex.withLock { addPast(item.voice.copy(status = PastStatus.STALE, file = file)) }
                        _state.update { it.copy(stale = it.stale + 1) }
                        continue
                    }
                }
                playOne(item, file)
                val more = mutex.withLock { queue.isNotEmpty() }
                if (more) {
                    _state.update { it.copy(playing = null) }
                    delay(gapMs)
                } else {
                    // 끝나고 3초 머문다 — 그사이 새 무전이 오면 바로 다음으로
                    withTimeoutOrNull(lingerMs) { signal.receive() }
                    _state.update { s -> if (s.playing?.ended == true) s.copy(playing = null) else s }
                }
            }
        }
    }

    private suspend fun playOne(item: Item, file: File) {
        val v = item.voice
        interruptedBy = null
        resumeRequested = false
        playingNow = item
        _state.update { it.copy(playing = PlayingVoice(v, 0, ended = false)) }
        DhLog.i("ptt: play start voiceId=${v.voiceId} manual=${item.manual} resume=${item.resume}")
        val outcome = try {
            coroutineScope {
                // LAZY: 코루틴을 [currentPlay] 에 걸어 둔 뒤 시작한다 — 그 사이 온 interrupt 는 아래 사유 확인이 받는다
                val play = async(start = CoroutineStart.LAZY) { announceAndPlay(item, file) }
                currentPlay = play
                if (interruptedBy != null) play.cancel()
                play.await()
            }
        } catch (e: CancellationException) {
            // 끊김(interrupt·그룹 변경)은 Stopped. 워커 자신이 멈춘 것(stop())이면 그대로 끝낸다
            currentCoroutineContext().ensureActive()
            PlayOutcome.Stopped
        } finally {
            currentPlay = null
            playingNow = null
        }
        finishPlay(item, file, outcome)
    }

    /**
     * "○○ 무전" 알림(엔진이 없으면 즉시) → 재생. 알림을 상한([announceTimeoutMs]) 안에 다 읽지 못했거나(TIMEOUT) 끊겨 취소되면 **그 알림 문장만** 버린다 —
     * 체인의 speakAndWait 가 그 꼬리표를 취소한다(B012: 늦게 나온 알림이 녹음 재생 위에 겹치거나 새 녹음에 섞이지 않게). 전에는 취소 때 TTS 큐 전체를
     * 비워(`voice.stop()`) 같은 때 읽던 급제동(HIGH) 안내까지 지웠다.
     */
    private suspend fun announceAndPlay(item: Item, file: File): PlayOutcome {
        val v = item.voice
        if (announceEnabled()) {
            val text = v.speech?.trim()?.takeIf { it.isNotEmpty() } ?: PttPrompts.incoming(v.nickname)
            currentCoroutineContext().ensureActive()
            try {
                val r = voice.speakAndWait(text, Priority.NORMAL, announceTimeoutMs)
                if (r == SpeakResult.TIMEOUT) DhLog.i("ptt: voice announce timeout ${announceTimeoutMs}ms → 알림 버리고 재생 voiceId=${v.voiceId}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DhLog.w("ptt: voice announce 실패 ${e.javaClass.simpleName}")
            }
        }
        if (interruptedBy != null) return PlayOutcome.Stopped
        DhLog.i("ptt: playing from=${tail(v.fromVehicleId)} voiceId=${v.voiceId} dur=${v.durationMs}ms manual=${item.manual} resume=${item.resume}")
        return player.play(file) { pos ->
            _state.update { s -> if (s.playing?.voice?.voiceId == v.voiceId) s.copy(playing = s.playing.copy(positionMs = pos)) else s }
        }
    }

    private suspend fun finishPlay(item: Item, file: File, outcome: PlayOutcome) {
        val v = item.voice
        val status = when (outcome) {
            PlayOutcome.Completed -> PastStatus.PLAYED
            PlayOutcome.Stopped -> PastStatus.INTERRUPTED
            is PlayOutcome.Failed -> PastStatus.PLAY_FAILED
        }
        val sameGroup = session.value?.groupId == v.groupId
        // 무전 버튼으로 끊었다 → 내 흐름이 끝나면 처음부터 다시(그룹이 끝나 멈춘 것은 아니다)
        val resume = outcome == PlayOutcome.Stopped && resumeRequested && sameGroup
        when (outcome) {
            PlayOutcome.Completed -> DhLog.i("ptt: playback done voiceId=${v.voiceId} resume=${item.resume}")
            PlayOutcome.Stopped -> DhLog.i(
                "ptt: playback interrupted voiceId=${v.voiceId} why=${interruptedBy ?: "stop"} status=INTERRUPTED" + if (resume) " resume=after-flow" else "",
            )
            is PlayOutcome.Failed -> DhLog.w("ptt: playback failed voiceId=${v.voiceId} reason=${outcome.reason}")
        }
        if (!sameGroup) {
            // 재생하는 사이 그룹이 끝났다 — 목록에 남기지 않는다
            file.delete()
            _state.update { it.copy(playing = null) }
            return
        }
        mutex.withLock {
            addPast(v.copy(status = status, file = file))
            if (resume) {
                // '다 못 들음'으로 목록에 두고, 같은 파일로 큐 맨 앞에 '다시 틀 것'을 세운다 — 문([resumeGate])은 interrupt 가 이미 닫았다
                queue.removeAll { it.voice.voiceId == v.voiceId }
                queue.addFirst(Item(v.copy(status = PastStatus.QUEUED), CompletableDeferred(file), manual = true, resume = true, interruptedAtMs = serverNow()))
                queueVersion.value++
                publishQueue()
            }
        }
        _state.update {
            it.copy(
                played = it.played + if (status == PastStatus.PLAYED) 1 else 0,
                playFailed = it.playFailed + if (status == PastStatus.PLAY_FAILED) 1 else 0,
                playing = it.playing?.takeIf { p -> p.voice.voiceId == v.voiceId }?.copy(ended = true, positionMs = if (status == PastStatus.PLAYED) v.durationMs else it.playing.positionMs),
            )
        }
    }

    /** [mutex] 안에서 부른다(past 는 mutex 안에서만 바뀐다). 최근 [MAX_PAST]개 — 넘친 것은 캐시 파일도 지운다(큐가 아직 쓰는 파일은 남긴다) */
    private fun addPast(v: ReceivedVoice) {
        val list = listOf(v) + _state.value.past.filterNot { it.voiceId == v.voiceId }
        val over = list.drop(MAX_PAST)
        over.forEach { old -> if (queue.none { it.voice.voiceId == old.voiceId }) old.file?.delete() }
        if (over.isNotEmpty()) DhLog.i("ptt: past over ${MAX_PAST} → 오래된 ${over.size}개 버림(캐시 파일 포함)")
        _state.update { it.copy(past = list.take(MAX_PAST)) }
    }

    private suspend fun onGroupChanged() {
        val (dropped, past) = mutex.withLock {
            val q = queue.toList()
            queue.clear()
            seen.clear()
            // 끊겨 다시 틀 차례를 기다리던 것도 버린다 — 문을 열어 기다리는 워커를 깨운다
            resumeGate.value = true
            queueVersion.value++
            val p = _state.value.past
            // 재생 중인 것은 finishPlay 가 정리한다(그룹이 다르면 목록에 남기지 않는다), 끝나 머무는 것은 바로 지운다
            _state.update { VoicePlaybackState(held = it.held, playing = it.playing?.takeIf { p -> !p.ended }) }
            q to p
        }
        dropped.forEach { it.file.cancel() }
        // 재생 중(알림을 읽는 중 포함)이면 멈춘다 — finishPlay 가 그룹이 다른 것을 보고 목록에 남기지 않고 파일을 지운다
        if (playingNow != null) stopCurrent("group-changed") else player.stop()
        past.forEach { it.file?.delete() }
        cacheDir?.listFiles()?.forEach { it.delete() }
        if (dropped.isNotEmpty() || past.isNotEmpty()) {
            DhLog.i("ptt: group changed → voice queue=${dropped.size}(resume=${dropped.count { it.resume }}) past=${past.size} cleared (cache 포함)")
        }
    }

    private fun tail(vehicleId: String?): String = vehicleId?.takeLast(4) ?: "?"

    companion object {
        const val FRESHNESS_MS = 60_000L
        const val LINGER_MS = 3_000L
        const val GAP_MS = 500L
        const val ANNOUNCE_TIMEOUT_MS = 4_000L
        /** '지난 무전' 상한 — 최근 20개(사용자 결정 2026-10-28, 다 들은 것 포함) */
        const val MAX_PAST = 20
        const val MAX_SEEN = 128
        /** 끊긴 무전을 다시 트는 상한 — 끊긴 때부터 2분(녹음 15 s + 확인 5~6.5 s + 전송 + 안내 3 s 를 몇 번 이어 눌러도 넉넉) */
        const val RESUME_MAX_WAIT_MS = 120_000L
    }
}
