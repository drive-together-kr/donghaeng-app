package app.donghaeng.car.adapter.trigger

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.AudioTrack
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import app.donghaeng.car.core.common.DhLog
import app.donghaeng.car.domain.ptt.PttSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * MediaSession 무전 트리거 (F16 ④ — 그룹 주행 중 백그라운드). 2026-10-28 Pleos AVD 실측(F16Probes `media_on`)과 같은 방법:
 * 활성 `MediaSession`(STATE_PLAYING, ACTION_PLAY_PAUSE) + **짧은 무음 재생(USAGE_MEDIA 400 ms)** → 미디어 버튼 라우팅이 '최근 재생한 앱'의 세션을 고르므로
 * 우리 세션이 Media button session 이 되고, 순정 내비가 앞에 있어도 HEADSETHOOK·MEDIA_PLAY_PAUSE·MEDIA_PLAY 가 [onMediaButtonEvent] 로 온다.
 *
 * - 켜는 조건은 [TriggerPolicy.sessionActive]: 그룹 있음 + '핸들 버튼으로 무전' + 다른 앱 미디어 재생 없음(+ 멈춘 뒤 15초).
 * - 다른 앱 미디어 감지: `AudioManager.AudioPlaybackCallback` 의 활성 재생 구성 중 용도가 MEDIA·GAME 인 것. 우리 무음 재생 창(직후 1.5초) 안의 MEDIA 는
 *   우리 것과 구별할 수 없어 판단을 미루고([TriggerPolicy.otherMediaFrom] = null), **창이 끝나면 활성 구성을 다시 읽어** 판단한다 — 그 사이 시작된 음악을 놓치지 않게.
 *   우리 무전 재생·TTS 는 USAGE_ASSISTANCE_NAVIGATION_GUIDANCE 라 셈에 들지 않는다.
 * - 세션·콜백은 메인 스레드. 무음 재생은 IO 스레드. 실패해도 예외를 밖으로 던지지 않는다(로그만 — 패널·대시보드 탭은 그대로 된다).
 */
class MediaSessionVoiceTrigger(
    context: Context,
    settings: PttSettings,
    clock: () -> Long = SystemClock::elapsedRealtime,
    private val reclaimDelayMs: Long = TriggerPolicy.RECLAIM_DELAY_MS,
) : BaseVoiceTrigger(settings, clock) {

    private val appContext = context.applicationContext
    private val audioManager: AudioManager? = appContext.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var reclaimJob: Job? = null
    private var silenceRecheckJob: Job? = null

    /** 메인 스레드에서만 */
    private var session: MediaSession? = null
    @Volatile private var otherMediaActive = false
    @Volatile private var otherMediaStoppedAt: Long? = null
    @Volatile private var ownSilenceUntil = 0L

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            onPlaybackConfigs(configs.orEmpty())
        }
    }

    override fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            runCatching { audioManager?.registerAudioPlaybackCallback(playbackCallback, main) }
                .onFailure { DhLog.w("ptt: playback callback 등록 실패 ${it.javaClass.simpleName}") }
            runCatching { audioManager?.activePlaybackConfigurations }.getOrNull()?.let { onPlaybackConfigs(it) }
            // 설정 스위치가 바뀌면 다시 판단
            settings.handleButton.drop(1).collect {
                _status.update { s -> s.copy(handleButton = it) }
                onInputsChanged()
            }
        }
        onInputsChanged()
    }

    override fun stop() {
        job?.cancel(); job = null
        reclaimJob?.cancel(); reclaimJob = null
        silenceRecheckJob?.cancel(); silenceRecheckJob = null
        runCatching { audioManager?.unregisterAudioPlaybackCallback(playbackCallback) }
        main.post { deactivate("stop") }
    }

    private fun onPlaybackConfigs(configs: List<AudioPlaybackConfiguration>) {
        val now = clock()
        val media = configs.any { c ->
            val usage = runCatching { c.audioAttributes.usage }.getOrDefault(AudioAttributes.USAGE_UNKNOWN)
            usage == AudioAttributes.USAGE_MEDIA || usage == AudioAttributes.USAGE_GAME
        }
        // 방금 우리가 낸 무음(USAGE_MEDIA) 창 안이면 판단을 미룬다 — 창이 끝날 때 playSilence 가 건 재확인이 다시 읽는다
        val other = TriggerPolicy.otherMediaFrom(media, now, ownSilenceUntil) ?: return
        if (other == otherMediaActive) return
        otherMediaActive = other
        if (!other) otherMediaStoppedAt = now
        _status.update { it.copy(otherMediaActive = other) }
        DhLog.i("ptt: other media active=$other")
        onInputsChanged()
        if (!other) {
            // 멈춘 뒤 reclaimDelay 가 지나면 다시 판단
            reclaimJob?.cancel()
            reclaimJob = scope.launch {
                delay(reclaimDelayMs + 50)
                onInputsChanged()
            }
        }
    }

    override fun onInputsChanged() {
        val stoppedAgo = otherMediaStoppedAt?.let { clock() - it }
        val want = TriggerPolicy.sessionActive(groupActive.value, settings.handleButton.value, otherMediaActive, stoppedAgo, reclaimDelayMs)
        main.post { if (want) activate() else deactivate(reason()) }
    }

    /** 화면이 앞일 때 재생/정지 키도 세션과 같은 기준으로 양보한다(재생 중 + 멈춘 뒤 [reclaimDelayMs]) */
    override fun yieldingMediaKeys(): Boolean = otherMediaActive || otherMediaStoppedAt?.let { clock() - it < reclaimDelayMs } == true

    private fun reason(): String = when {
        !groupActive.value -> "no-group"
        !settings.handleButton.value -> "setting-off"
        otherMediaActive -> "other-media"
        else -> "reclaim-wait"
    }

    /** 메인 스레드 */
    private fun activate() {
        if (session != null) return
        val s = try {
            MediaSession(appContext, SESSION_TAG).apply {
                setCallback(object : MediaSession.Callback() {
                    override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                        val ev = keyEventOf(mediaButtonIntent) ?: return super.onMediaButtonEvent(mediaButtonIntent)
                        return if (onSessionKey(ev.keyCode, ev.action, ev.repeatCount, ev.eventTime)) true else super.onMediaButtonEvent(mediaButtonIntent)
                    }
                    // 무전 키가 아닌 재생/정지 명령은 무시(우리는 음악을 틀지 않는다)
                    override fun onPlay() = Unit
                    override fun onPause() = Unit
                }, main)
                setPlaybackState(
                    PlaybackState.Builder()
                        .setActions(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE)
                        .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                        .build(),
                )
                isActive = true
            }
        } catch (e: Exception) {
            DhLog.w("ptt: media session 생성 실패 ${e.javaClass.simpleName}")
            _status.update { it.copy(sessionActive = false, detail = "create-${e.javaClass.simpleName}") }
            return
        }
        session = s
        _status.update { it.copy(sessionActive = true, detail = null) }
        DhLog.i("ptt: media session active (HEADSETHOOK·MEDIA_PLAY_PAUSE·MEDIA_PLAY → 무전)")
        playSilence()
    }

    /** 메인 스레드 */
    private fun deactivate(why: String) {
        val s = session ?: return
        session = null
        runCatching { s.isActive = false }
        runCatching { s.release() }
        _status.update { it.copy(sessionActive = false, detail = why) }
        DhLog.i("ptt: media session released why=$why")
    }

    /** 미디어 버튼 라우팅을 우리 쪽으로 — 400 ms 무음(USAGE_MEDIA, 포커스 요청 없음 → 음악을 멈추지 않는다) */
    private fun playSilence() {
        ownSilenceUntil = clock() + SILENCE_MS + SILENCE_GRACE_MS
        // 창이 끝난 직후 활성 재생 구성을 다시 읽는다 — 창 안에서 시작된 음악(판단을 미룬 것)과 우리 무음이 끝난 뒤의 실제 상태를 놓치지 않게
        silenceRecheckJob?.cancel()
        silenceRecheckJob = scope.launch {
            delay(SILENCE_MS + SILENCE_GRACE_MS + RECHECK_MARGIN_MS)
            runCatching { audioManager?.activePlaybackConfigurations }.getOrNull()?.let { onPlaybackConfigs(it) }
        }
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val rate = 16_000
                    val buf = ShortArray(rate * SILENCE_MS / 1000)
                    val track = AudioTrack.Builder()
                        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                        .setBufferSizeInBytes(buf.size * 2)
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .build()
                    try {
                        track.write(buf, 0, buf.size)
                        track.play()
                        delay(SILENCE_MS + 200L)
                    } finally {
                        runCatching { track.stop() }
                        track.release()
                    }
                }.onFailure { DhLog.w("ptt: silence 재생 실패 ${it.javaClass.simpleName}") }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun keyEventOf(intent: Intent): KeyEvent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)

    companion object {
        const val SESSION_TAG = "dh-ptt"
        const val SILENCE_MS = 400
        const val SILENCE_GRACE_MS = 1_100L
        /** 창이 끝나고 다시 읽기까지 여유 */
        const val RECHECK_MARGIN_MS = 100L
    }
}
