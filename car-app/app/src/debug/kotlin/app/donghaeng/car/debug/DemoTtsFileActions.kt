package app.donghaeng.car.debug

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import app.donghaeng.car.core.common.DhLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale

/**
 * 시연 영상용 — 앱이 읽는 안내 문장을 **같은 TTS 엔진**으로 WAV 파일에 바로 쓴다(스피커 녹음은 에뮬레이터 부하로 소리가 깨진다).
 *
 * ```
 * adb shell am broadcast -n app.donghaeng.car/.debug.DemoControlReceiver --es action tts_file --es name low_energy --es text "아빠 차 배터리가 12% 남았어요."
 * adb exec-out run-as app.donghaeng.car --user 10 cat files/tts/low_energy.wav > low_energy.wav
 * ```
 * 로그에는 파일 이름·길이·결과만 남긴다(문장 원문 금지, CLAUDE.md 8). debug 빌드에만 있다.
 */
object DemoTtsFileActions {

    val ACTIONS = setOf("tts_file")

    suspend fun handle(context: Context, action: String, intent: Intent) {
        val text = intent.getStringExtra("text").orEmpty()
        val name = intent.getStringExtra("name").orEmpty().filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(40)
        if (text.isBlank() || name.isEmpty()) {
            DhLog.w("demo: tts_file text·name 필요")
            return
        }
        val rate = intent.getFloatExtra("rate", 1.0f)
        val dir = File(context.filesDir, "tts").apply { mkdirs() }
        val out = File(dir, "$name.wav")
        val ok = synthesize(context.applicationContext, text, out, rate)
        DhLog.i("demo: tts_file name=$name len=${text.length} ok=$ok bytes=${if (out.exists()) out.length() else 0}")
    }

    private suspend fun synthesize(context: Context, text: String, out: File, rate: Float): Boolean {
        val ready = CompletableDeferred<Boolean>()
        val done = CompletableDeferred<Boolean>()
        val tts = withContext(Dispatchers.Main) { TextToSpeech(context) { ready.complete(it == TextToSpeech.SUCCESS) } }
        try {
            if (withTimeoutOrNull(15_000) { ready.await() } != true) return false
            tts.setLanguage(Locale.KOREA)
            tts.setSpeechRate(rate)
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) { done.complete(true) }
                @Deprecated("API 21 미만 호환용")
                override fun onError(utteranceId: String?) { done.complete(false) }
                override fun onError(utteranceId: String?, errorCode: Int) { done.complete(false) }
            })
            if (tts.synthesizeToFile(text, Bundle(), out, "tts-file") != TextToSpeech.SUCCESS) return false
            return withTimeoutOrNull(60_000) { done.await() } == true
        } finally {
            tts.shutdown()
        }
    }
}
