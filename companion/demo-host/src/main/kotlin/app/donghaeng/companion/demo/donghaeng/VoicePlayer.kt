package app.donghaeng.companion.demo.donghaeng

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.donghaeng.companion.demo.DemoApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * 오디오 포커스 변화 → 무전 재생기 동작(순수 — 단위 테스트 대상).
 *
 * - `LOSS`(다른 앱이 계속 재생)·`LOSS_TRANSIENT`(통화 등) → 멈춤
 * - `LOSS_TRANSIENT_CAN_DUCK`(내비 안내 음성처럼 짧게 겹치는 소리) → **일시정지**, `GAIN` 이 오면 이어 재생.
 *   무전은 `CONTENT_TYPE_SPEECH` 라 프레임워크가 자동으로 줄여 주지 않는다(말소리를 줄이면 알아듣기 어려워 앱에 맡긴다) —
 *   그대로 두면 길안내와 무전이 같은 크기로 겹친다. 말소리라 볼륨을 줄이기보다 멈췄다가 잇는다(`setWillPauseWhenDucked(true)`).
 */
object VoiceFocusPolicy {
    enum class Action { STOP, PAUSE, RESUME, NONE }

    fun actionFor(focusChange: Int): Action = when (focusChange) {
        AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> Action.STOP
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> Action.PAUSE
        AudioManager.AUDIOFOCUS_GAIN -> Action.RESUME
        else -> Action.NONE
    }

    /** 일시정지 뒤 이만큼 지나도 포커스가 돌아오지 않으면 멈춘다(무전은 길어야 16초) */
    const val PAUSE_TIMEOUT_MS: Long = 30_000
}

/**
 * F16 무전 재생기(호스트 앱 몫 — 모듈은 파일만 준다). 한 번에 하나, 사용자가 [재생] 을 눌렀을 때만 재생한다.
 *
 * - 폰에서는 **자동 재생하지 않는다**: 같은 차에 탄 폰이면 차 스피커(차량 앱이 자동 재생)와 겹치고, 주머니 속 폰이 갑자기 말하면 안 된다.
 *   새 무전은 알림(F)과 목록으로만 알린다.
 * - 오디오: `USAGE_MEDIA` + `CONTENT_TYPE_SPEECH`, 포커스 `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`(음악은 잠시 작아졌다 돌아온다).
 *   포커스를 못 받으면(통화 중 등) 재생하지 않는다. 포커스 변화는 [VoiceFocusPolicy]: LOSS·LOSS_TRANSIENT 면 멈추고,
 *   LOSS_TRANSIENT_CAN_DUCK(다른 앱의 짧은 안내 음성)이면 일시정지했다가 GAIN 에 이어 재생한다(30초 안에 안 돌아오면 멈춤).
 * - 로그: `voice: play voiceId=… durMs=…` / `voice: duck_pause|resume …` / `voice: done|stop|error …` — 내용·닉네임 없음(CLAUDE.md 규칙 8).
 */
object VoicePlayer {

    /**
     * @param voiceId 재생 중(또는 준비 중)인 무전. null = 멈춤
     * @param pausedForFocus 다른 앱의 짧은 안내 음성 때문에 잠시 멈춤(포커스가 돌아오면 이어 재생)
     */
    data class State(
        val voiceId: String? = null,
        val positionMs: Int = 0,
        val durationMs: Int = 0,
        val preparing: Boolean = false,
        val pausedForFocus: Boolean = false,
    ) {
        val fraction: Float get() = if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    // 처음 재생할 때 만든다 — 상태만 읽거나 stop() 하는 곳(DongHaengStore, JVM 단위 테스트)에서 안드로이드 객체를 만들지 않게
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private val attributes: AudioAttributes by lazy {
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }

    private var player: MediaPlayer? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var ticker: Job? = null

    /** [player] 가 준비를 마쳤다(start·pause 를 불러도 된다) */
    private var isPrepared = false

    /** CAN_DUCK 으로 일시정지 중 — GAIN 이 오면 이어 재생 */
    private var pausedForFocus = false

    private val pauseTimeout = Runnable { if (pausedForFocus) stop("focus_timeout") }

    // 리스너·MediaPlayer 콜백·UI 모두 메인 스레드(mainHandler·메인 루퍼)에서 돈다
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (VoiceFocusPolicy.actionFor(change)) {
            VoiceFocusPolicy.Action.STOP -> stop("focus_loss")
            VoiceFocusPolicy.Action.PAUSE -> pauseForFocus()
            VoiceFocusPolicy.Action.RESUME -> resumeAfterFocus()
            VoiceFocusPolicy.Action.NONE -> Unit
        }
    }

    /**
     * 같은 무전을 재생 중이면 멈추고, 아니면 지금 것을 멈추고 [item] 을 재생한다.
     * @return 사용자에게 보여 줄 안내(재생하지 못한 이유). 재생을 시작했거나 멈췄으면 null
     */
    fun toggle(context: Context, item: VoiceItem): String? {
        if (_state.value.voiceId == item.voiceId) {
            stop("user")
            return null
        }
        return play(context, item)
    }

    private fun play(context: Context, item: VoiceItem): String? {
        stop("switch")
        voiceStatusText(item.status)?.let { return it }
        val path = item.localPath ?: return "아직 받는 중이에요"
        if (!File(path).isFile) {
            // 모듈이 지웠다(서버 시계로 보관 기간이 끝남·캐시 한도) — 보관 중이면 다음 스냅샷에서 다시 받는다
            Log.w(DemoApplication.TAG, "voice: file missing voiceId=${item.voiceId}")
            return "무전 파일이 없어요. 보관 기간이 지났거나 다시 받는 중이에요"
        }
        val am = context.applicationContext.getSystemService(AudioManager::class.java) ?: return "재생할 수 없어요"
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            // 짧은 안내 음성이 끼어들면(CAN_DUCK) 줄이지 않고 스스로 멈춘다 — 콜백을 꼭 받는다(VoiceFocusPolicy)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener(focusListener, mainHandler)
            .build()
        if (am.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.w(DemoApplication.TAG, "voice: focus denied voiceId=${item.voiceId}")
            return "지금은 재생할 수 없어요(통화 중이거나 다른 소리가 재생 중)"
        }
        val mp = MediaPlayer()
        isPrepared = false
        pausedForFocus = false
        try {
            mp.setAudioAttributes(attributes)
            mp.setDataSource(path)
            mp.setOnPreparedListener { ready ->
                if (player !== ready) return@setOnPreparedListener
                isPrepared = true
                // 준비 중에 안내 음성이 끼어들었으면(CAN_DUCK) 포커스가 돌아올 때 시작한다
                if (!pausedForFocus) ready.start()
                val duration = ready.duration.takeIf { it > 0 } ?: item.durationMs
                _state.value = State(item.voiceId, 0, duration, pausedForFocus = pausedForFocus)
                startTicker(ready)
            }
            mp.setOnCompletionListener { done -> if (player === done) finish("done", item.voiceId) }
            mp.setOnErrorListener { failed, what, extra ->
                Log.w(DemoApplication.TAG, "voice: error voiceId=${item.voiceId} what=$what extra=$extra")
                if (player === failed) finish("error", item.voiceId)
                true
            }
            player = mp
            audioManager = am
            focusRequest = request
            _state.value = State(item.voiceId, 0, item.durationMs, preparing = true)
            mp.prepareAsync()
        } catch (e: Exception) {
            Log.w(DemoApplication.TAG, "voice: cannot play voiceId=${item.voiceId} cause=${e.javaClass.simpleName}")
            player = null
            runCatching { mp.release() }
            am.abandonAudioFocusRequest(request)
            audioManager = null
            focusRequest = null
            _state.value = State()
            return "재생할 수 없어요"
        }
        Log.i(DemoApplication.TAG, "voice: play voiceId=${item.voiceId} durMs=${item.durationMs} mine=${item.mine}")
        return null
    }

    /** 재생을 멈추고 포커스를 돌려준다. 재생 중이 아니면 아무 일도 하지 않는다 */
    fun stop(reason: String = "user") {
        val current = _state.value.voiceId ?: return
        finish(reason, current)
    }

    /** CAN_DUCK — 다른 앱의 짧은 안내 음성. 말소리끼리 겹치지 않게 잠시 멈춘다(포커스는 그대로 쥐고 GAIN 을 기다린다) */
    private fun pauseForFocus() {
        val mp = player ?: return
        val voiceId = _state.value.voiceId ?: return
        if (pausedForFocus) return
        pausedForFocus = true
        if (isPrepared) runCatching { if (mp.isPlaying) mp.pause() }
        _state.value = _state.value.copy(pausedForFocus = true)
        mainHandler.removeCallbacks(pauseTimeout)
        mainHandler.postDelayed(pauseTimeout, VoiceFocusPolicy.PAUSE_TIMEOUT_MS)
        Log.i(DemoApplication.TAG, "voice: duck_pause voiceId=$voiceId")
    }

    /** GAIN — 안내 음성이 끝났다. CAN_DUCK 으로 멈췄던 무전을 이어 재생한다 */
    private fun resumeAfterFocus() {
        val mp = player ?: return
        if (!pausedForFocus) return
        pausedForFocus = false
        mainHandler.removeCallbacks(pauseTimeout)
        if (isPrepared) runCatching { mp.start() }
        _state.value = _state.value.copy(pausedForFocus = false)
        Log.i(DemoApplication.TAG, "voice: resume voiceId=${_state.value.voiceId}")
    }

    private fun finish(reason: String, voiceId: String) {
        ticker?.cancel()
        ticker = null
        mainHandler.removeCallbacks(pauseTimeout)
        player?.let { mp ->
            runCatching { if (mp.isPlaying) mp.stop() }
            runCatching { mp.release() }
        }
        player = null
        isPrepared = false
        pausedForFocus = false
        focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        focusRequest = null
        audioManager = null
        _state.value = State()
        Log.i(DemoApplication.TAG, "voice: $reason voiceId=$voiceId")
    }

    private fun startTicker(mp: MediaPlayer) {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive && player === mp) {
                val position = runCatching { mp.currentPosition }.getOrDefault(0)
                _state.value = _state.value.copy(positionMs = position, preparing = false)
                delay(200)
            }
        }
    }
}
