package app.donghaeng.car.adapter.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import app.donghaeng.car.core.common.DhLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File

/**
 * `MediaPlayer` 무전 재생 (F16) — 메인 스레드에서 만들고 콜백을 받는다.
 *
 * 용도 `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` + `CONTENT_TYPE_SPEECH`: AAOS 는 용도별로 음향 경로·포커스 상호작용을 정한다.
 * 음성 안내(TTS, `AndroidTtsVoiceAdapter`)와 같은 용도라 같은 규칙(음악은 덕킹, 순정 내비 안내와는 차례)으로 섞이고,
 * 미디어 용도가 아니어서 핸들 미디어 키 라우팅(MediaSession, `MediaSessionVoiceTrigger`)을 빼앗지 않는다.
 * 포커스는 `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` — 못 받아도 재생한다(로그만).
 * [stop]·취소는 `prepare` 중에 와도 받는다 — 완료 신호를 prepare 전에 걸어 두고, prepare 뒤 시작하기 전에 한 번 더 본다(받은 무전 전체가 그대로 재생되지 않게).
 */
class AndroidVoiceClipPlayer(context: Context) : VoiceClipPlayer {

    private val audioManager: AudioManager? = context.applicationContext.getSystemService(AudioManager::class.java)
    private val attributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusRequest: AudioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .setWillPauseWhenDucked(false)
        .build()

    /** 현재 재생기(메인 스레드에서만)와 그 완료 신호([stop] 이 다른 스레드에서 읽는다) */
    private var current: MediaPlayer? = null
    @Volatile private var currentDone: CompletableDeferred<PlayOutcome>? = null

    override suspend fun play(file: File, onProgress: (positionMs: Long) -> Unit): PlayOutcome = withContext(Dispatchers.Main) {
        stopCurrent(PlayOutcome.Stopped)
        val done = CompletableDeferred<PlayOutcome>()
        // prepare(메인 스레드에서 동기) 중에 다른 스레드가 stop() 을 부르면 이 신호가 풀린다 — prepare 뒤에 확인한다
        currentDone = done
        val player = MediaPlayer()
        try {
            player.setAudioAttributes(attributes)
            player.setDataSource(file.absolutePath)
            player.setOnCompletionListener { done.complete(PlayOutcome.Completed) }
            player.setOnErrorListener { _, what, extra -> done.complete(PlayOutcome.Failed("media-error $what/$extra")); true }
            player.prepare()   // 로컬 캐시 파일(≤ 512 KB) — 짧다
        } catch (e: Exception) {
            runCatching { player.release() }
            if (currentDone === done) currentDone = null
            DhLog.w("ptt: playback prepare 실패 ${e.javaClass.simpleName}")
            return@withContext PlayOutcome.Failed("prepare-${e.javaClass.simpleName}")
        }
        if (done.isCompleted || !isActive) {
            // 준비하는 사이 멈춤 요청(무전 버튼·그룹 종료) 또는 취소 — 시작하지 않는다
            runCatching { player.release() }
            if (currentDone === done) currentDone = null
            DhLog.i("ptt: playback stopped before start")
            return@withContext PlayOutcome.Stopped
        }
        val focus = runCatching { audioManager?.requestAudioFocus(focusRequest) }.getOrNull()
        if (focus != null && focus != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) DhLog.w("ptt: 재생 오디오 포커스 거부 result=$focus (재생은 계속)")
        current = player
        try {
            player.start()
            coroutineScope {
                val ticker = launch {
                    while (isActive) {
                        runCatching { onProgress(player.currentPosition.toLong()) }
                        delay(PROGRESS_MS)
                    }
                }
                val outcome = done.await()
                ticker.cancel()
                outcome
            }
        } finally {
            // 끝·실패·취소 어느 경우든 메인 스레드에서 정리(취소돼도 이 블록은 NonCancellable 없이 동기 코드만 쓴다)
            if (current === player) current = null
            if (currentDone === done) currentDone = null
            runCatching { player.stop() }
            runCatching { player.release() }
            runCatching { audioManager?.abandonAudioFocusRequest(focusRequest) }
        }
    }

    override fun stop() {
        // 어느 스레드에서든 부를 수 있다 — 완료 신호만 먼저 풀고, 실제 정리는 play() 의 finally(메인)가 한다
        currentDone?.complete(PlayOutcome.Stopped)
    }

    private fun stopCurrent(outcome: PlayOutcome) {
        currentDone?.complete(outcome)
    }

    companion object {
        const val PROGRESS_MS = 250L
    }
}
