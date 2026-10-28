package app.donghaeng.car.domain.ptt

import app.donghaeng.car.adapter.audio.PlayOutcome
import app.donghaeng.car.adapter.audio.VoiceClipPlayer
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
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    /** 재생 중 무전 버튼을 눌러 끊었다 */
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
    /** 재생을 기다리는 수(내려받는 중 포함) */
    val queued: Int = 0,
    /** 무전 보내기(녹음·확인·전송) 중이라 재생을 미루는 중 */
    val held: Boolean = false,
    /** '지난 무전'(최신 먼저, 최대 [VoicePlayback.MAX_PAST]) — 재생한 것·60초 넘은 것·끊은 것 */
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
 * 6. 재생 중 무전 버튼을 누르면 [interrupt] — 멈추고 INTERRUPTED 로 '지난 무전'에 둔다(탭 다시 듣기).
 * 7. 그룹이 바뀌거나 끝나면 큐·목록·캐시 파일을 지운다(서버도 그룹 종료 때 지운다).
 *
 * 로그: `ptt: voice received voiceId=… age=…s dur=…ms`, `ptt: playing from=<vehicleId 끝 4자리> voiceId=… dur=…`, `ptt: voice stale …`, `ptt: voice skip own|not-for-me`.
 * 닉네임·오디오 내용은 남기지 않는다(규칙 8).
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
) {
    private class Item(val voice: ReceivedVoice, val file: Deferred<File?>, val manual: Boolean)

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var job: Job? = null
    private val mutex = Mutex()
    private val queue = ArrayDeque<Item>()
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val seen = LinkedHashSet<String>()
    private val held = MutableStateFlow(false)
    @Volatile private var playingNow: Item? = null
    @Volatile private var interruptedBy: String? = null

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

    /** 재생 중인 무전을 끊는다(무전 버튼을 눌러 녹음을 시작할 때) — '지난 무전'에 INTERRUPTED 로 남는다 */
    fun interrupt(why: String) {
        if (playingNow == null) return
        interruptedBy = why
        player.stop()
    }

    /** '지난 무전' 다시 듣기(정차 대시보드 탭) — 파일이 없으면 내려받는다. 신선도는 보지 않는다. 큐에 넣었으면 true */
    suspend fun replay(voiceId: String): Boolean = mutex.withLock {
        val v = _state.value.past.firstOrNull { it.voiceId == voiceId } ?: return@withLock false
        val file = v.file?.takeIf { it.isFile }
        val deferred: Deferred<File?> = if (file != null) CompletableDeferred(file) else scope.async { fetchSafely(v) }
        queue.addFirst(Item(v.copy(status = PastStatus.QUEUED), deferred, manual = true))
        _state.update { s -> s.copy(queued = queue.size, past = s.past.filterNot { it.voiceId == voiceId }) }
        DhLog.i("ptt: replay voiceId=$voiceId cached=${file != null}")
        signal.trySend(Unit)
        true
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
                val item = mutex.withLock { queue.firstOrNull() } ?: break
                val file = item.file.await()
                mutex.withLock {
                    queue.remove(item)
                    _state.update { it.copy(queued = queue.size) }
                }
                if (file == null) {
                    DhLog.w("ptt: voice download failed voiceId=${item.voice.voiceId}")
                    mutex.withLock { addPast(item.voice.copy(status = PastStatus.DOWNLOAD_FAILED)) }
                    _state.update { it.copy(downloadFailed = it.downloadFailed + 1) }
                    continue
                }
                if (held.value) {
                    // 내려받는 사이 무전 보내기가 시작됐다 — 맨 앞에 다시 세우고 기다린다
                    mutex.withLock { queue.addFirst(Item(item.voice, CompletableDeferred(file), item.manual)); _state.update { it.copy(queued = queue.size) } }
                    continue
                }
                val age = serverNow() - item.voice.envelopeTsMs
                if (!item.manual && age > freshnessMs) {
                    DhLog.i("ptt: voice stale at play voiceId=${item.voice.voiceId} age=${age / 1000}s → 지난 무전")
                    mutex.withLock { addPast(item.voice.copy(status = PastStatus.STALE, file = file)) }
                    _state.update { it.copy(stale = it.stale + 1) }
                    continue
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
        playingNow = item
        interruptedBy = null
        _state.update { it.copy(playing = PlayingVoice(v, 0, ended = false)) }
        try {
            if (announceEnabled()) {
                val text = v.speech?.trim()?.takeIf { it.isNotEmpty() } ?: PttPrompts.incoming(v.nickname)
                runCatching { voice.speakAndWait(text, Priority.NORMAL, announceTimeoutMs) }
            }
            if (interruptedBy != null) {
                finishPlay(v, file, PlayOutcome.Stopped)
                return
            }
            DhLog.i("ptt: playing from=${tail(v.fromVehicleId)} voiceId=${v.voiceId} dur=${v.durationMs}ms manual=${item.manual}")
            val outcome = player.play(file) { pos ->
                _state.update { s -> if (s.playing?.voice?.voiceId == v.voiceId) s.copy(playing = s.playing.copy(positionMs = pos)) else s }
            }
            finishPlay(v, file, outcome)
        } finally {
            playingNow = null
        }
    }

    private suspend fun finishPlay(v: ReceivedVoice, file: File, outcome: PlayOutcome) {
        val status = when (outcome) {
            PlayOutcome.Completed -> PastStatus.PLAYED
            PlayOutcome.Stopped -> PastStatus.INTERRUPTED
            is PlayOutcome.Failed -> PastStatus.PLAY_FAILED
        }
        when (outcome) {
            PlayOutcome.Completed -> DhLog.i("ptt: playback done voiceId=${v.voiceId}")
            PlayOutcome.Stopped -> DhLog.i("ptt: playback interrupted voiceId=${v.voiceId} why=${interruptedBy ?: "stop"}")
            is PlayOutcome.Failed -> DhLog.w("ptt: playback failed voiceId=${v.voiceId} reason=${outcome.reason}")
        }
        if (session.value?.groupId != v.groupId) {
            // 재생하는 사이 그룹이 끝났다 — 목록에 남기지 않는다
            file.delete()
            _state.update { it.copy(playing = null) }
            return
        }
        mutex.withLock { addPast(v.copy(status = status, file = file)) }
        _state.update {
            it.copy(
                played = it.played + if (status == PastStatus.PLAYED) 1 else 0,
                playFailed = it.playFailed + if (status == PastStatus.PLAY_FAILED) 1 else 0,
                playing = it.playing?.takeIf { p -> p.voice.voiceId == v.voiceId }?.copy(ended = true, positionMs = if (status == PastStatus.PLAYED) v.durationMs else it.playing.positionMs),
            )
        }
    }

    /** [mutex] 안에서 부른다(past 는 mutex 안에서만 바뀐다) */
    private fun addPast(v: ReceivedVoice) {
        val list = listOf(v) + _state.value.past.filterNot { it.voiceId == v.voiceId }
        list.drop(MAX_PAST).forEach { it.file?.delete() }
        _state.update { it.copy(past = list.take(MAX_PAST)) }
    }

    private suspend fun onGroupChanged() {
        val (dropped, past) = mutex.withLock {
            val q = queue.toList()
            queue.clear()
            seen.clear()
            val p = _state.value.past
            // 재생 중인 것은 finishPlay 가 정리한다(그룹이 다르면 목록에 남기지 않는다), 끝나 머무는 것은 바로 지운다
            _state.update { VoicePlaybackState(held = it.held, playing = it.playing?.takeIf { p -> !p.ended }) }
            q to p
        }
        dropped.forEach { it.file.cancel() }
        player.stop()
        past.forEach { it.file?.delete() }
        cacheDir?.listFiles()?.forEach { it.delete() }
        if (dropped.isNotEmpty() || past.isNotEmpty()) DhLog.i("ptt: group changed → voice queue=${dropped.size} past=${past.size} cleared")
    }

    private fun tail(vehicleId: String?): String = vehicleId?.takeLast(4) ?: "?"

    companion object {
        const val FRESHNESS_MS = 60_000L
        const val LINGER_MS = 3_000L
        const val GAP_MS = 500L
        const val ANNOUNCE_TIMEOUT_MS = 4_000L
        const val MAX_PAST = 10
        const val MAX_SEEN = 128
    }
}
