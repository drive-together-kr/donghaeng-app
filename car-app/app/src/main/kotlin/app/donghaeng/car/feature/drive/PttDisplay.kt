package app.donghaeng.car.feature.drive

import app.donghaeng.car.domain.ptt.PastStatus
import app.donghaeng.car.domain.ptt.PttFormat
import app.donghaeng.car.domain.ptt.PttNotice
import app.donghaeng.car.domain.ptt.ReceivedVoice
import app.donghaeng.car.domain.ptt.UnsentReason
import app.donghaeng.car.domain.ptt.UnsentVoice
import java.time.ZoneId

/**
 * 무전 줄에 무엇을 그릴지(순수, F16) — 플로팅 패널 [무전] 줄·정차 대시보드 하단 버튼·상단 띠가 같은 판단을 쓴다.
 * 문구는 화면이 strings.xml 에서 고른다(여기는 종류만).
 */
enum class PttDisplayKind {
    /** 평소 [무전] 버튼 */
    IDLE,

    /** 마이크 권한 없음 — "마이크 권한 필요"(정차 대시보드는 정차 중에만 [권한 열기]) */
    MIC_DENIED,

    /** 3초 안내([PttUi.notice]) */
    NOTICE,

    /** 녹음 중(빨간 파형 + 남은 초) */
    RECORDING,

    /** "보낼까요?" + "네 / 아니라고 말하세요" + 5초 원형 카운트다운 */
    CONFIRM_VOICE,

    /** 인식기가 없다 — "5초 뒤 보낼게요 — 취소하려면 무전 버튼" */
    CONFIRM_COUNTDOWN,

    /** 서버로 올리는 중 — "보내는 중…" */
    SENDING,

    /** "N대에 보냈어요" 3초 */
    SENT,

    /** 받은 무전 재생 중 — 보낸 차 색 + "아빠 차 무전 0:07" */
    PLAYING,

    /** 재생이 끝나 3초 머무는 중 */
    PLAYED,
}

object PttDisplay {
    fun kind(p: PttUi): PttDisplayKind = when (p.phase) {
        PttPhase.IDLE -> when {
            p.notice != null -> PttDisplayKind.NOTICE
            p.micDenied -> PttDisplayKind.MIC_DENIED
            else -> PttDisplayKind.IDLE
        }
        PttPhase.RECORDING -> PttDisplayKind.RECORDING
        PttPhase.CONFIRMING -> if (p.fallbackCountdown) PttDisplayKind.CONFIRM_COUNTDOWN else PttDisplayKind.CONFIRM_VOICE
        PttPhase.SENT -> if (p.sending) PttDisplayKind.SENDING else PttDisplayKind.SENT
        PttPhase.PLAYING -> if (p.playingEnded) PttDisplayKind.PLAYED else PttDisplayKind.PLAYING
    }

    /** 안내 색 — 버린 것(너무 짧음·취소)·그룹 없음은 중립, 목록에 남았거나 실패한 것은 주의 */
    fun noticeTone(n: PttNotice): Tone = when (n) {
        PttNotice.TOO_SHORT, PttNotice.CANCELLED, PttNotice.NO_GROUP -> Tone.NEUTRAL
        PttNotice.KEPT_UNSENT, PttNotice.SEND_FAILED, PttNotice.RECORD_FAILED, PttNotice.MIC_PERMISSION -> Tone.CAUTION
    }

    /** 대시보드 '지난 무전'에 보이는 개수 — 들고 있는 최근 20개 전부(정차 블록은 스크롤된다, 사용자 결정 2026-10-28) */
    const val PAST_COUNT = app.donghaeng.car.domain.ptt.VoicePlayback.MAX_PAST

    /** '보내지 않은 무전' → 화면 줄(최신 먼저 그대로). 시각은 벽시계 "14:03" */
    fun unsentRows(list: List<UnsentVoice>, zone: ZoneId): List<UnsentPttUi> = list.map {
        UnsentPttUi(
            id = it.id,
            text = "무전 ${PttFormat.clock(it.durationMs)} · ${DriveFormat.clock(it.createdAtMs, zone)}",
            reasonText = when (it.reason) {
                UnsentReason.NO_ANSWER -> "대답 없음"
                UnsentReason.SEND_FAILED -> "보내기 실패"
            },
            failed = it.reason == UnsentReason.SEND_FAILED,
        )
    }

    /**
     * '지난 무전' → 화면 줄(최신 먼저, 최대 [PAST_COUNT]). 재생 대기(QUEUED)는 빼고, 다 들은 것(PLAYED)도 다시 듣기용으로 남긴다
     * (사용자 결정 2026-10-28 — 들은 것도 유지, 최근 20개, 그룹 종료 때 삭제).
     * 꼬리표: 60초 넘어 자동 재생 안 함 = "못 들음", 무전 버튼으로 끊음 = "다 못 들음"(내 흐름이 끝나면 처음부터 다시 틀고, 다 들으면 꼬리표 없음).
     * "N분 전" = 서버 시계 − 봉투 ts.
     */
    fun pastRows(list: List<ReceivedVoice>, serverNowMs: Long): List<PastPttUi> = list
        .filter { it.status != PastStatus.QUEUED }
        .take(PAST_COUNT)
        .map { v ->
            val name = v.nickname?.trim()?.takeIf { it.isNotEmpty() }
            PastPttUi(
                voiceId = v.voiceId,
                text = (if (name != null) "$name 무전 " else "무전 ") + PttFormat.clock(v.durationMs),
                colorArgb = v.colorHex?.let { app.donghaeng.car.feature.groupdrive.GroupDriveViewModel.parseColor(it) },
                whenText = DriveFormat.ago(serverNowMs - v.envelopeTsMs),
                statusText = when (v.status) {
                    PastStatus.STALE -> "못 들음"
                    PastStatus.INTERRUPTED -> "다 못 들음"
                    PastStatus.DOWNLOAD_FAILED -> "받기 실패"
                    PastStatus.PLAY_FAILED -> "재생 실패"
                    PastStatus.PLAYED, PastStatus.QUEUED -> null
                },
                tone = when (v.status) {
                    PastStatus.STALE, PastStatus.INTERRUPTED -> Tone.INFO
                    PastStatus.DOWNLOAD_FAILED, PastStatus.PLAY_FAILED -> Tone.CAUTION
                    PastStatus.PLAYED, PastStatus.QUEUED -> Tone.NEUTRAL
                },
            )
        }
}

/**
 * 정차 대시보드의 무전 입력(F16) — `feature.groupdrive.PttDashboardViewModel` 이 모아 [DriveDashboardMapper.map] 에 준다.
 * [ptt] = `SurfaceState.ptt`(패널과 같은 값), [unsent] = PttController.unsent, [past] = VoicePlayback.state.past, [handleButton] = 설정 '핸들 버튼으로 무전'.
 */
data class PttDashboardInput(
    val ptt: PttUi = PttUi(),
    val unsent: List<UnsentVoice> = emptyList(),
    val past: List<ReceivedVoice> = emptyList(),
    val handleButton: Boolean = true,
)
