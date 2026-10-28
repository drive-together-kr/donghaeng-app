package app.donghaeng.car.adapter.voice

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import ai.pleos.playground.tts.listener.OnServerConnectionListener
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import app.donghaeng.car.core.common.DhLog
import java.util.Locale

/*
 * Pleos TextToSpeech SDK 3.3.1 실측 프로브(2026-10-28, pleos debug 빌드만 — 준비 중 SDK, CLAUDE.md 규칙 5).
 * 목적: 에뮬레이터에서 실제로 소리가 나는지, 서버 모드면 registerApp 키 어디서 막히는지.
 * 호출: adb shell am broadcast -n app.donghaeng.car/.adapter.voice.PleosTtsProbeReceiver --es mode ON_DEVICE|SERVER|HYBRID [--es text …] [--es key …] [--ez register true]
 * 로그: 'pleos tts probe: …' (문장 원문은 남기지 않고 길이만).
 */
class PleosTtsProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val mode = runCatching { Mode.valueOf(intent.getStringExtra("mode") ?: "ON_DEVICE") }.getOrDefault(Mode.ON_DEVICE)
        val text = intent.getStringExtra("text") ?: "아빠 차가 휴게소에 도착했어요."
        PleosTtsProbe.run(context.applicationContext, mode, text, intent.getBooleanExtra("register", false), intent.getStringExtra("key"))
    }
}

object PleosTtsProbe {
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null

    fun run(context: Context, mode: Mode, text: String, register: Boolean, key: String?) = main.post {
        runCatching { tts?.release() }
        val started = System.currentTimeMillis()
        fun ms() = System.currentTimeMillis() - started
        val t = try {
            TextToSpeech(context, mode)
        } catch (e: Throwable) {
            DhLog.w("pleos tts probe: create 실패 mode=$mode ${e.javaClass.simpleName} ${e.message?.take(80)}")
            return@post
        }
        tts = t
        // SDK 는 initialize() 뒤에야 리스너를 받는다(먼저 붙이면 UninitializedPropertyAccessException — 2026-10-28 실측)
        val listener = object : EventListener {
            override fun onStart() { DhLog.i("pleos tts probe: onStart ${ms()}ms") }
            override fun onReady() { DhLog.i("pleos tts probe: onReady ${ms()}ms") }
            override fun onDone() { DhLog.i("pleos tts probe: onDone ${ms()}ms") }
            override fun onError(type: ErrorType) { DhLog.w("pleos tts probe: onError $type ${ms()}ms") }
            override fun onStop() { DhLog.i("pleos tts probe: onStop ${ms()}ms") }
            override fun onUpdatedRms(rms: Double) {}
        }
        val speakNow = {
            runCatching { t.setLocale(Locale.KOREA) }  // "ko" 만 주면 엔진이 "unsupported locale: ko-" 로 실패(2026-10-28 실측) — ko_KR.onFailure { DhLog.w("pleos tts probe: setLocale 실패 ${it.javaClass.simpleName}") }
            runCatching { t.speak(text, true) }
                .onSuccess { DhLog.i("pleos tts probe: speak 요청 mode=$mode len=${text.length} ${ms()}ms") }
                .onFailure { DhLog.w("pleos tts probe: speak 실패 ${it.javaClass.simpleName} ${it.message?.take(80)}") }
        }
        try {
            t.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    DhLog.i("pleos tts probe: initialize 성공 mode=$mode ${ms()}ms")
                    runCatching { t.addEventListener(listener) }.onFailure { DhLog.w("pleos tts probe: addEventListener 실패 ${it.javaClass.simpleName} ${it.message?.take(100)}") }
                    if (register) {
                        // 서버 모드 앱 등록 — 키가 없으면 어디서 막히는지 본다(키 원문은 로그에 남기지 않는다)
                        runCatching {
                            t.registerApp(key ?: "", { DhLog.i("pleos tts probe: registerApp 성공 ${ms()}ms"); speakNow() }, { msg ->
                                DhLog.w("pleos tts probe: registerApp 실패 reason=${msg.take(120)} ${ms()}ms")
                                speakNow()
                            })
                        }.onFailure { DhLog.w("pleos tts probe: registerApp 예외 ${it.javaClass.simpleName} ${it.message?.take(80)}"); speakNow() }
                    } else {
                        speakNow()
                    }
                }
                override fun onFailure(type: ErrorType) { DhLog.w("pleos tts probe: initialize 실패 mode=$mode type=$type ${ms()}ms") }
            })
        } catch (e: Throwable) {
            DhLog.w("pleos tts probe: initialize 예외 ${e.javaClass.simpleName} ${e.message?.take(100)}")
        }
    }

    @Suppress("unused")
    private val unusedServerListener: OnServerConnectionListener? = null
}
