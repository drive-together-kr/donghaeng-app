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
 * F16 무전 재생기(호스트 앱 몫 — 모듈은 파일만 준다). 한 번에 하나, 사용자가 [재생] 을 눌렀을 때만 재생한다.
 *
 * - 폰에서는 **자동 재생하지 않는다**: 같은 차에 탄 폰이면 차 스피커(차량 앱이 자동 재생)와 겹치고, 주머니 속 폰이 갑자기 말하면 안 된다.
 *   새 무전은 알림(F)과 목록으로만 알린다.
 * - 오디오: `USAGE_MEDIA` + `CONTENT_TYPE_SPEECH`, 포커스 `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`(음악은 잠시 작아졌다 돌아온다).
 *   포커스를 못 받으면(통화 중 등) 재생하지 않는다. 포커스를 잃으면(LOSS·LOSS_TRANSIENT) 멈춘다.
 * - 로그: `voice: play voiceId=… durMs=…` / `voice: done|stop|error …` — 내용·닉네임 없음(CLAUDE.md 규칙 8).
 */
object VoicePlayer {

    /** @param voiceId 재생 중(또는 준비 중)인 무전. null = 멈춤 */
    data class State(
        val voiceId: String? = null,
        val positionMs: Int = 0,
        val durationMs: Int = 0,
        val preparing: Boolean = false,
    ) {
        val fraction: Float get() = if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val attributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private var player: MediaPlayer? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var ticker: Job? = null

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) stop("focus_loss")
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
            Log.w(DemoApplication.TAG, "voice: file missing voiceId=${item.voiceId}")
            return "무전 파일이 없어요. 잠시 뒤 다시 시도해 주세요"
        }
        val am = context.applicationContext.getSystemService(AudioManager::class.java) ?: return "재생할 수 없어요"
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(focusListener, mainHandler)
            .build()
        if (am.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.w(DemoApplication.TAG, "voice: focus denied voiceId=${item.voiceId}")
            return "지금은 재생할 수 없어요(통화 중이거나 다른 소리가 재생 중)"
        }
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(attributes)
            mp.setDataSource(path)
            mp.setOnPreparedListener { prepared ->
                if (player !== prepared) return@setOnPreparedListener
                prepared.start()
                val duration = prepared.duration.takeIf { it > 0 } ?: item.durationMs
                _state.value = State(item.voiceId, 0, duration)
                startTicker(prepared)
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

    private fun finish(reason: String, voiceId: String) {
        ticker?.cancel()
        ticker = null
        player?.let { mp ->
            runCatching { if (mp.isPlaying) mp.stop() }
            runCatching { mp.release() }
        }
        player = null
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
