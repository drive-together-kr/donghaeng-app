package app.donghaeng.car.surface

import app.donghaeng.car.domain.ptt.PttFormat
import app.donghaeng.car.domain.ptt.PttStage
import app.donghaeng.car.domain.ptt.PttState
import app.donghaeng.car.domain.ptt.VoicePlaybackState
import app.donghaeng.car.feature.drive.PttPhase
import app.donghaeng.car.feature.drive.PttUi
import app.donghaeng.car.feature.groupdrive.GroupDriveViewModel

/**
 * F16 보내기 상태([PttState]) + 받은 무전 재생([VoicePlaybackState]) → 화면 모델 [PttUi] (순수) — `SurfaceState.ptt` 로 패널·대시보드가 같은 값을 본다.
 *
 * 우선순위: 녹음·확인·전송(바쁨) > 받은 무전 재생(끝나고 3초 머묾 포함) > 보냄 3초·안내 3초 > 평소.
 * - RECORDING → [PttPhase.RECORDING](seconds = 남은 초, total 15, level)
 * - CONFIRMING → [PttPhase.CONFIRMING](seconds = 남은 초, total 5, listening, fallbackCountdown = 카운트다운 모드)
 * - SENDING → [PttPhase.SENT] + sending = true(sentTo 0) / SENT → [PttPhase.SENT](sentTo)
 * - NOTICE → [PttPhase.IDLE] + notice
 * - 재생 → [PttPhase.PLAYING](seconds = 경과 초, fromName = 닉네임, fromColorArgb = 멤버 색, durationText "0:07", playingEnded)
 */
object PttUiMapper {
    fun map(c: PttState, p: VoicePlaybackState, unsentCount: Int = 0): PttUi {
        val common = PttUi(micDenied = !c.micGranted, queuedIncoming = p.queued, unsentCount = unsentCount)
        if (c.stage.busy) return mapSend(c, common)
        val playing = p.playing
        if (playing != null) {
            val v = playing.voice
            return common.copy(
                phase = PttPhase.PLAYING,
                seconds = (playing.positionMs / 1000).toInt(),
                totalSeconds = PttFormat.ceilSeconds(v.durationMs).coerceAtLeast(1),
                fromName = v.nickname,
                fromColorArgb = v.colorHex?.let { GroupDriveViewModel.parseColor(it) },
                durationText = PttFormat.clock(v.durationMs),
                playingEnded = playing.ended,
            )
        }
        return mapSend(c, common)
    }

    private fun mapSend(c: PttState, common: PttUi): PttUi = when (c.stage) {
        PttStage.IDLE -> common
        PttStage.RECORDING -> common.copy(phase = PttPhase.RECORDING, seconds = c.remainingS, totalSeconds = c.totalS.coerceAtLeast(1), level = c.level)
        PttStage.CONFIRMING -> common.copy(
            phase = PttPhase.CONFIRMING, seconds = c.remainingS, totalSeconds = c.totalS.coerceAtLeast(1),
            listening = c.listening, fallbackCountdown = c.countdownMode,
        )
        PttStage.SENDING -> common.copy(phase = PttPhase.SENT, sending = true, sentTo = 0)
        PttStage.SENT -> common.copy(phase = PttPhase.SENT, sentTo = c.sentTo)
        PttStage.NOTICE -> common.copy(phase = PttPhase.IDLE, notice = c.notice)
    }
}
