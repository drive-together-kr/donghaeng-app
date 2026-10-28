package app.donghaeng.car.adapter.speech

import app.donghaeng.car.domain.ptt.YesNo
import java.io.File

/** 인식기 종류 — 체인 순서(docs/05 § 무전): Pleos STT → Android SpeechRecognizer → Vosk → (없으면) 카운트다운 */
enum class RecognizerKind { PLEOS_STT, ANDROID_STT, VOSK, INJECTED }

/** 한 단계의 듣기 결과 */
sealed interface ListenResult {
    /** 들었다 — 판정([YesNo.NONE] = 시간 안에 말이 없거나 네/아니가 아님). [confidence] 는 인식기가 주면 0..1 */
    data class Heard(val answer: YesNo, val confidence: Float? = null) : ListenResult

    /**
     * 이 단계로는 들을 수 없었다 → 체인이 다음 단계로 간다. [permanent] = 이번 실행 동안 다시 시도하지 않는다
     * (예: SpeechRecognizer `onError(9)` — Pleos AVD 실측, 권한을 줘도 같다).
     */
    data class Failed(val reason: String, val permanent: Boolean) : ListenResult
}

/**
 * '네/아니' 인식기 한 단계 (F16). 구현: [AndroidSpeechYesNoRecognizer], [VoskYesNoRecognizer], pleos flavor 의 `PleosSttYesNoRecognizer`,
 * 테스트용 [FakeYesNoRecognizer]. 인식 원문은 반환·로그하지 않는다 — 판정([YesNo])과 근거(사유·신뢰도)만.
 */
interface YesNoRecognizer {
    val kind: RecognizerKind

    /** 지금 쓸 수 없으면 사유(짧은 영어, 로그용), 쓸 수 있으면 null — 모델·서비스·권한 확인(빠르게, 메인 스레드에서도 부를 수 있게) */
    fun unavailableReason(): String?

    /** 무거운 준비(모델 적재 등) — 녹음 중에 미리 부른다. 실패해도 예외를 던지지 않는다 */
    suspend fun prepare() {}

    /** 마이크로 최대 [timeoutMs] 듣는다 */
    suspend fun listen(timeoutMs: Long): ListenResult

    /**
     * 마이크 대신 WAV(16 kHz 모노 16 bit PCM)를 흘려 들을 수 없으면 사유, 들을 수 있으면 null — 디버그 `ptt_answer --es file`.
     * 마이크 권한과 무관하다(Vosk 는 모델만 있으면 된다).
     */
    fun wavUnavailableReason(): String? = "wav-unsupported"

    suspend fun listenWav(file: File, timeoutMs: Long): ListenResult = ListenResult.Failed("wav-unsupported", permanent = false)
}

/**
 * flavor 별 플랫폼 인식기(체인 맨 앞) — pleos: `PleosSttYesNoRecognizer`(선택적), fake: 없음. `di/SpeechFlavorModule`(flavor 소스셋)이 묶는다.
 */
fun interface PlatformRecognizers {
    fun create(): List<YesNoRecognizer>
}

/** 체인 전체의 듣기 결과 — [answer] null = 들을 수 있는 단계가 하나도 없었다(→ 카운트다운으로) */
data class ChainOutcome(
    val answer: YesNo?,
    val kind: RecognizerKind? = null,
    val confidence: Float? = null,
    /** 건너뛰거나 실패한 단계와 사유 — 로그 `ptt: answer=YES recognizer=VOSK (ANDROID_STT error=9)` */
    val trail: List<String> = emptyList(),
)

/** PttController 가 쓰는 '네/아니 듣기' 창구 — 구현은 [RecognizerChain] */
interface YesNoListener {
    /** 들을 수 있는 단계(또는 주입된 답)가 하나라도 있는가 — 없으면 처음부터 카운트다운 모드 */
    fun hasCandidate(): Boolean

    /** 녹음 중에 미리 준비(Vosk 모델 적재) */
    suspend fun prepare()

    /** 최대 [timeoutMs] 동안 체인 순서대로 듣는다 */
    suspend fun listen(timeoutMs: Long): ChainOutcome

    /**
     * 디버그 주입 답이 올 때까지 기다린다(카운트다운 모드에서도 `ptt_answer` 로 네/아니/무응답을 정할 수 있게). 주입이 없는 구현은 영원히 기다린다.
     */
    suspend fun awaitInjected(): YesNo = kotlinx.coroutines.awaitCancellation()

    /** 진단 한 줄 — `PLEOS_STT=unavailable(no-caas) ANDROID_STT=failed(stt error=9) VOSK=ready` */
    fun describe(): String
}
