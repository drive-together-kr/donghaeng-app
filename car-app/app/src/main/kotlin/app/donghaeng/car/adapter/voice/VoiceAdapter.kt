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

    /**
     * 문장을 읽고 **끝날 때까지** 기다린다(최대 [timeoutMs]) — F16 무전의 "보낼까요?" 뒤에만 마이크를 열고, 수신 무전은 "○○ 무전" 알림 뒤에 재생하기 위해.
     * 기본 구현은 [speak] 후 바로 [SpeakResult.UNKNOWN](완료를 모르는 구현·테스트 대역).
     */
    suspend fun speakAndWait(text: String, priority: Priority, timeoutMs: Long = DEFAULT_WAIT_MS): SpeakResult {
        speak(text, priority)
        return SpeakResult.UNKNOWN
    }

    /** 재생 중인 안내를 즉시 멈춘다. */
    fun stop()

    companion object {
        /** [speakAndWait] 기본 상한 — "보낼까요? 네 또는 아니라고 말하세요" ≈ 3초 + 엔진 지연 */
        const val DEFAULT_WAIT_MS = 8_000L
    }
}

/** [VoiceAdapter.speakAndWait] 결과 */
enum class SpeakResult {
    /** 엔진이 끝까지 읽었다(onDone) */
    SPOKEN,

    /** TTS 엔진이 없어 로그로 대신했다(Pleos AVD — docs/10 R14) — 화면 문구가 대신한다 */
    FALLBACK,

    /** 읽기 실패(speak 오류·onError) */
    FAILED,

    /** 상한 안에 끝나지 않았다(엔진은 계속 읽을 수 있다) */
    TIMEOUT,

    /** 완료를 알 수 없는 구현 */
    UNKNOWN,
}
