package app.donghaeng.car.adapter.speech

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import android.content.Context
import android.content.pm.PackageManager
import app.donghaeng.car.core.common.DhLog
import app.donghaeng.car.domain.ptt.YesNo
import app.donghaeng.car.domain.ptt.YesNoParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * Pleos `SpeechToText` SDK(3.3.1, Nexus) '네/아니' 단계 — F16 체인 1순위, **선택적**(CLAUDE.md 규칙 5: 준비 중 SDK 에 기능적으로 의존하지 않는다).
 *
 * 쓰는 조건(모두 참일 때만 — 하나라도 아니면 [unavailableReason] 으로 건너뛰고 체인이 Android SpeechRecognizer → Vosk → 카운트다운으로 간다):
 * 1. CaaS(`ai.pleos.playground.caas`) 설치 + 버전 ≥ SDK 최소 4.1.29(versionCode 4001029, SDK 매니페스트 meta-data) — AVD 실측 4.2.10.
 * 2. `pleos.car.permission.STT_SERVICE`(normal) 승인 — 없으면 SDK 의 모든 호출이 SecurityException.
 * 3. `initialize` 가 [INIT_TIMEOUT_MS] 안에 onSuccess. 실패는 영구 실패로 돌려 앱이 사는 동안 다시 시도하지 않는다.
 *
 * `registerApp(appId, appKey)`(서버 STT·UA 인증) 키는 발급 경로를 모른다(docs/10 Q9) → 부르지 않고 **ON_DEVICE** 모드만 쓴다. 인증이 필요하면
 * `onError(UNAUTHENTICATED)` 로 끝나 영구 실패 → 다음 단계. 인식 문장은 [YesNoParser] 판정만 남기고 버린다(규칙 8).
 * SDK 타입은 이 파일(adapter/) 밖으로 나가지 않는다(규칙 1). 리스너는 등록한 것과 같은 객체로 해제한다(규칙 3).
 */
class PleosSttYesNoRecognizer(context: Context) : YesNoRecognizer {

    private val appContext = context.applicationContext

    override val kind: RecognizerKind = RecognizerKind.PLEOS_STT

    override fun unavailableReason(): String? {
        if (appContext.checkSelfPermission(STT_PERMISSION) != PackageManager.PERMISSION_GRANTED) return "no-stt-permission"
        val version = try {
            appContext.packageManager.getPackageInfo(CAAS_PACKAGE, 0).longVersionCode
        } catch (e: PackageManager.NameNotFoundException) {
            return "no-caas"
        } catch (e: Exception) {
            return "caas-${e.javaClass.simpleName}"
        }
        if (version < CAAS_MIN_VERSION_CODE) return "caas-old v=$version"
        return null
    }

    override suspend fun listen(timeoutMs: Long): ListenResult = withContext(Dispatchers.Main) {
        val stt = try {
            SpeechToText(appContext, Mode.ON_DEVICE)
        } catch (e: Throwable) {
            return@withContext ListenResult.Failed("create-${e.javaClass.simpleName}", permanent = true)
        }
        val initialized = CompletableDeferred<ErrorType?>()
        try {
            stt.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    initialized.complete(null)
                }
                override fun onFailure(errorType: ErrorType) {
                    initialized.complete(errorType)
                }
            })
        } catch (e: Throwable) {
            runCatching { stt.release() }
            return@withContext ListenResult.Failed("init-${e.javaClass.simpleName}", permanent = true)
        }
        val initResult = withTimeoutOrNull(INIT_TIMEOUT_MS) { initialized.await() }
        if (!initialized.isCompleted || initResult != null) {
            runCatching { stt.release() }
            val why = if (!initialized.isCompleted) "init-timeout" else "init ${initResult}"
            return@withContext ListenResult.Failed(why, permanent = true)
        }

        val done = CompletableDeferred<ListenResult>()
        val listener = object : ResultListener {
            override fun onUpdated(text: String, isFinal: Boolean) {
                val verdict = YesNoParser.parse(text)
                if (isFinal || verdict != YesNo.NONE) done.complete(ListenResult.Heard(verdict))
            }
            override fun onUpdatedEpdData(epdOn: Long, epdOff: Long) = Unit
            override fun onStartedRecognition() = Unit
            override fun onEndedRecognition() = Unit
            override fun onError(errorType: ErrorType) {
                done.complete(ListenResult.Failed("stt $errorType", permanent = errorType in PERMANENT_ERRORS))
            }
            override fun onReady() = Unit
            override fun onUpdatedRms(rms: Double) = Unit
        }
        try {
            stt.addListener(listener)
            stt.setLocale(Locale.KOREAN)
            stt.request()
            withTimeoutOrNull(timeoutMs) { done.await() } ?: run {
                runCatching { stt.stop() }
                withTimeoutOrNull(STOP_GRACE_MS) { done.await() } ?: ListenResult.Heard(YesNo.NONE)
            }
        } catch (e: Throwable) {
            ListenResult.Failed("request-${e.javaClass.simpleName}", permanent = e is SecurityException)
        } finally {
            runCatching { stt.stop() }
            runCatching { stt.removeListener(listener) }
            runCatching { stt.release() }
        }
    }.also { if (it is ListenResult.Failed) DhLog.i("ptt: pleos stt ${it.reason}") }

    companion object {
        const val CAAS_PACKAGE = "ai.pleos.playground.caas"
        /** SpeechToText 3.3.1 매니페스트 `ai.pleos.playground.caas.min_version_code` */
        const val CAAS_MIN_VERSION_CODE = 4_001_029L
        const val STT_PERMISSION = "pleos.car.permission.STT_SERVICE"
        const val INIT_TIMEOUT_MS = 3_000L
        const val STOP_GRACE_MS = 1_000L

        /** 다시 시도해도 안 될 오류 — 앱이 사는 동안 이 단계를 건너뛴다 */
        private val PERMANENT_ERRORS = setOf(
            ErrorType.NOT_SUPPORTED_API, ErrorType.UNAUTHENTICATED, ErrorType.PERMISSION_DENIED,
            ErrorType.UNAVAILABLE_LANGUAGE, ErrorType.ON_DEVICE_ENGINE_ERROR,
        )
    }
}
