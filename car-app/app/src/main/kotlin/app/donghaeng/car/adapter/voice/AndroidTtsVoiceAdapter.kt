package app.donghaeng.car.adapter.voice

import android.content.Context
import android.speech.tts.TextToSpeech
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
 * 문장 원문은 **어떤 빌드에서도** 로그에 남기지 않는다(닉네임 포함, CLAUDE.md 규칙 8) — 길이와 utteranceId 만 남긴다.
 * 무엇을 말했는지 진단이 필요하면 문장을 조립하는 쪽(T27 `VoiceAnnouncer`)이 템플릿 + `TextMasking.maskNickname` 으로 남긴다.
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
        val utteranceId = "dh-${utteranceSeq++}"
        val engine = tts
        if (engine == null || state.get() != State.READY) {
            DhLog.i("TTS 폴백 [$priority] ${describe(text, utteranceId)}")
            return
        }
        val queueMode = if (priority == Priority.CRITICAL) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        val result = engine.speak(text, queueMode, null, utteranceId)
        if (result != TextToSpeech.SUCCESS) {
            DhLog.w("TTS speak 실패 result=$result [$priority] ${describe(text, utteranceId)}")
        } else {
            DhLog.d("TTS speak [$priority] ${describe(text, utteranceId)}")
        }
    }

    override fun stop() {
        runCatching { tts?.stop() }
    }

    /** 자원 해제 — Application 수명과 같이 가므로 보통 호출하지 않는다. */
    fun shutdown() {
        runCatching { tts?.shutdown() }
    }

    internal companion object {
        /** 로그용 요약 — 문장 원문 대신 길이와 utteranceId 만 (규칙 8). 빌드 타입과 무관하게 항상 동일. */
        internal fun describe(text: String, utteranceId: String): String = "(len=${text.length} id=$utteranceId)"
    }
}
