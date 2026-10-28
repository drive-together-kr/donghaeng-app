package app.donghaeng.car.adapter.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import app.donghaeng.car.core.common.DhLog
import app.donghaeng.car.domain.ptt.YesNo
import app.donghaeng.car.domain.ptt.YesNoParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Android `SpeechRecognizer` '네/아니' 단계 (F16 체인 2순위).
 *
 * 2026-10-28 Pleos AVD 실측(F16Probes `stt`): 시스템 서비스 `ai.umos.ambientai/…AmbRecognitionService` 가 있고 `isRecognitionAvailable=true` 지만
 * `startListening` → 곧바로 `onError(9)`(ERROR_INSUFFICIENT_PERMISSIONS — RECORD_AUDIO 승인 상태에서도). 이 오류는 [ListenResult.Failed] `permanent`
 * 로 돌려 체인이 앱이 사는 동안 다시 시도하지 않고 다음 단계(Vosk)로 간다.
 *
 * - 오류 6(말 없음 시간 초과)·7(일치 없음) = [YesNo.NONE](들었지만 답 없음). 9(권한)·12·13(언어)은 영구 실패, 그 밖(네트워크 1·2·4, 3·5·8·10·11)은 이번만 실패.
 * - 결과 N-best 를 [YesNoParser.parseCandidates] 로 판정(아니 우선). 신뢰도는 `CONFIDENCE_SCORES` 첫 값(없으면 null).
 * - `SpeechRecognizer` 는 메인 스레드에서 만들고 써야 한다. 끝·취소 때 `destroy()`.
 */
class AndroidSpeechYesNoRecognizer(
    context: Context,
    private val micGranted: () -> Boolean,
) : YesNoRecognizer {

    private val appContext = context.applicationContext

    override val kind: RecognizerKind = RecognizerKind.ANDROID_STT

    override fun unavailableReason(): String? = when {
        !micGranted() -> "no-mic-permission"
        !runCatching { SpeechRecognizer.isRecognitionAvailable(appContext) }.getOrDefault(false) -> "no-service"
        else -> null
    }

    override suspend fun listen(timeoutMs: Long): ListenResult = withContext(Dispatchers.Main) {
        val done = CompletableDeferred<ListenResult>()
        val recognizer = try {
            SpeechRecognizer.createSpeechRecognizer(appContext)
        } catch (e: Exception) {
            return@withContext ListenResult.Failed("create-${e.javaClass.simpleName}", permanent = true)
        }
        try {
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
                override fun onError(error: Int) {
                    done.complete(resultOfError(error))
                }
                override fun onResults(results: Bundle?) {
                    val candidates = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                    val conf = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)?.firstOrNull()?.takeIf { it >= 0f }
                    done.complete(ListenResult.Heard(YesNoParser.parseCandidates(candidates), conf))
                }
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
            try {
                recognizer.startListening(intent)
            } catch (e: Exception) {
                return@withContext ListenResult.Failed("start-${e.javaClass.simpleName}", permanent = true)
            }
            withTimeoutOrNull(timeoutMs) { done.await() } ?: run {
                // 시간이 다 됐다 — 말한 게 있으면 결과가 곧 온다(최대 1초 더 기다림)
                runCatching { recognizer.stopListening() }
                withTimeoutOrNull(STOP_GRACE_MS) { done.await() } ?: ListenResult.Heard(YesNo.NONE)
            }
        } finally {
            runCatching { recognizer.cancel() }
            runCatching { recognizer.destroy() }
        }
    }

    companion object {
        const val STOP_GRACE_MS = 1_000L

        /** SpeechRecognizer 오류 코드 → 체인 결과(순수 — 단위 테스트) */
        fun resultOfError(error: Int): ListenResult = when (error) {
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT, SpeechRecognizer.ERROR_NO_MATCH -> ListenResult.Heard(YesNo.NONE)
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS, ERROR_LANGUAGE_NOT_SUPPORTED, ERROR_LANGUAGE_UNAVAILABLE ->
                ListenResult.Failed("stt error=$error", permanent = true)
            else -> ListenResult.Failed("stt error=$error", permanent = false)
        }.also { if (it is ListenResult.Failed) DhLog.d("ptt: android stt error=$error") }

        // API 31+ 상수(minSdk 28 이라 값으로 둔다)
        const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
        const val ERROR_LANGUAGE_UNAVAILABLE = 13
    }
}
