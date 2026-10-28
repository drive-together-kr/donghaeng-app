package app.donghaeng.car.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.KeyEvent
import app.donghaeng.car.core.common.DhLog
import java.io.File

/*
 * F16 무전 사전 실측(2026-10-28, debug 빌드만): 순정 내비가 앞에 있을 때
 *  ① 핸들/미디어 키(HEADSETHOOK·MEDIA_PLAY_PAUSE·SEARCH)가 MediaSession 으로 들어오는가
 *  ② Android TextToSpeech 가 시스템 엔진(ai.pleos.playground.caas)으로 초기화되는가 — 기본/명시 엔진
 *  ③ Android SpeechRecognizer(시스템 = ai.umos.ambientai)가 한국어 단어를 받는가(네트워크·계정 의존 여부)
 *  ④ MediaRecorder(AAC 16 kHz 모노)로 녹음 파일이 만들어지는가(AVD hw.audioInput 설정에 따라)
 * 호출: adb shell am broadcast -n app.donghaeng.car/.debug.F16ProbeReceiver --es probe <media_on|media_off|tts|tts_engine|stt|rec> [--es engine <pkg>] [--ei sec 3]
 * 결과는 로그 'f16 probe: …' 한 줄씩(문장·좌표·닉네임 없음).
 */
class F16ProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (val p = intent.getStringExtra("probe")) {
            "media_on" -> F16Probes.mediaOn(app)
            "media_off" -> F16Probes.mediaOff()
            "tts" -> F16Probes.tts(app, null)
            "tts_engine" -> F16Probes.tts(app, intent.getStringExtra("engine") ?: "ai.pleos.playground.caas")
            "stt" -> F16Probes.stt(app, intent.getIntExtra("sec", 5))
            "rec" -> F16Probes.rec(app, intent.getIntExtra("sec", 3))
            else -> DhLog.w("f16 probe: 모르는 probe=$p")
        }
    }
}

object F16Probes {
    private val main = Handler(Looper.getMainLooper())
    private var session: MediaSession? = null
    private var tts: TextToSpeech? = null

    /** ① 활성 MediaSession + 짧은 무음 재생(미디어 버튼 라우팅은 '최근 재생한 세션'을 고른다) */
    fun mediaOn(context: Context) = main.post {
        if (session == null) {
            session = MediaSession(context, "dh-ptt-probe").apply {
                setCallback(object : MediaSession.Callback() {
                    override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                        @Suppress("DEPRECATION")
                        val ev = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                        DhLog.i("f16 probe: media button ${ev?.let { KeyEvent.keyCodeToString(it.keyCode) }} action=${ev?.action}")
                        return true
                    }
                    override fun onPlay() { DhLog.i("f16 probe: media onPlay") }
                    override fun onPause() { DhLog.i("f16 probe: media onPause") }
                })
                setPlaybackState(
                    PlaybackState.Builder()
                        .setActions(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE)
                        .setState(PlaybackState.STATE_PLAYING, 0, 1f).build(),
                )
                isActive = true
            }
        }
        playSilence(400)
        DhLog.i("f16 probe: media session active=${session?.isActive}")
    }

    fun mediaOff() = main.post {
        session?.run { isActive = false; release() }
        session = null
        DhLog.i("f16 probe: media session released")
    }

    private fun playSilence(ms: Int) {
        Thread {
            runCatching {
                val rate = 16_000
                val buf = ShortArray(rate * ms / 1000)
                val track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(buf.size * 2).setTransferMode(AudioTrack.MODE_STATIC).build()
                track.write(buf, 0, buf.size)
                track.play()
                Thread.sleep(ms.toLong() + 200)
                track.release()
                DhLog.i("f16 probe: silence played ${ms}ms")
            }.onFailure { DhLog.w("f16 probe: silence 실패 ${it.javaClass.simpleName}") }
        }.start()
    }

    /** ② TextToSpeech — engine=null 이면 기본 엔진 */
    fun tts(context: Context, engine: String?) = main.post {
        tts?.shutdown()
        val started = System.currentTimeMillis()
        lateinit var t: TextToSpeech
        val listener = TextToSpeech.OnInitListener { status ->
            val engines = runCatching { t.engines.joinToString { it.name } }.getOrDefault("?")
            val lang = runCatching { t.setLanguage(java.util.Locale.KOREAN) }.getOrDefault(-99)
            DhLog.i("f16 probe: tts init status=$status engine=${engine ?: "default"} default=${runCatching { t.defaultEngine }.getOrNull()} engines=[$engines] setLanguage(ko)=$lang ${System.currentTimeMillis() - started}ms")
            if (status == TextToSpeech.SUCCESS) {
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) { DhLog.i("f16 probe: tts onStart") }
                    override fun onDone(id: String?) { DhLog.i("f16 probe: tts onDone") }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) { DhLog.w("f16 probe: tts onError") }
                    override fun onError(utteranceId: String?, errorCode: Int) { DhLog.w("f16 probe: tts onError code=$errorCode") }
                })
                val r = t.speak("보낼까요? 네 또는 아니라고 말하세요.", TextToSpeech.QUEUE_FLUSH, Bundle(), "f16")
                DhLog.i("f16 probe: tts speak result=$r")
            }
        }
        t = if (engine == null) TextToSpeech(context, listener) else TextToSpeech(context, listener, engine)
        tts = t
    }

    /** ③ SpeechRecognizer — 시스템 인식 서비스로 한국어 단어 인식 */
    fun stt(context: Context, sec: Int) = main.post {
        val available = SpeechRecognizer.isRecognitionAvailable(context)
        DhLog.i("f16 probe: stt available=$available")
        if (!available) return@post
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { DhLog.i("f16 probe: stt ready") }
            override fun onBeginningOfSpeech() { DhLog.i("f16 probe: stt begin") }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { DhLog.i("f16 probe: stt end") }
            override fun onError(error: Int) { DhLog.w("f16 probe: stt error=$error"); r.destroy() }
            override fun onResults(results: Bundle?) {
                val n = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.size ?: 0
                DhLog.i("f16 probe: stt results count=$n firstLen=${results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.length}")
                r.destroy()
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        runCatching { r.startListening(i) }.onFailure { DhLog.w("f16 probe: stt start 실패 ${it.javaClass.simpleName}") }
        main.postDelayed({ runCatching { r.stopListening() } }, sec * 1000L)
    }

    /** ④ MediaRecorder AAC 16 kHz 모노 [sec] 초 */
    fun rec(context: Context, sec: Int) {
        val out = File(context.cacheDir, "f16_probe.m4a")
        val rec = runCatching {
            @Suppress("DEPRECATION")
            MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(16_000)
                setAudioChannels(1)
                setAudioEncodingBitRate(24_000)
                setOutputFile(out.absolutePath)
                prepare()
                start()
            }
        }.onFailure { DhLog.w("f16 probe: rec start 실패 ${it.javaClass.simpleName} ${it.message?.take(60)}") }.getOrNull() ?: return
        var peak = 0
        val sampler = object : Runnable {
            override fun run() { peak = maxOf(peak, runCatching { rec.maxAmplitude }.getOrDefault(0)); main.postDelayed(this, 100) }
        }
        main.post(sampler)
        main.postDelayed({
            main.removeCallbacks(sampler)
            runCatching { rec.stop() }.onFailure { DhLog.w("f16 probe: rec stop 실패 ${it.javaClass.simpleName}") }
            rec.release()
            DhLog.i("f16 probe: rec done bytes=${out.length()} peakAmp=$peak sec=$sec")
        }, sec * 1000L)
    }
}
