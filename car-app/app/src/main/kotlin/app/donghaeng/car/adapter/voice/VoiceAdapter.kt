package app.donghaeng.car.adapter.voice

import app.donghaeng.car.domain.model.Priority
import kotlinx.coroutines.flow.StateFlow

/** 음성 어댑터 상태(진단용). 문장 원문은 담지 않는다 */
data class VoiceAdapterStatus(
    val engine: String? = null,
    /** null = 초기화 중 */
    val ready: Boolean? = null,
    val detail: String? = null,
    val spokenCount: Int = 0,
    val fallbackCount: Int = 0,
    val failedCount: Int = 0,
    val focusGranted: Boolean = false,
    val lastUtteranceMs: Long? = null,
)

/**
 * 음성 안내 어댑터 (docs/05 §3). Pleos TTS 는 "준비 중"이므로 Android [android.speech.tts.TextToSpeech] 를 쓴다(CLAUDE.md 규칙 5).
 *
 * 구현: [AndroidTtsVoiceAdapter](TTS 미준비 시 로그 폴백, 오디오 포커스, 선행 묵음). 우선순위 큐·신선도·내 이벤트 생략은 도메인 `VoiceAnnouncer`(T27).
 */
interface VoiceAdapter {
    val status: StateFlow<VoiceAdapterStatus>

    /** 문장을 읽는다. [Priority.CRITICAL] 은 재생 중인 안내를 끊는다. */
    suspend fun speak(text: String, priority: Priority)

    /** 재생 중인 안내를 즉시 멈춘다. */
    fun stop()
}
