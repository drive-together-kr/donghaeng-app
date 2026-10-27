package app.donghaeng.car.adapter.voice

import app.donghaeng.car.domain.model.Priority

/**
 * 음성 안내 어댑터 (docs/05 §3). Pleos TTS 는 "준비 중"이므로 Android [android.speech.tts.TextToSpeech] 를 쓴다(CLAUDE.md 규칙 5).
 *
 * 구현: [AndroidTtsVoiceAdapter](TTS 미준비 시 로그 폴백). 우선순위 큐·신선도·오디오 포커스는 도메인 `VoiceAnnouncer`(T27).
 */
interface VoiceAdapter {
    /** 문장을 읽는다. [Priority.CRITICAL] 은 재생 중인 안내를 끊는다. */
    suspend fun speak(text: String, priority: Priority)

    /** 재생 중인 안내를 즉시 멈춘다. */
    fun stop()
}
