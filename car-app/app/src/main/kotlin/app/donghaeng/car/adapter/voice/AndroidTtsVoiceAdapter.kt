package app.donghaeng.car.adapter.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import app.donghaeng.car.core.common.DhLog
import app.donghaeng.car.domain.model.Priority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Android `TextToSpeech` 기반 음성 어댑터 (T04 골격, T27 완성).
 *
 * - 엔진 초기화는 비동기다. 준비 전이거나 초기화에 실패하면 **로그로 대체**하고 예외를 던지지 않는다.
 * - 한국어 음성 데이터가 없는 에뮬레이터에서도 크래시 없이 동작한다(LANG_MISSING_DATA → 로그 폴백). Pleos AVD 에는 TTS 엔진 자체가 없다(실측).
 * - 오디오: `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`, 말하기 전 **오디오 포커스 `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`** 요청, 마지막 문장이 끝나면 반납.
 *   문장 앞에 **선행 묵음 200 ms**(Pleos 가이드) — 블루투스/앰프가 깨어나며 첫 음절이 잘리는 것을 막는다.
 * - [Priority.CRITICAL] 은 `QUEUE_FLUSH` 로 재생 중인 안내를 끊는다. 큐 순서·신선도는 `VoiceAnnouncer` 가 결정한다.
 *
 * 문장 원문은 **어떤 빌드에서도** 로그에 남기지 않는다(닉네임 포함, CLAUDE.md 규칙 8) — 길이와 utteranceId 만 남긴다.
 */
class AndroidTtsVoiceAdapter(context: Context) : VoiceAdapter {

    private enum class State { INITIALIZING, READY, UNAVAILABLE }

    private val appContext = context.applicationContext
    private val state = AtomicReference(State.INITIALIZING)
    private var utteranceSeq = 0L
    private val outstanding = AtomicInteger(0)
    private val audioManager: AudioManager? = appContext.getSystemService(AudioManager::class.java)
    private var focusRequest: AudioFocusRequest? = null

    private val _status = MutableStateFlow(VoiceAdapterStatus(engine = "Android TextToSpeech"))
    override val status: StateFlow<VoiceAdapterStatus> = _status

    private val audioAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val tts: TextToSpeech? = runCatching {
        TextToSpeech(appContext) { status -> onInit(status) }
    }.getOrElse { t ->
        DhLog.w("TTS 생성 실패 → 로그 폴백", t)
        state.set(State.UNAVAILABLE)
        _status.update { it.copy(ready = false, detail = "생성 실패 ${t.javaClass.simpleName}") }
        null
    }

    private fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            DhLog.w("TTS 초기화 실패 status=$status → 로그 폴백 (엔진 없음?)")
            state.set(State.UNAVAILABLE)
            _status.update { it.copy(ready = false, detail = "초기화 실패 status=$status (TTS 엔진 없음) → 로그 폴백") }
            return
        }
        val engine = tts
        val langResult = engine?.setLanguage(Locale.KOREAN) ?: TextToSpeech.ERROR
        if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            DhLog.w("TTS 한국어 미지원(result=$langResult) → 로그 폴백")
            state.set(State.UNAVAILABLE)
            _status.update { it.copy(ready = false, detail = "한국어 미지원 result=$langResult → 로그 폴백", engine = engine?.defaultEngine ?: it.engine) }
            return
        }
        runCatching { engine?.setAudioAttributes(audioAttributes) }
        engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = onUtteranceFinished(utteranceId, ok = true)
            @Deprecated("API 21 미만 호환용")
            override fun onError(utteranceId: String?) = onUtteranceFinished(utteranceId, ok = false)
            override fun onError(utteranceId: String?, errorCode: Int) = onUtteranceFinished(utteranceId, ok = false)
        })
        state.set(State.READY)
        _status.update { it.copy(ready = true, detail = "ko-KR 준비", engine = engine?.defaultEngine ?: it.engine) }
        DhLog.i("TTS 준비 완료 (Android TextToSpeech ${engine?.defaultEngine ?: ""}, ko-KR)")
    }

    override suspend fun speak(text: String, priority: Priority) {
        val utteranceId = "dh-${utteranceSeq++}"
        val engine = tts
        if (engine == null || state.get() != State.READY) {
            DhLog.i("TTS 폴백 [$priority] ${describe(text, utteranceId)}")
            _status.update { it.copy(fallbackCount = it.fallbackCount + 1, lastUtteranceMs = System.currentTimeMillis()) }
            return
        }
        requestFocus()
        val queueMode = if (priority == Priority.CRITICAL) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        if (priority == Priority.CRITICAL) outstanding.set(0)
        // 선행 묵음 200 ms (묵음도 한 utterance 로 센다 → 끝나면 포커스 정리 대상)
        outstanding.incrementAndGet()
        engine.playSilentUtterance(PRE_SILENCE_MS, queueMode, "$utteranceId-s")
        outstanding.incrementAndGet()
        val result = engine.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
        if (result != TextToSpeech.SUCCESS) {
            outstanding.decrementAndGet()
            DhLog.w("TTS speak 실패 result=$result [$priority] ${describe(text, utteranceId)}")
            _status.update { it.copy(failedCount = it.failedCount + 1) }
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

    private fun onUtteranceFinished(utteranceId: String?, ok: Boolean) {
        if (!ok) DhLog.w("TTS utterance 오류 id=$utteranceId")
        if (outstanding.decrementAndGet() <= 0) {
            outstanding.set(0)
            releaseFocusIfIdle()
        }
    }

    private fun requestFocus() {
        val am = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(audioAttributes)
                    .setWillPauseWhenDucked(false)
                    .build()
                    .also { focusRequest = it }
                val r = am.requestAudioFocus(req)
                _status.update { it.copy(focusGranted = r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) }
            } else {
                @Suppress("DEPRECATION")
                val r = am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                _status.update { it.copy(focusGranted = r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) }
            }
        } catch (e: Exception) {
            DhLog.w("오디오 포커스 요청 실패 ${e.javaClass.simpleName}")
        }
    }

    private fun releaseFocusIfIdle() {
        if (outstanding.get() > 0) return
        val am = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) focusRequest?.let { am.abandonAudioFocusRequest(it) }
            else @Suppress("DEPRECATION") am.abandonAudioFocus(null)
            _status.update { it.copy(focusGranted = false) }
        } catch (e: Exception) {
            DhLog.w("오디오 포커스 반납 실패 ${e.javaClass.simpleName}")
        }
    }

    /** 자원 해제 — Application 수명과 같이 가므로 보통 호출하지 않는다. */
    fun shutdown() {
        runCatching { tts?.shutdown() }
    }

    internal companion object {
        const val PRE_SILENCE_MS = 200L

        /** 로그용 요약 — 문장 원문 대신 길이와 utteranceId 만 (규칙 8). 빌드 타입과 무관하게 항상 동일. */
        internal fun describe(text: String, utteranceId: String): String = "(len=${text.length} id=$utteranceId)"
    }
}
