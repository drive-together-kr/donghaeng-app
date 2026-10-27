package app.donghaeng.car.adapter.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import app.donghaeng.car.BuildConfig
import app.donghaeng.car.core.common.DhLog
import app.donghaeng.car.domain.model.Priority
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/**
 * Android `TextToSpeech` 기반 음성 어댑터.
 *
 * - 엔진 초기화는 비동기다. 준비 전이거나 초기화에 실패하면 **로그로 대체**하고 예외를 던지지 않는다.
 * - 한국어 음성 데이터가 없는 에뮬레이터에서도 크래시 없이 동작한다(LANG_MISSING_DATA → 로그 폴백).
 * - 오디오 포커스(AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)·선행 묵음 200ms·우선순위 큐는 T27 `VoiceAnnouncer` 에서.
 *
 * 로그에는 release 빌드에서 문장 원문을 남기지 않는다(닉네임이 들어갈 수 있음, CLAUDE.md 규칙 8).
 */
class AndroidTtsVoiceAdapter(context: Context) : VoiceAdapter {

    private enum class State { INITIALIZING, READY, UNAVAILABLE }

    private val state = AtomicReference(State.INITIALIZING)
    private var utteranceSeq = 0L

    private val tts: TextToSpeech? = runCatching {
        TextToSpeech(context.applicationContext) { status -> onInit(status) }
    }.getOrElse { t ->
        DhLog.w("TTS 생성 실패 → 로그 폴백", t)
        state.set(State.UNAVAILABLE)
        null
    }

    private fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            DhLog.w("TTS 초기화 실패 status=$status → 로그 폴백")
            state.set(State.UNAVAILABLE)
            return
        }
        val langResult = tts?.setLanguage(Locale.KOREAN) ?: TextToSpeech.ERROR
        if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            DhLog.w("TTS 한국어 미지원(result=$langResult) → 로그 폴백")
            state.set(State.UNAVAILABLE)
            return
        }
        state.set(State.READY)
        DhLog.i("TTS 준비 완료 (Android TextToSpeech, ko-KR)")
    }

    override suspend fun speak(text: String, priority: Priority) {
        val engine = tts
        if (engine == null || state.get() != State.READY) {
            DhLog.i("TTS 폴백 [$priority] ${describe(text)}")
            return
        }
        val queueMode = if (priority == Priority.CRITICAL) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        val utteranceId = "dh-${utteranceSeq++}"
        val result = engine.speak(text, queueMode, null, utteranceId)
        if (result != TextToSpeech.SUCCESS) {
            DhLog.w("TTS speak 실패 result=$result [$priority] ${describe(text)}")
        } else {
            DhLog.d("TTS speak [$priority] ${describe(text)}")
        }
    }

    override fun stop() {
        runCatching { tts?.stop() }
    }

    /** 자원 해제 — Application 수명과 같이 가므로 보통 호출하지 않는다. */
    fun shutdown() {
        runCatching { tts?.shutdown() }
    }

    private fun describe(text: String): String =
        if (BuildConfig.DEBUG) "\"$text\"" else "(len=${text.length})"
}
