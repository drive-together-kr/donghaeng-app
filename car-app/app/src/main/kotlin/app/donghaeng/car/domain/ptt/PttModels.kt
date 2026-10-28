package app.donghaeng.car.domain.ptt

import app.donghaeng.car.adapter.audio.RecordedClip
import app.donghaeng.car.adapter.speech.RecognizerKind
import java.io.File
import java.util.Locale

/** 무전 보내기 단계 (F16 상태 기계, docs/05 § 무전) */
enum class PttStage {
    IDLE,

    /** 녹음 중(최대 15초, 다시 누르면 끝) */
    RECORDING,

    /** "보낼까요?" — 음성 확인(5초) 또는 카운트다운 모드(5초 뒤 자동 전송) */
    CONFIRMING,

    /** 서버로 올리는 중 */
    SENDING,

    /** "N대에 보냈어요" 3초 */
    SENT,

    /** 한 줄 안내 3초([PttNotice]) */
    NOTICE,
    ;

    /** 받은 무전 재생·NORMAL 이하 음성 안내를 미루는 단계 — 마이크를 쓰거나 확인을 기다리는 동안 */
    val busy: Boolean get() = this == RECORDING || this == CONFIRMING || this == SENDING
}

/** 3초 안내 — 화면 문구는 UI 담당이 고른다(음성은 [PttPrompts]) */
enum class PttNotice {
    /** 0.7초 미만 — 버림 "너무 짧아요" */
    TOO_SHORT,

    /** 아니·확인 중 버튼 — 버림 "취소했어요" */
    CANCELLED,

    /** 무응답 — '보내지 않은 무전'에 보관 "보내지 않았어요. 멈춘 뒤 목록에서 보낼 수 있어요" */
    KEPT_UNSENT,

    /** 업로드 실패 — 보관 "보내지 못했어요. 멈춘 뒤 목록에서 다시 보낼 수 있어요" */
    SEND_FAILED,

    /** 마이크·인코더를 열지 못했다 */
    RECORD_FAILED,

    /** RECORD_AUDIO 미승인 — "마이크 권한 필요"(정차 중에만 [권한 열기]) */
    MIC_PERMISSION,

    /** 그룹이 없다(그룹이 있을 때만 트리거가 켜지므로 보통 오지 않는다) */
    NO_GROUP,
}

/** 보내지 않은 무전이 된 이유 */
enum class UnsentReason { NO_ANSWER, SEND_FAILED }

/**
 * '보내지 않은 무전' 한 개 — 정차 대시보드에서 다시 보내기/지우기. 오디오·메타는 [UnsentVoiceStore](기기: 앱 내부 저장소 `files/ptt_unsent/`)에 두어
 * 앱 재시작·프로세스 종료 뒤에도 남는다(같은 그룹일 때만 다시 보인다). 다시 보냄 성공·지우기·그룹 종료(바뀜) 때 지운다.
 * [id] 는 `u<만든 시각 ms>-<순번>`(파일 이름으로도 쓴다 — 영숫자·`-` 만).
 */
data class UnsentVoice(
    val id: String,
    val groupId: String,
    val file: File,
    val mime: String,
    val durationMs: Long,
    /** 벽시계(epoch ms) — 목록의 "14:03" */
    val createdAtMs: Long,
    val reason: UnsentReason,
    /** 마지막 전송 실패 사유(로그용 요약 — `HTTP 429 VOICE_RATE_LIMITED`·`network …`) */
    val failure: String? = null,
) {
    fun toClip(): RecordedClip = RecordedClip(file, mime, durationMs, file.length(), injected = false)
}

/** 보내기 단계 상태 — 패널·대시보드는 surface `PttUiMapper` 로 `PttUi` 를 만든다 */
data class PttState(
    val stage: PttStage = PttStage.IDLE,
    /** RECORDING: 남은 초 / CONFIRMING: 남은 확인(또는 카운트다운) 초 */
    val remainingS: Int = 0,
    /** 원형 카운트다운 분모 — 녹음 15, 확인 5 */
    val totalS: Int = 0,
    /** 0..1 입력 크기(녹음 중 100 ms) */
    val level: Float = 0f,
    /** CONFIRMING 에서 마이크가 열려 듣는 중("보낼까요?" 를 다 읽은 뒤) */
    val listening: Boolean = false,
    /** 인식기가 없어 "5초 뒤 보낼게요 — 취소하려면 무전 버튼" */
    val countdownMode: Boolean = false,
    /** SENT: 받는 차 수(나 제외) */
    val sentTo: Int = 0,
    val notice: PttNotice? = null,
    val micGranted: Boolean = true,
    val lastAnswer: YesNo? = null,
    val lastRecognizer: RecognizerKind? = null,
    val stats: PttStats = PttStats(),
    /** 마지막으로 시작한 사이클 번호(녹음·다시 보내기·안내 — 1부터) */
    val cycleSeq: Int = 0,
    /** IDLE 로 끝난 마지막 사이클 번호 — `PttRuntime` 이 '재생을 끊은 누름 뒤의 흐름이 끝났다'를 가린다([ResumeAfterFlow]) */
    val idleCycleSeq: Int = 0,
)

/** 누적 수 — 디버그 `ptt_status` 한 줄 */
data class PttStats(
    val presses: Int = 0,
    val recordings: Int = 0,
    val sent: Int = 0,
    val cancelled: Int = 0,
    val kept: Int = 0,
    val tooShort: Int = 0,
    val sendFailed: Int = 0,
    val countdowns: Int = 0,
    val ignoredPresses: Int = 0,
) {
    override fun toString(): String =
        "presses=$presses rec=$recordings sent=$sent cancelled=$cancelled kept=$kept short=$tooShort sendFailed=$sendFailed countdown=$countdowns ignored=$ignoredPresses"
}

/** 보내기 결과 */
sealed interface SendOutcome {
    data class Sent(val voiceId: String) : SendOutcome

    /** [reason] = 로그용 요약(`HTTP 429 VOICE_RATE_LIMITED`, `network …`, `client-too-large`) */
    data class Failed(val reason: String) : SendOutcome
}

/** 무전 올리기 — 기기: `VoiceMessageClient.upload`, 테스트: 대본 */
fun interface VoiceSender {
    suspend fun send(groupId: String, clip: RecordedClip): SendOutcome
}

/** 상태 기계 시간 규칙(사용자 지시 — 15 s·0.7 s·5 s·3 s) */
data class PttTimings(
    val maxRecordMs: Long = 15_000,
    val minRecordMs: Long = 700,
    val confirmMs: Long = 5_000,
    val countdownMs: Long = 5_000,
    val sentLingerMs: Long = 3_000,
    val noticeMs: Long = 3_000,
    /** 녹음 남은 초·파형 갱신 */
    val tickMs: Long = 100,
    /** "보낼까요?" 를 다 읽기를 기다리는 상한 */
    val promptTimeoutMs: Long = 6_000,
    /** 인식기가 정리(stop 뒤 마지막 결과)하는 여유 — 확인 5초 + 이만큼 지나면 무응답 */
    val listenGraceMs: Long = 1_500,
    /**
     * 앱 시작 때 저장소에서 읽은 '보내지 않은 무전'을 그룹 세션 복원까지 기다리는 상한 — 이 안에 그룹이 오면 같은 그룹 것만 보이고,
     * 끝까지 그룹이 없으면(그룹 없음) 지운다. 세션 저장소(DataStore)는 시작 직후 몇 ms 안에 읽힌다
     */
    val restoreGraceMs: Long = 30_000,
)

/** 무전 음성 문구(TTS) — 화면 문구는 strings.xml(UI 담당). 닉네임이 들어간 문장은 로그에 남기지 않는다 */
object PttPrompts {
    const val ASK = "보낼까요?"
    const val COUNTDOWN = "5초 뒤 보낼게요. 취소하려면 무전 버튼을 누르세요."
    const val TOO_SHORT = "너무 짧아요."
    const val CANCELLED = "취소했어요."
    const val KEPT = "보내지 않았어요. 멈춘 뒤 목록에서 보낼 수 있어요."
    const val SENT = "보냈어요."
    const val SEND_FAILED = "보내지 못했어요. 멈춘 뒤 목록에서 다시 보낼 수 있어요."
    const val MIC_PERMISSION = "마이크 권한이 필요해요. 차를 세운 뒤 허용해 주세요."
    const val RECORD_FAILED = "녹음하지 못했어요."

    /** 받은 무전 알림 — 서버 speech("{닉네임} 무전")가 없을 때 */
    fun incoming(nickname: String?): String = nickname?.trim()?.takeIf { it.isNotEmpty() }?.let { "$it 무전" } ?: "무전이 왔어요"
}

/** 무전 길이 표기 */
object PttFormat {
    /** 7 400 ms → "0:07"(올림 없이 내림), 75 000 → "1:15" */
    fun clock(ms: Long): String {
        val s = (ms.coerceAtLeast(0) / 1000)
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
    }

    /** 남은 ms → 화면 초(올림 — 4.1초 남음 = "5") */
    fun ceilSeconds(ms: Long): Int = ((ms.coerceAtLeast(0) + 999) / 1000).toInt()
}
