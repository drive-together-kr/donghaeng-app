package app.donghaeng.car.adapter.audio

import java.io.File

/** [VoiceClipPlayer.play] 결과 */
sealed interface PlayOutcome {
    data object Completed : PlayOutcome

    /** [VoiceClipPlayer.stop] 으로 멈췄다(무전 버튼을 눌러 끊음·그룹 종료) */
    data object Stopped : PlayOutcome

    data class Failed(val reason: String) : PlayOutcome
}

/**
 * 받은 무전 재생 어댑터 (F16).
 *
 * - 기기: [AndroidVoiceClipPlayer] — `MediaPlayer`, 용도 `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`(음성 안내와 같은 길 — 순정 내비·음악을 줄여 듣는다),
 *   오디오 포커스 `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`(음악은 멈추지 않고 작아진다), 끝나면 반환.
 * - 테스트: [FakeVoiceClipPlayer](가상 시간으로 길이만큼 기다린다).
 */
interface VoiceClipPlayer {
    /**
     * [file] 을 끝까지(또는 [stop] 까지) 재생하고 결과를 돌려준다. [onProgress] 는 재생 위치(ms)로 약 4번/초 불린다.
     * 취소되면 재생도 멈춘다.
     */
    suspend fun play(file: File, onProgress: (positionMs: Long) -> Unit): PlayOutcome

    /** 재생 중이면 멈춘다(재생 중인 [play] 는 [PlayOutcome.Stopped] 로 끝난다) */
    fun stop()
}
