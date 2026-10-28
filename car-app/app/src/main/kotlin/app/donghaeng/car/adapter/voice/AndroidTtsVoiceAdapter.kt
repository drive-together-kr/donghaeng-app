package app.donghaeng.car.adapter.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import app.donghaeng.car.core.common.DhLog
import app.donghaeng.car.domain.model.Priority
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
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
 * - 진행 신호([signals]): **Started = 실제 소리 시작** — 본문 utterance 의 재생 차례(`onStart`)와 엔진 오디오 첫 조각(`onAudioAvailable`, API 24+)이
 *   **둘 다 온 시각(늦은 쪽)**. AOSP `SynthesisPlaybackQueueItem.run()` 은 `onStart` 를 먼저 보내고 엔진 오디오를 기다리므로(sherpa-onnx 는
 *   문장 합성을 끝낸 뒤에 오디오를 넘긴다) `onStart` 만 보면 합성이 3 s 넘게 걸려도 지연이 0.2~0.3 s 로 보인다(리뷰 2026-10-28).
 *   오디오 조각 신호 없이 끝나면(onDone) `onStart` 시각으로 대신한다. 지연 = 요청 → 그 시각(선행 묵음 200 ms 포함).
 *   `onDone` → Done, `onError` → Failed, 끊김 → Dropped.
 *
 * 문장 원문은 **어떤 빌드에서도** 로그에 남기지 않는다(닉네임 포함, CLAUDE.md 규칙 8) — 길이와 utteranceId 만 남긴다.
 */
class AndroidTtsVoiceAdapter(context: Context) : VoiceRoute {

    private enum class State { INITIALIZING, READY, UNAVAILABLE }

    /** 본문 utterance 한 개 — [startAt] = onStart(재생 차례), [audioAt] = 첫 onAudioAvailable(엔진 오디오 도착), [signaled] = Started 를 보냈다 */
    private data class Pending(
        val tag: String?,
        val requestedAt: Long,
        val startAt: Long? = null,
        val audioAt: Long? = null,
        val signaled: Boolean = false,
    )

    override val path: VoicePath = VoicePath.ANDROID_TTS

    private val appContext = context.applicationContext
    private val state = AtomicReference(State.INITIALIZING)
    private var utteranceSeq = 0L
    private val outstanding = AtomicInteger(0)
    private val focus = AudioFocusGuard(appContext)
    private val pending = ConcurrentHashMap<String, Pending>()

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
            override fun onStart(utteranceId: String?) = onUtteranceProgress(utteranceId) { p, now -> if (p.startAt == null) p.copy(startAt = now) else p }
            override fun onAudioAvailable(utteranceId: String?, audio: ByteArray?) =
                onUtteranceProgress(utteranceId) { p, now -> if (p.audioAt == null) p.copy(audioAt = now) else p }
            override fun onDone(utteranceId: String?) = onUtteranceFinished(utteranceId, ok = true, reason = null)
            @Deprecated("API 21 미만 호환용")
            override fun onError(utteranceId: String?) = onUtteranceFinished(utteranceId, ok = false, reason = "onError")
            override fun onError(utteranceId: String?, errorCode: Int) = onUtteranceFinished(utteranceId, ok = false, reason = "onError code=$errorCode")
            override fun onStop(utteranceId: String?, interrupted: Boolean) = onUtteranceStopped(utteranceId)
        })
        state.set(State.READY)
        _status.update { it.copy(ready = true, detail = "ko_KR 준비 (isLanguageAvailable=$available)", engine = engineName ?: it.engine) }
        DhLog.i("TTS 준비 완료 (Android TextToSpeech ${engineName ?: ""}, ko-KR)")
    }

    override suspend fun speak(text: String, priority: Priority) = speak(text, priority, null)

    override suspend fun speak(text: String, priority: Priority, tag: String?) {
        val utteranceId = "dh-${utteranceSeq++}"
        val engine = tts
        if (engine == null || state.get() != State.READY) {
            // 체인은 준비된 후보에만 넘긴다 — 여기 오면 경쟁 상태(방금 불가로 바뀜). 실패로 알려 체인이 센다
            DhLog.i("TTS 준비 안 됨 [$priority] ${describe(text, utteranceId)}")
            _status.update { it.copy(fallbackCount = it.fallbackCount + 1, lastUtteranceMs = System.currentTimeMillis()) }
            _signals.tryEmit(UtteranceSignal.Failed(path, tag, System.currentTimeMillis(), "준비 안 됨"))
            return
        }
        val granted = focus.request()
        _status.update { it.copy(focusGranted = granted) }
        val queueMode = if (priority == Priority.CRITICAL) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        if (priority == Priority.CRITICAL) outstanding.set(0)
        pending[utteranceId] = Pending(tag, System.currentTimeMillis())
        // 선행 묵음 200 ms (묵음도 한 utterance 로 센다 → 끝나면 포커스 정리 대상)
        outstanding.incrementAndGet()
        engine.playSilentUtterance(PRE_SILENCE_MS, queueMode, "$utteranceId$SILENCE_SUFFIX")
        outstanding.incrementAndGet()
        val result = engine.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
        if (result != TextToSpeech.SUCCESS) {
            outstanding.decrementAndGet()
            pending.remove(utteranceId)
            DhLog.w("TTS speak 실패 result=$result [$priority] ${describe(text, utteranceId)}")
            _status.update { it.copy(failedCount = it.failedCount + 1) }
            _signals.tryEmit(UtteranceSignal.Failed(path, tag, System.currentTimeMillis(), "speak result=$result"))
            releaseFocusIfIdle()
        } else {
            DhLog.d("TTS speak [$priority] ${describe(text, utteranceId)}")
            _status.update { it.copy(spokenCount = it.spokenCount + 1, lastUtteranceMs = System.currentTimeMillis()) }
        }
    }

    override fun stop() {
        runCatching { tts?.stop() }
        outstanding.set(0)
        releaseFocusIfIdle()
    }

    /** onStart·onAudioAvailable — 둘 다 모이면 그 문장의 Started 를 한 번 보낸다(바인더 스레드 여럿이어도 맵 갱신은 원자적) */
    private fun onUtteranceProgress(utteranceId: String?, update: (Pending, Long) -> Pending) {
        if (utteranceId == null || utteranceId.endsWith(SILENCE_SUFFIX)) return
        val now = System.currentTimeMillis()
        var fire: Pending? = null
        pending.computeIfPresent(utteranceId) { _, p ->
            val n = update(p, now)
            if (!n.signaled && soundStartAt(n.startAt, n.audioAt) != null) n.copy(signaled = true).also { fire = it } else n
        }
        fire?.let { p -> emitStarted(p, soundStartAt(p.startAt, p.audioAt) ?: now) }
    }

    private fun emitStarted(p: Pending, atMs: Long) {
        _signals.tryEmit(UtteranceSignal.Started(path, p.tag, atMs, atMs - p.requestedAt))
    }

    private fun onUtteranceFinished(utteranceId: String?, ok: Boolean, reason: String?) {
        if (!ok) DhLog.w("TTS utterance 오류 id=$utteranceId ${reason ?: ""}")
        if (utteranceId != null && !utteranceId.endsWith(SILENCE_SUFFIX)) {
            pending.remove(utteranceId)?.let { p ->
                val now = System.currentTimeMillis()
                // 오디오 조각 신호 없이 끝남 → 재생 차례(onStart) 시각으로 대신한다
                if (ok && !p.signaled && p.startAt != null) emitStarted(p, p.startAt)
                _signals.tryEmit(if (ok) UtteranceSignal.Done(path, p.tag, now) else UtteranceSignal.Failed(path, p.tag, now, reason ?: "onError"))
                if (!ok) _status.update { it.copy(failedCount = it.failedCount + 1) }
            }
        }
        if (outstanding.decrementAndGet() <= 0) {
            outstanding.set(0)
            releaseFocusIfIdle()
        }
    }

    /** CRITICAL(QUEUE_FLUSH)·stop 으로 끊긴 문장 — 실패로 세지 않는다. 포커스 수는 CRITICAL 이 이미 0 으로 맞췄다 */
    private fun onUtteranceStopped(utteranceId: String?) {
        if (utteranceId == null || utteranceId.endsWith(SILENCE_SUFFIX)) return
        pending.remove(utteranceId)?.let { p -> _signals.tryEmit(UtteranceSignal.Dropped(path, p.tag, System.currentTimeMillis(), "interrupted")) }
    }

    private fun releaseFocusIfIdle() {
        if (outstanding.get() > 0) return
        focus.abandon()
        _status.update { it.copy(focusGranted = false) }
    }

    /** 자원 해제 — Application 수명과 같이 가므로 보통 호출하지 않는다. */
    fun shutdown() {
        runCatching { tts?.shutdown() }
    }

    internal companion object {
        const val PRE_SILENCE_MS = 200L
        private const val SILENCE_SUFFIX = "-s"

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
