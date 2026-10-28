package app.donghaeng.car.adapter.voice

import app.donghaeng.car.domain.model.Priority
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * 음성 출력 경로 — 체인 순서(docs/05 §3.5, CLAUDE.md 규칙 5 "가능하면 쓰고, 실패하면 다음 수단으로 자동 전환").
 *
 * - [ANDROID_TTS]: Android `TextToSpeech`(한국어 엔진이 있고 ko_KR 이 쓸 수 있을 때만)
 * - [PLEOS_TTS]: Pleos TextToSpeech SDK ON_DEVICE(ko_KR, pleos flavor 만 — 준비 중 SDK)
 * - [DISPLAY_ONLY]: 소리 경로 없음 — 화면(패널 소식 줄·대시보드 띠)만, 로그에 길이만 남긴다
 */
enum class VoicePath { ANDROID_TTS, PLEOS_TTS, DISPLAY_ONLY }

/** 요청 → 실제 소리 시작 지연 통계(최근 [VoiceLatencyWindow.CAPACITY]건). 표본이 없으면 전부 null */
data class VoiceLatencyStats(
    val lastMs: Long? = null,
    val avgMs: Long? = null,
    val maxMs: Long? = null,
    val count: Int = 0,
)

/** 체인 후보 한 개의 상태(진단 화면 후보 줄) */
data class VoiceCandidateStatus(
    val path: VoicePath,
    /** null = 초기화 중 */
    val ready: Boolean?,
    val engine: String?,
    val detail: String?,
    /** 연속 실패로 강등됨(프로세스가 끝날 때까지 쓰지 않는다) */
    val demoted: Boolean = false,
    val consecutiveFailures: Int = 0,
    val latency: VoiceLatencyStats = VoiceLatencyStats(),
)

/** 음성 어댑터 상태(진단용). 문장 원문은 담지 않는다 */
data class VoiceAdapterStatus(
    val engine: String? = null,
    /** null = 초기화 중(체인: 소리 경로가 아직 없고 초기화 중인 후보가 있음), true = 소리 경로 있음, false = 없음(화면 표시만으로 정해짐) */
    val ready: Boolean? = null,
    val detail: String? = null,
    val spokenCount: Int = 0,
    val fallbackCount: Int = 0,
    val failedCount: Int = 0,
    val focusGranted: Boolean = false,
    val lastUtteranceMs: Long? = null,
    /** 지금 경로(체인 [ChainedVoiceAdapter]). null = 경로 개념이 없는 단일 어댑터(후보 자신·테스트 대역) */
    val path: VoicePath? = null,
    /** 경로를 고른 사유 — "ANDROID_TTS 불가(초기화 실패 status=-1)" 처럼 위 순위가 왜 빠졌는지 */
    val pathReason: String? = null,
    /** 더 높은 순위 후보가 아직 초기화 중(선택 상한 안) — 지금 경로는 잠정 */
    val selecting: Boolean = false,
    /** 지금 경로의 요청 → 시작 지연 */
    val latency: VoiceLatencyStats = VoiceLatencyStats(),
    /** 체인 후보별 상태(체인만 채운다) */
    val candidates: List<VoiceCandidateStatus> = emptyList(),
)

/**
 * 한 문장의 진행 신호 — 지연 측정(요청 → 시작)·연속 실패 강등·위험 이벤트 강조 해제(eventId = [tag])에 쓴다.
 * 시각은 기기 벽시계(ms, `System.currentTimeMillis` — 그룹 이벤트 `receivedAtMs` 와 같은 시계).
 */
sealed interface UtteranceSignal {
    val path: VoicePath
    /** 호출자가 붙인 꼬리표(그룹 이벤트면 eventId). 없으면 null */
    val tag: String?
    val atMs: Long

    /** 실제 소리 시작. [latencyMs] = 요청(speak 호출) → 시작 */
    data class Started(override val path: VoicePath, override val tag: String?, override val atMs: Long, val latencyMs: Long) : UtteranceSignal

    /** 끝까지 읽음 */
    data class Done(override val path: VoicePath, override val tag: String?, override val atMs: Long) : UtteranceSignal

    /** 실패(엔진 오류·시작 시간 초과·speak 거부·준비 안 됨) — 지금 경로가 연속 [ChainedVoiceAdapter.DEMOTE_AFTER_FAILURES]번이면 다음 경로로 내린다 */
    data class Failed(override val path: VoicePath, override val tag: String?, override val atMs: Long, val reason: String) : UtteranceSignal

    /** 실패로 세지 않는 끝남 — 더 급한 안내가 끊음(interrupted·flushed), 큐에서 너무 오래 기다림 */
    data class Dropped(override val path: VoicePath, override val tag: String?, override val atMs: Long, val reason: String) : UtteranceSignal

    /** 소리 경로가 없어 화면 표시만 했다 */
    data class NoAudio(override val tag: String?, override val atMs: Long) : UtteranceSignal {
        override val path: VoicePath get() = VoicePath.DISPLAY_ONLY
    }
}

/**
 * 음성 안내 어댑터 (docs/05 §3). 앱이 쓰는 구현은 [ChainedVoiceAdapter] — Android TTS → Pleos TTS(pleos flavor) → 화면 표시만.
 * 후보([VoiceRoute])는 [AndroidTtsVoiceAdapter]·`PleosTtsVoiceAdapter`(pleos 소스셋). 우선순위 큐·신선도·내 이벤트 생략은 도메인 `VoiceAnnouncer`(T27).
 */
interface VoiceAdapter {
    val status: StateFlow<VoiceAdapterStatus>

    /** 문장 진행 신호(시작·끝·실패). 기본 = 신호 없음(테스트 대역) */
    val signals: Flow<UtteranceSignal> get() = emptyFlow()

    /** 문장을 읽는다. [Priority.CRITICAL] 은 재생 중인 안내를 끊는다. */
    suspend fun speak(text: String, priority: Priority)

    /** [tag](그룹 이벤트 eventId)를 붙여 읽는다 — [signals] 로 그 문장이 언제 시작됐는지 알 수 있다. 기본 = 꼬리표 없이 [speak] */
    suspend fun speak(text: String, priority: Priority, tag: String?) = speak(text, priority)

    /**
     * 문장을 읽고 **끝날 때까지** 기다린다(최대 [timeoutMs]) — F16 무전의 "보낼까요?" 뒤에만 마이크를 열고, 수신 무전은 "○○ 무전" 알림 뒤에 재생하기 위해.
     * 기본 구현은 [speak] 후 바로 [SpeakResult.UNKNOWN](완료를 모르는 구현·테스트 대역). 앱이 쓰는 [ChainedVoiceAdapter] 는 [signals] 로 끝을 기다린다.
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

/** 체인의 후보 한 개 — 자기 경로를 안다. [status] 의 `ready`(null 초기화 중 / true / false)·`engine`·`detail` 로 체인이 고른다 */
interface VoiceRoute : VoiceAdapter {
    val path: VoicePath
}

/**
 * 디버그 리시버 `status` 한 줄의 음성 부분(demo.py 가 읽는다, 공백 없는 토큰):
 * `tts=ready|init|off ttsEngine=<엔진, 공백→_> ttsPath=ANDROID_TTS|PLEOS_TTS|DISPLAY_ONLY ttsSelecting=true|false`.
 * ready = 어느 경로든 소리 경로가 있음, init = 소리 경로가 아직 없고 초기화 중인 후보가 있음(늦게·다시 시도로 준비될 수 있음),
 * off = 화면 표시만으로 정해짐(모든 후보 불가·강등).
 */
fun VoiceAdapterStatus.debugTokens(): String {
    val tts = when (ready) { true -> "ready"; null -> "init"; false -> "off" }
    val eng = (if (path == VoicePath.DISPLAY_ONLY) null else engine)?.replace(Regex("\\s+"), "_")?.ifBlank { null } ?: "-"
    return "tts=$tts ttsEngine=$eng ttsPath=${path?.name ?: "-"} ttsSelecting=$selecting"
}
