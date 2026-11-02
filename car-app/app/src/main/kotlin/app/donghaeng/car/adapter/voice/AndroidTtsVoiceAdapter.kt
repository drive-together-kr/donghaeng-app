package app.donghaeng.car.adapter.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import app.donghaeng.car.core.common.DhLog
import app.donghaeng.car.domain.model.Priority
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * 체인 1순위 후보 — Android `TextToSpeech` (T04 골격, T27 완성, R19 체인 2026-10-28).
 *
 * - **사용 가능 조건**: 초기화 SUCCESS **그리고** `isLanguageAvailable(Locale.KOREA)`·`setLanguage(Locale.KOREA)` 가 지원(≥ LANG_AVAILABLE).
 *   아니면 `ready=false`(사유를 `detail` 에) → 체인이 Pleos TTS·화면 표시로 넘어간다. Pleos AVD 는 시스템 엔진(ai.pleos.playground.caas)이
 *   TTS_SERVICE 필터에 category DEFAULT 가 없어 여기서 status -1 이 난다(docs/10 R19 — 그래서 Pleos SDK 경로가 있다).
 * - 오디오: [AudioFocusGuard](`USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`, 말하기 전 `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`, 마지막 문장이 끝나면 반납).
 *   문장 앞에 **선행 묵음 200 ms**(Pleos 가이드) — 블루투스/앰프가 깨어나며 첫 음절이 잘리는 것을 막는다.
 * - [Priority.CRITICAL] 은 `QUEUE_FLUSH` 로 재생 중인 안내를 끊는다. 큐 순서·신선도는 `VoiceAnnouncer` 가 결정한다.
 * - 큐·신호·포커스 계산은 순수 클래스 [AndroidTtsQueue](엔진 = [TtsEnginePort] — JVM 단위 테스트)가 한다. 이 클래스는 TextToSpeech 생성·초기화·콜백 연결만.
 * - 진행 신호([signals]): **Started = 실제 소리 시작** — 본문 utterance 의 재생 차례(`onStart`)와 엔진 오디오 첫 조각(`onAudioAvailable`, API 24+)이
 *   **둘 다 온 시각(늦은 쪽)**. AOSP `SynthesisPlaybackQueueItem.run()` 은 `onStart` 를 먼저 보내고 엔진 오디오를 기다리므로(sherpa-onnx 는
 *   문장 합성을 끝낸 뒤에 오디오를 넘긴다) `onStart` 만 보면 합성이 3 s 넘게 걸려도 지연이 0.2~0.3 s 로 보인다(리뷰 2026-10-28).
 *   오디오 조각 신호 없이 끝나면(onDone) `onStart` 시각으로 대신한다. 지연 = 요청 → 그 시각(선행 묵음 200 ms 포함).
 *   `onDone` → Done, `onError` → Failed, 끊김 → Dropped.
 * - [cancel](꼬리표 하나만 버림, B012·B013): Android TTS 는 큐에서 문장 하나만 지울 수 없다 → 그 문장이 **재생 차례가 되는 순간**(묵음·본문 onStart)
 *   엔진을 멈추고, 그 뒤에 줄 서 있던 문장은 같은 꼬리표·요청 시각으로 다시 넣는다(이미 읽는 중이면 바로 멈추고 같은 방법). 그 문장에는 바로 Dropped("cancelled").
 * - F16: [speakAndWait] 는 같은 큐에 넣고 그 utterance 의 onDone/onError 를 기다린다(엔진 없음 → 즉시 [SpeakResult.FALLBACK], [stop] → 실패로 풀림).
 *   CRITICAL(`QUEUE_FLUSH`)이 큐를 비우면 지워진 문장에는 onDone·onError 가 오지 않고 **onStop** 만 온다 → 그때도 실패로 풀어 준다
 *   (재생 차례 전에 지워진 선행 묵음에는 그 onStop 도 없다 — 큐가 미리 뺀다, RB-2).
 *   앱이 쓰는 체인([ChainedVoiceAdapter])은 경로와 무관하게 [signals] 로 같은 일을 한다.
 * - 오디오 포커스는 '콜백을 기다리는 utterance' 가 하나도 없을 때 반납한다(B050·B063 — speak·묵음이 ERROR 를 돌려주면 그 몫은 바로 뺀다,
 *   엔진이 재생 도중 죽어 콜백이 끝내 오지 않으면 [AndroidTtsQueue.STALE_MS] 뒤 정리).
 *
 * 문장 원문은 **어떤 빌드에서도** 로그에 남기지 않는다(닉네임 포함, CLAUDE.md 규칙 8) — 길이와 utteranceId 만 남긴다.
 */
class AndroidTtsVoiceAdapter(context: Context) : VoiceRoute {

    private enum class State { INITIALIZING, READY, UNAVAILABLE }

    override val path: VoicePath = VoicePath.ANDROID_TTS

    private val appContext = context.applicationContext
    private val state = AtomicReference(State.INITIALIZING)
    private val guard = AudioFocusGuard(appContext)
    private val main = Handler(Looper.getMainLooper())

    private val _status = MutableStateFlow(VoiceAdapterStatus(engine = "Android TextToSpeech"))
    override val status: StateFlow<VoiceAdapterStatus> = _status

    private val _signals = MutableSharedFlow<UtteranceSignal>(extraBufferCapacity = 64)
    override val signals: SharedFlow<UtteranceSignal> = _signals

    private val tts: TextToSpeech? = runCatching {
        TextToSpeech(appContext) { status -> onInit(status) }
    }.getOrElse { t ->
        DhLog.w("TTS 생성 실패 → 다음 경로", t)
        unavailable("생성 실패 ${t.javaClass.simpleName}")
        null
    }

    private val queue = AndroidTtsQueue(
        path = path,
        engine = object : TtsEnginePort {
            override fun playSilence(durationMs: Long, queueMode: Int, utteranceId: String): Int =
                tts?.playSilentUtterance(durationMs, queueMode, utteranceId) ?: TextToSpeech.ERROR
            override fun speak(text: String, queueMode: Int, utteranceId: String): Int =
                tts?.speak(text, queueMode, null, utteranceId) ?: TextToSpeech.ERROR
            override fun stop() {
                runCatching { tts?.stop() }
            }
        },
        focus = object : AudioFocusPort {
            override fun request(): Boolean = guard.request().also { g -> _status.update { it.copy(focusGranted = g) } }
            override fun abandon() { guard.abandon(); _status.update { it.copy(focusGranted = false) } }
        },
        clock = System::currentTimeMillis,
        emit = { s ->
            if (s is UtteranceSignal.Failed) _status.update { it.copy(failedCount = it.failedCount + 1) }
            _signals.tryEmit(s)
        },
    )

    private val staleCheck = Runnable { queue.purgeStale() }

    private fun unavailable(detail: String, engine: String? = null) {
        state.set(State.UNAVAILABLE)
        _status.update { it.copy(ready = false, detail = detail, engine = engine ?: it.engine) }
    }

    private fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            DhLog.w("TTS 초기화 실패 status=$status → 다음 경로 (한국어 엔진 없음?)")
            unavailable("초기화 실패 status=$status (엔진 없음)")
            return
        }
        val engine = tts ?: return
        val engineName = runCatching { engine.defaultEngine }.getOrNull()
        // ko_KR 이 실제로 쓸 수 있는지(isLanguageAvailable) + 설정(setLanguage). LANG_AVAILABLE(0) 이상이면 한국어 음성이 있다
        val available = runCatching { engine.isLanguageAvailable(Locale.KOREA) }.getOrDefault(TextToSpeech.ERROR)
        if (available < TextToSpeech.LANG_AVAILABLE) {
            DhLog.w("TTS ko_KR 미지원(isLanguageAvailable=$available, engine=$engineName) → 다음 경로")
            unavailable("ko_KR 미지원 isLanguageAvailable=$available", engineName)
            return
        }
        val set = runCatching { engine.setLanguage(Locale.KOREA) }.getOrDefault(TextToSpeech.ERROR)
        if (set < TextToSpeech.LANG_AVAILABLE) {
            DhLog.w("TTS ko_KR 설정 실패(setLanguage=$set, engine=$engineName) → 다음 경로")
            unavailable("ko_KR 설정 실패 setLanguage=$set", engineName)
            return
        }
        runCatching { engine.setAudioAttributes(AudioFocusGuard.NAV_GUIDANCE) }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = queue.onStart(utteranceId)
            override fun onAudioAvailable(utteranceId: String?, audio: ByteArray?) = queue.onAudio(utteranceId)
            override fun onDone(utteranceId: String?) = queue.onDone(utteranceId)
            @Deprecated("API 21 미만 호환용")
            override fun onError(utteranceId: String?) = queue.onError(utteranceId, "onError")
            override fun onError(utteranceId: String?, errorCode: Int) = queue.onError(utteranceId, "onError code=$errorCode")
            override fun onStop(utteranceId: String?, interrupted: Boolean) = queue.onStop(utteranceId)
        })
        state.set(State.READY)
        _status.update { it.copy(ready = true, detail = "ko_KR 준비 (isLanguageAvailable=$available)", engine = engineName ?: it.engine) }
        DhLog.i("TTS 준비 완료 (Android TextToSpeech ${engineName ?: ""}, ko-KR)")
    }

    override suspend fun speak(text: String, priority: Priority) = speak(text, priority, null)

    override suspend fun speak(text: String, priority: Priority, tag: String?) {
        enqueue(text, priority, tag, waiter = null)
    }

    override suspend fun speakAndWait(text: String, priority: Priority, timeoutMs: Long): SpeakResult {
        val done = CompletableDeferred<Boolean>()
        return when (enqueue(text, priority, tag = null, waiter = done)) {
            AndroidTtsQueue.Enqueued.FALLBACK -> SpeakResult.FALLBACK
            AndroidTtsQueue.Enqueued.FAILED -> SpeakResult.FAILED
            AndroidTtsQueue.Enqueued.QUEUED -> when (withTimeoutOrNull(timeoutMs) { done.await() }) {
                true -> SpeakResult.SPOKEN
                false -> SpeakResult.FAILED
                null -> SpeakResult.TIMEOUT
            }
        }
    }

    private fun enqueue(text: String, priority: Priority, tag: String?, waiter: CompletableDeferred<Boolean>?): AndroidTtsQueue.Enqueued {
        if (tts == null || state.get() != State.READY) {
            // 체인은 준비된 후보에만 넘긴다 — 여기 오면 경쟁 상태(방금 불가로 바뀜). 실패로 알려 체인이 센다
            DhLog.i("TTS 준비 안 됨 [$priority] (len=${text.length})")
            _status.update { it.copy(fallbackCount = it.fallbackCount + 1, lastUtteranceMs = System.currentTimeMillis()) }
            _signals.tryEmit(UtteranceSignal.Failed(path, tag, System.currentTimeMillis(), "준비 안 됨"))
            return AndroidTtsQueue.Enqueued.FALLBACK
        }
        val r = queue.enqueue(text, priority, tag, waiter)
        if (r == AndroidTtsQueue.Enqueued.QUEUED) {
            _status.update { it.copy(spokenCount = it.spokenCount + 1, lastUtteranceMs = System.currentTimeMillis()) }
            // 엔진이 재생 도중 죽어 콜백이 끝내 오지 않아도 포커스(덕킹)가 남지 않게 — 상한 뒤 한 번 정리
            main.removeCallbacks(staleCheck)
            main.postDelayed(staleCheck, AndroidTtsQueue.STALE_MS + STALE_CHECK_SLACK_MS)
        }
        return r
    }

    override fun stop() = queue.stopAll()

    override fun cancel(tag: String) = queue.cancel(tag)

    /** 자원 해제 — Application 수명과 같이 가므로 보통 호출하지 않는다. */
    fun shutdown() {
        runCatching { tts?.shutdown() }
    }

    internal companion object {
        const val PRE_SILENCE_MS = 200L
        private const val STALE_CHECK_SLACK_MS = 1_000L

        /**
         * 실제 소리 시작 시각 — 재생 차례(onStart)와 엔진 오디오 도착(첫 onAudioAvailable) 중 **늦은 쪽**, 둘 다 와야 정해진다(null = 아직).
         * 합성이 빠르면 오디오가 선행 묵음 중에 먼저 와 onStart 가 시작, 느리면(sherpa-onnx 부하) 오디오 도착이 시작이다.
         */
        internal fun soundStartAt(startAt: Long?, audioAt: Long?): Long? =
            if (startAt != null && audioAt != null) maxOf(startAt, audioAt) else null

        /** 로그용 요약 — 문장 원문 대신 길이와 utteranceId 만 (규칙 8). 빌드 타입과 무관하게 항상 동일. */
        internal fun describe(text: String, utteranceId: String): String = "(len=${text.length} id=$utteranceId)"
    }
}

/** [AndroidTtsQueue] 가 쓰는 TextToSpeech 창구 — 반환값은 `TextToSpeech.SUCCESS`(0)/`ERROR`(-1). 단위 테스트에서 가짜로 바꾼다 */
internal interface TtsEnginePort {
    fun playSilence(durationMs: Long, queueMode: Int, utteranceId: String): Int
    fun speak(text: String, queueMode: Int, utteranceId: String): Int
    fun stop()
}

/**
 * Android TTS 큐 상태(순수 — 엔진·포커스·시계·신호는 주입, JVM 단위 테스트). 엔진 콜백 스레드와 호출 스레드가 같이 쓰므로 상태는 [lock] 안에서만 바꾸고,
 * 엔진 호출(바인더)·신호는 잠금 밖에서 한다.
 *
 * - 문장 한 개 = 선행 묵음 utterance(`dh-N-s`) + 본문(`dh-N`). id 는 잠금 안에서 매긴다(B065 — 동시 speak 에서 id 가 겹쳐 신호를 잃지 않게).
 * - **포커스**: 콜백을 기다리는 utterance([entries])가 생길 때 요청하고, 하나도 없으면 반납한다. `playSilentUtterance`·`speak` 가 ERROR 를 돌려주면
 *   그 utterance 는 콜백이 오지 않으므로 바로 뺀다(B050 — 전에는 묵음 몫 1 이 남아 음악이 줄어든 채였다). onStop 도 뺀다(CRITICAL 의 QUEUE_FLUSH·stop).
 *   CRITICAL 의 QUEUE_FLUSH 는 재생 차례 전 묵음에 콜백을 주지 않으므로 그 몫은 보내기 전에 미리 뺀다(RB-2, [forgetQueuedSilencesLocked]).
 *   [STALE_MS] 넘게 콜백이 없는 utterance(엔진이 재생 도중 죽음)는 [purgeStale] 이 정리한다(B063).
 * - [cancel]: 위 클래스 설명.
 */
internal class AndroidTtsQueue(
    private val path: VoicePath,
    private val engine: TtsEnginePort,
    private val focus: AudioFocusPort,
    private val clock: () -> Long,
    private val emit: (UtteranceSignal) -> Unit,
    private val preSilenceMs: Long = AndroidTtsVoiceAdapter.PRE_SILENCE_MS,
    private val staleMs: Long = STALE_MS,
) {
    enum class Enqueued { QUEUED, FALLBACK, FAILED }

    /** 본문 한 개(묵음 포함 한 문장). [order] = 넣은 순서(다시 넣을 때 순서를 지킨다) */
    private class Sentence(
        val order: Long,
        val text: String,
        val priority: Priority,
        val tag: String?,
        val requestedAt: Long,
        var waiter: CompletableDeferred<Boolean>?,
    ) {
        var bodyId: String = ""
        var silenceId: String? = null
        /** 마지막으로 엔진에 넣은 시각(다시 넣으면 갱신) — 콜백 상한([STALE_MS]) 판단 */
        var queuedAt: Long = requestedAt
        var startAt: Long? = null
        var audioAt: Long? = null
        var signaled = false
        /** 재생 차례가 됐다(묵음 또는 본문 onStart) */
        var playing = false
        /** [cancel] 됨 — 재생 차례가 되면 엔진을 멈춘다 */
        var cancelled = false
    }

    private val lock = Any()
    private var seq = 0L
    /** 콜백을 기다리는 utterance id → 넣은 시각(포커스 계산) */
    private val entries = LinkedHashMap<String, Long>()
    /** 본문 utterance id → 문장 */
    private val sentences = LinkedHashMap<String, Sentence>()
    /** 묵음 utterance id → 문장 */
    private val silences = HashMap<String, Sentence>()
    private var focusHeld = false

    /** 콜백을 기다리는 utterance 수(테스트·진단) */
    val outstanding: Int get() = synchronized(lock) { entries.size }

    fun enqueue(text: String, priority: Priority, tag: String?, waiter: CompletableDeferred<Boolean>? = null): Enqueued {
        purgeStale()
        val critical = priority == Priority.CRITICAL
        val s = synchronized(lock) {
            if (critical) forgetQueuedSilencesLocked()
            Sentence(seq++, text, priority, tag, clock(), waiter)
        }
        return submit(s, if (critical) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, fresh = true)
    }

    /**
     * CRITICAL 의 `QUEUE_FLUSH` 직전(잠금 안) — 줄 서 있던 문장들의 **선행 묵음** 몫을 미리 뺀다(RB-2). AOSP 는 재생 차례 전에 지워진 묵음
     * (`SilenceSpeechItem.stopImpl` 은 빈 함수, `SilencePlaybackQueueItem.stop` 은 condvar 만 연다)에 onStop 도 onDone 도 보내지 않아, 남겨 두면
     * 포커스(덕킹)가 60 s 정리([purgeStale])까지 풀리지 않는다. 본문은 onStop 이 오므로(NOT_RUN → dispatchEndStatus) 그대로 두어 Dropped 를 낸다.
     * 재생 중이던 묵음의 늦은 onStop 은 모르는 id 라 무시된다([stopAndRefill] 과 같은 방식).
     */
    private fun forgetQueuedSilencesLocked() {
        if (silences.isEmpty()) return
        silences.keys.forEach { entries.remove(it) }
        silences.values.forEach { it.silenceId = null }
        silences.clear()
    }

    /** 문장 하나를 엔진에 넣는다(묵음 + 본문). [fresh] = 처음 넣음(다시 넣기면 실패 로그만 다르다) */
    private fun submit(s: Sentence, queueMode: Int, fresh: Boolean): Enqueued {
        synchronized(lock) {
            val n = seq++
            s.bodyId = "dh-$n"
            s.silenceId = "dh-$n$SILENCE_SUFFIX"
            s.startAt = null; s.audioAt = null; s.signaled = false; s.playing = false
            val now = clock()
            s.queuedAt = now
            entries[s.silenceId!!] = now
            entries[s.bodyId] = now
            silences[s.silenceId!!] = s
            sentences[s.bodyId] = s
        }
        requestFocus()
        // 선행 묵음 200 ms — 실패하면(엔진 연결 끊김) 콜백이 오지 않으니 그 몫을 바로 뺀다. CRITICAL 의 QUEUE_FLUSH 는 본문이 대신 맡는다
        val silenceOk = engine.playSilence(preSilenceMs, queueMode, s.silenceId!!) == TextToSpeech.SUCCESS
        if (!silenceOk) synchronized(lock) { entries.remove(s.silenceId!!); silences.remove(s.silenceId!!); s.silenceId = null }
        val bodyOk = engine.speak(s.text, if (silenceOk) TextToSpeech.QUEUE_ADD else queueMode, s.bodyId) == TextToSpeech.SUCCESS
        if (bodyOk) {
            DhLog.d("TTS speak [${s.priority}] ${AndroidTtsVoiceAdapter.describe(s.text, s.bodyId)}${if (fresh) "" else " (다시 넣음)"}")
            return Enqueued.QUEUED
        }
        synchronized(lock) {
            entries.remove(s.bodyId)
            sentences.remove(s.bodyId)
        }
        DhLog.w("TTS speak 실패 [${s.priority}] ${AndroidTtsVoiceAdapter.describe(s.text, s.bodyId)}")
        s.waiter?.complete(false)
        emit(UtteranceSignal.Failed(path, s.tag, clock(), "speak result=ERROR"))
        releaseFocusIfIdle()
        return Enqueued.FAILED
    }

    fun onStart(utteranceId: String?) {
        if (utteranceId == null) return
        val stopFor: Sentence?
        var fire: Sentence? = null
        val now = clock()
        synchronized(lock) {
            val silence = silences[utteranceId]
            val s = silence ?: sentences[utteranceId] ?: return
            s.playing = true
            if (silence == null && s.startAt == null) {
                s.startAt = now
                if (!s.signaled && AndroidTtsVoiceAdapter.soundStartAt(s.startAt, s.audioAt) != null) { s.signaled = true; fire = s }
            }
            stopFor = s.takeIf { it.cancelled }
        }
        if (stopFor != null) {
            stopAndRefill(stopFor)
            return
        }
        fire?.let { emitStarted(it) }
    }

    fun onAudio(utteranceId: String?) {
        if (utteranceId == null) return
        var fire: Sentence? = null
        synchronized(lock) {
            val s = sentences[utteranceId] ?: return
            if (s.cancelled) return
            if (s.audioAt == null) s.audioAt = clock()
            if (!s.signaled && AndroidTtsVoiceAdapter.soundStartAt(s.startAt, s.audioAt) != null) { s.signaled = true; fire = s }
        }
        fire?.let { emitStarted(it) }
    }

    private fun emitStarted(s: Sentence) {
        val at = AndroidTtsVoiceAdapter.soundStartAt(s.startAt, s.audioAt) ?: clock()
        emit(UtteranceSignal.Started(path, s.tag, at, at - s.requestedAt))
    }

    fun onDone(utteranceId: String?) = finish(utteranceId, ok = true, reason = null)

    fun onError(utteranceId: String?, reason: String) {
        DhLog.w("TTS utterance 오류 id=$utteranceId $reason")
        finish(utteranceId, ok = false, reason = reason)
    }

    private fun finish(utteranceId: String?, ok: Boolean, reason: String?) {
        if (utteranceId == null) return
        val s: Sentence?
        synchronized(lock) {
            entries.remove(utteranceId)
            silences.remove(utteranceId)
            s = sentences.remove(utteranceId)
        }
        if (s != null && !s.cancelled) {
            s.waiter?.complete(ok)
            // 오디오 조각 신호 없이 끝남 → 재생 차례(onStart) 시각으로 대신한다
            val startAt = s.startAt
            if (ok && !s.signaled && startAt != null) { s.signaled = true; emit(UtteranceSignal.Started(path, s.tag, startAt, startAt - s.requestedAt)) }
            emit(if (ok) UtteranceSignal.Done(path, s.tag, clock()) else UtteranceSignal.Failed(path, s.tag, clock(), reason ?: "onError"))
        }
        releaseFocusIfIdle()
    }

    /** CRITICAL(QUEUE_FLUSH)·stop·취소로 끊긴 문장 — 실패로 세지 않는다(Dropped "interrupted"). 다시 넣은 문장의 옛 id 는 이미 빠져 있어 무시된다 */
    fun onStop(utteranceId: String?) {
        if (utteranceId == null) return
        val s: Sentence?
        synchronized(lock) {
            entries.remove(utteranceId)
            silences.remove(utteranceId)
            s = sentences.remove(utteranceId)
        }
        if (s != null && !s.cancelled) {
            if (s.waiter?.complete(false) == true) DhLog.i("TTS utterance 지워짐(onStop) id=$utteranceId → 기다리던 쪽 풀기")
            emit(UtteranceSignal.Dropped(path, s.tag, clock(), "interrupted"))
        }
        releaseFocusIfIdle()
    }

    /** 전부 멈춤 — 엔진 stop(대기·재생 문장은 onStop 으로 Dropped), 기다리던 쪽은 실패로 풀고 포커스 반납 */
    fun stopAll() {
        val waiting: List<Sentence>
        synchronized(lock) { waiting = sentences.values.toList() }
        engine.stop()
        waiting.forEach { it.waiter?.complete(false) }
        synchronized(lock) { entries.clear() }
        releaseFocusIfIdle()
    }

    /**
     * [tag] 문장만 버린다 — 바로 Dropped("cancelled"). 이미 재생 차례면 지금 엔진을 멈추고 뒤에 줄 선 문장을 다시 넣는다. 아직이면 표시만 해 두고
     * 재생 차례가 되는 순간([onStart]) 같은 일을 한다(Android TTS 는 큐에서 문장 하나만 지울 수 없다).
     */
    fun cancel(tag: String) {
        val now: List<Sentence>
        val marked: List<Sentence>
        synchronized(lock) {
            marked = sentences.values.filter { it.tag == tag && !it.cancelled }
            marked.forEach { it.cancelled = true }
            now = marked.filter { it.playing }
        }
        if (marked.isEmpty()) return
        marked.forEach { s ->
            s.waiter?.complete(false)
            emit(UtteranceSignal.Dropped(path, s.tag, clock(), "cancelled"))
        }
        DhLog.i("TTS 문장 취소 n=${marked.size} 재생중=${now.size}")
        now.firstOrNull()?.let { stopAndRefill(it) }
    }

    /** [s](취소됨)가 재생 차례다 — 엔진을 멈추고, 뒤에 줄 서 있던(취소되지 않은) 문장을 같은 순서·꼬리표·요청 시각으로 다시 넣는다 */
    private fun stopAndRefill(s: Sentence) {
        val followers: List<Sentence>
        synchronized(lock) {
            // 멈추면 엔진 큐의 모든 utterance 에 onStop 이 온다 — 옛 id 는 미리 빼 두어 그 onStop 을 무시한다
            followers = sentences.values.filter { it !== s && !it.cancelled && it.order > s.order }.sortedBy { it.order }
            val drop = sentences.values.filter { it === s || it.cancelled || it.order > s.order }
            drop.forEach { d ->
                sentences.remove(d.bodyId); entries.remove(d.bodyId)
                d.silenceId?.let { silences.remove(it); entries.remove(it) }
            }
        }
        engine.stop()
        DhLog.i("TTS 취소한 문장 차례 → 멈추고 뒤 문장 ${followers.size}개 다시 넣음")
        followers.forEach { submit(it, TextToSpeech.QUEUE_ADD, fresh = false) }
        releaseFocusIfIdle()
    }

    /** 콜백이 [staleMs] 넘게 오지 않은 utterance 정리(엔진이 재생 도중 죽음) — 포커스(덕킹)가 남지 않게 */
    fun purgeStale() {
        val now = clock()
        val dead: List<Sentence>
        synchronized(lock) {
            val oldIds = entries.filterValues { now - it > staleMs }.keys.toList()
            dead = sentences.values.filter { now - it.queuedAt > staleMs }
            if (oldIds.isEmpty() && dead.isEmpty()) return
            oldIds.forEach { entries.remove(it); silences.remove(it) }
            dead.forEach { d ->
                sentences.remove(d.bodyId); entries.remove(d.bodyId)
                d.silenceId?.let { silences.remove(it); entries.remove(it) }
            }
        }
        DhLog.w("TTS 콜백 없는 utterance 정리 n=${dead.size} (${staleMs / 1_000}s — 엔진 끊김?)")
        dead.filter { !it.cancelled }.forEach { s ->
            s.waiter?.complete(false)
            emit(UtteranceSignal.Dropped(path, s.tag, now, "stale"))
        }
        releaseFocusIfIdle()
    }

    private fun requestFocus() {
        synchronized(lock) { focusHeld = true }
        focus.request()
    }

    private fun releaseFocusIfIdle() {
        val release = synchronized(lock) {
            if (entries.isNotEmpty() || !focusHeld) false else { focusHeld = false; true }
        }
        if (release) focus.abandon()
    }

    companion object {
        /** 콜백을 기다리는 상한 — 넘으면 엔진이 죽은 것으로 보고 정리(가장 긴 문장 + 엔진 지연보다 넉넉히) */
        const val STALE_MS = 60_000L
        const val SILENCE_SUFFIX = "-s"
    }
}

/**
 * [AndroidTtsVoiceAdapter.speakAndWait] 가 기다리는 utterance 표(순수 — 단위 테스트). 엔진 콜백 스레드와 호출 스레드가 같이 쓴다.
 * onDone → 성공, onError·onStop(QUEUE_FLUSH·stop 으로 지워짐) → 실패. 모르는 id(묵음 utterance `…-s`, 기다리지 않는 문장)는 무시.
 */
internal class UtteranceWaiters {
    private val map = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    fun register(utteranceId: String, waiter: CompletableDeferred<Boolean>) {
        map[utteranceId] = waiter
    }

    fun forget(utteranceId: String) {
        map.remove(utteranceId)
    }

    /** 기다리던 쪽이 있었으면 풀고 true */
    fun finish(utteranceId: String?, ok: Boolean): Boolean {
        if (utteranceId == null) return false
        return map.remove(utteranceId)?.complete(ok) ?: false
    }

    fun failAll() {
        map.values.forEach { it.complete(false) }
        map.clear()
    }

    val size: Int get() = map.size
}
