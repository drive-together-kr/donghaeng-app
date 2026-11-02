package app.donghaeng.car.adapter.speech

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.donghaeng.car.core.common.DhLog
import app.donghaeng.car.domain.ptt.YesNo
import app.donghaeng.car.domain.ptt.YesNoParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * Pleos `SpeechToText` SDK(3.3.1, Nexus) '네/아니' 단계 — F16 체인 1순위, **선택적**(CLAUDE.md 규칙 5: 준비 중 SDK 에 기능적으로 의존하지 않는다).
 *
 * 쓰는 조건(모두 참일 때만 — 하나라도 아니면 [unavailableReason] 으로 건너뛰고 체인이 Android SpeechRecognizer → Vosk → 카운트다운으로 간다):
 * 1. CaaS(`ai.pleos.playground.caas`) 설치 + 버전 ≥ SDK 최소 4.1.29(versionCode 4001029, SDK 매니페스트 meta-data) — AVD 실측 4.2.10.
 * 2. `pleos.car.permission.STT_SERVICE`(normal) 승인 — 없으면 SDK 의 모든 호출이 SecurityException.
 * 3. `initialize` 가 [INIT_TIMEOUT_MS] 안에 onSuccess. onFailure·예외는 영구 실패로 돌려 앱이 사는 동안 다시 시도하지 않는다.
 *    **시간 초과는 이번만 실패**(B045 — 차량 부팅 직후 CaaS 콜드 스타트로 3 s 를 넘겨도 프로세스 내내 음성 확인이 빠지지 않게): 다음 녹음 때 다시 시도하고,
 *    [PleosSttInitPolicy.MAX_TIMEOUTS]번 연달아 넘길 때만 영구 실패로 굳힌다(TTS 의 `PleosTtsInitPolicy` 와 같은 생각).
 *
 * **판정(B014)**: 부분 결과(`isFinal=false` — SDK `UpdateSttResult`)로는 **아니만** 바로 끝내고, **네는 최종 결과**(`isFinal=true` — 끝점 검출 뒤
 * `UpdateSttCompleted`)에서만 확정한다([YesNoParser.streamVerdict]) — "어… 아니 보내지 마"의 첫 부분 결과 "어"로 무전이 나가지 않게.
 * 시간이 다 돼 최종 결과가 끝내 오지 않으면 무응답(NONE — 보관)으로 본다.
 *
 * **인스턴스 재사용**: `initialize`(CaaS 서비스 연결, 최대 [INIT_TIMEOUT_MS])는 [prepare](녹음 중)에 한 번 하고, 같은 인스턴스로 `request`/`stop` 을
 * 여러 번 한다(NONE 재듣기 포함). 확인 5초가 연결을 기다리느라 줄지 않게 하고, 그래도 [listen] 안에서 연결을 기다렸으면 그만큼 듣기 상한을 줄여
 * 체인의 남은 시간 계산(→ 컨트롤러 확인 상한 5 s + 1.5 s)을 넘지 않는다. [IDLE_RELEASE_MS] 동안 안 쓰면 놓는다(release).
 * 듣다가 오류가 나면 인스턴스를 버린다(다음에 새로 만든다). 취소돼도(확인 중 누름·주입 답) 리스너 해제·필요하면 release 는 반드시 한다.
 *
 * `registerApp(appId, appKey)`(서버 STT·UA 인증) 키는 발급 경로를 모른다(docs/10 Q9) → 부르지 않고 **ON_DEVICE** 모드만 쓴다. 인증이 필요하면
 * `onError(UNAUTHENTICATED)` 로 끝나 영구 실패 → 다음 단계. registerApp 을 켜려면 security-crypto 1.1.x 가 필요하다(build.gradle.kts 주석, docs/10 Q9).
 * 인식 문장은 [YesNoParser] 판정만 남기고 버린다(규칙 8).
 * SDK 타입은 이 파일(adapter/) 밖으로 나가지 않는다(규칙 1). 리스너는 등록한 것과 같은 객체로 해제한다(규칙 3). SDK 호출은 모두 메인 스레드.
 */
class PleosSttYesNoRecognizer(context: Context) : YesNoRecognizer {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val initLock = Mutex()

    /** 초기화된 인스턴스(메인 스레드에서만 읽고 쓴다) */
    private var ready: SpeechToText? = null

    /** 초기화 영구 실패 사유 — 있으면 앱이 사는 동안 이 단계를 건너뛴다 */
    @Volatile private var initFailure: String? = null

    /** 연달아 난 초기화 시간 초과 수(성공하면 0) — [PleosSttInitPolicy] */
    @Volatile private var initTimeouts = 0

    private val idleRelease = Runnable { releaseReady("idle") }

    override val kind: RecognizerKind = RecognizerKind.PLEOS_STT

    override fun unavailableReason(): String? {
        initFailure?.let { return it }
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

    /** 녹음하는 동안 CaaS 연결·초기화를 미리 해 둔다(실패는 [initFailure] 로 남는다) */
    override suspend fun prepare() {
        if (unavailableReason() != null) return
        ensureReady()
        withContext(Dispatchers.Main) { scheduleIdleRelease() }
    }

    /**
     * 초기화된 인스턴스 — 없으면 만들고 `initialize` 를 기다린다(최대 [INIT_TIMEOUT_MS]). 영구 실패면 null.
     * 기다리는 중에 취소되면 만든 인스턴스를 놓고(release) 실패로 남기지 않는다 — 다음 확인에서 다시 시도한다.
     */
    private suspend fun ensureReady(): SpeechToText? = initLock.withLock {
        if (initFailure != null) return@withLock null
        withContext(Dispatchers.Main) {
            // 곧 쓴다 — 쉬는 동안 놓기 타이머를 멈춘다(listen 이 끝나면 다시 건다)
            main.removeCallbacks(idleRelease)
            ready?.let { return@withContext it }
            val stt = try {
                SpeechToText(appContext, Mode.ON_DEVICE)
            } catch (e: Throwable) {
                initFailure = "create-${e.javaClass.simpleName}"
                return@withContext null
            }
            var ok = false
            try {
                val initialized = CompletableDeferred<ErrorType?>()
                stt.initialize(object : OnInitializeListener {
                    override fun onSuccess() {
                        initialized.complete(null)
                    }
                    override fun onFailure(errorType: ErrorType) {
                        initialized.complete(errorType)
                    }
                })
                val started = SystemClock.elapsedRealtime()
                val result = withTimeoutOrNull(INIT_TIMEOUT_MS) { initialized.await() }
                when {
                    !initialized.isCompleted -> {
                        // 시간 초과는 이번만 — 연달아 [MAX_TIMEOUTS]번이면 굳힌다(B045)
                        val n = ++initTimeouts
                        if (PleosSttInitPolicy.permanentAfter(n)) initFailure = "init-timeout x$n"
                        else DhLog.i("ptt: pleos stt init-timeout ${INIT_TIMEOUT_MS}ms ($n/${PleosSttInitPolicy.MAX_TIMEOUTS}) → 이번만 건너뜀, 다음 녹음 때 다시")
                    }
                    result != null -> initFailure = "init $result"
                    else -> {
                        ok = true
                        ready = stt
                        initTimeouts = 0
                        DhLog.i("ptt: pleos stt initialized ${SystemClock.elapsedRealtime() - started}ms")
                    }
                }
            } catch (e: CancellationException) {
                throw e   // finally 가 놓는다 — 실패로 남기지 않는다(다음 확인에서 다시)
            } catch (e: Throwable) {
                initFailure = "init-${e.javaClass.simpleName}"
            } finally {
                if (!ok) runCatching { stt.release() }
            }
            initFailure?.let { DhLog.i("ptt: pleos stt $it (영구 실패 — 이 단계를 건너뛴다)") }
            if (ok) stt else null
        }
    }

    override suspend fun listen(timeoutMs: Long): ListenResult {
        val t0 = SystemClock.elapsedRealtime()
        val stt = ensureReady() ?: return ListenResult.Failed(initFailure ?: if (initTimeouts > 0) "init-timeout" else "init-unavailable", permanent = initFailure != null)
        // 연결을 여기서 기다렸으면 그만큼 듣기를 줄인다 — 체인의 남은 시간(확인 5초)을 넘지 않게
        val budget = timeoutMs - (SystemClock.elapsedRealtime() - t0)
        if (budget < MIN_LISTEN_MS) {
            DhLog.i("ptt: pleos stt init-slow budget=${budget}ms → 다음 단계")
            return ListenResult.Failed("init-slow", permanent = false)
        }
        return withContext(Dispatchers.Main) {
            main.removeCallbacks(idleRelease)
            val done = CompletableDeferred<ListenResult>()
            val listener = object : ResultListener {
                override fun onUpdated(text: String, isFinal: Boolean) {
                    // 아니는 부분 결과에서도 바로, 네는 최종 결과에서만(B014)
                    YesNoParser.streamVerdict(text, isFinal)?.let { done.complete(ListenResult.Heard(it)) }
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
            var keep = false
            try {
                stt.addListener(listener)
                // "ko"(Locale.KOREAN)만 주면 CaaS 엔진이 "unsupported locale: ko-" 로 ON_DEVICE_ENGINE_ERROR — TTS 와 같은 원인(docs/10 R19, 2026-10-28 실측) → ko_KR
                stt.setLocale(Locale.KOREA)
                stt.request()
                val r = withTimeoutOrNull(budget) { done.await() } ?: run {
                    runCatching { stt.stop() }
                    withTimeoutOrNull(STOP_GRACE_MS) { done.await() } ?: ListenResult.Heard(YesNo.NONE)
                }
                keep = r is ListenResult.Heard
                if (r is ListenResult.Failed && r.permanent) initFailure = r.reason
                r
            } catch (e: CancellationException) {
                keep = true   // 취소(확인 중 누름·주입 답)는 인스턴스 탓이 아니다 — 멈추고 다시 쓴다
                throw e
            } catch (e: Throwable) {
                if (e is SecurityException) initFailure = "request-SecurityException"
                ListenResult.Failed("request-${e.javaClass.simpleName}", permanent = e is SecurityException)
            } finally {
                // 동기 SDK 호출뿐이라 취소된 뒤에도 그대로 돈다(메인 스레드)
                runCatching { stt.stop() }
                runCatching { stt.removeListener(listener) }
                if (keep) scheduleIdleRelease() else releaseReady("error")
            }
        }.also { if (it is ListenResult.Failed) DhLog.i("ptt: pleos stt ${it.reason}") }
    }

    /** 메인 스레드 */
    private fun scheduleIdleRelease() {
        main.removeCallbacks(idleRelease)
        if (ready != null) main.postDelayed(idleRelease, IDLE_RELEASE_MS)
    }

    /** 메인 스레드 */
    private fun releaseReady(why: String) {
        main.removeCallbacks(idleRelease)
        val stt = ready ?: return
        ready = null
        runCatching { stt.release() }
        DhLog.i("ptt: pleos stt released why=$why")
    }

    companion object {
        const val CAAS_PACKAGE = "ai.pleos.playground.caas"
        /** SpeechToText 3.3.1 매니페스트 `ai.pleos.playground.caas.min_version_code` */
        const val CAAS_MIN_VERSION_CODE = 4_001_029L
        const val STT_PERMISSION = "pleos.car.permission.STT_SERVICE"
        const val INIT_TIMEOUT_MS = 3_000L
        const val STOP_GRACE_MS = 1_000L
        /** 연결을 기다리고 남은 듣기 시간이 이보다 짧으면 이 단계를 건너뛴다(다음 단계에 시간을 넘긴다) */
        const val MIN_LISTEN_MS = 1_500L
        /** 이만큼 안 쓰면 인스턴스를 놓는다(CaaS 연결 해제) — 다음 무전 녹음 중 다시 만든다 */
        const val IDLE_RELEASE_MS = 60_000L

        /** 다시 시도해도 안 될 오류 — 앱이 사는 동안 이 단계를 건너뛴다 */
        private val PERMANENT_ERRORS = setOf(
            ErrorType.NOT_SUPPORTED_API, ErrorType.UNAUTHENTICATED, ErrorType.PERMISSION_DENIED,
            ErrorType.UNAVAILABLE_LANGUAGE, ErrorType.ON_DEVICE_ENGINE_ERROR,
        )
    }
}

/**
 * Pleos STT `initialize` 시간 초과 규칙 — 순수(testPleos, B045). 한 번의 시간 초과(3 s — 확인 5 s 안에서 기다릴 수 있는 만큼)는 CaaS 콜드 스타트일 수 있어
 * 이번만 건너뛰고 다음 녹음 때 다시 시도한다. [MAX_TIMEOUTS]번 연달아면 영구 실패로 굳힌다(매 무전마다 3 s 씩 잃지 않게).
 */
internal object PleosSttInitPolicy {
    const val MAX_TIMEOUTS = 3

    /** 연달아 [consecutiveTimeouts]번 시간 초과 → 영구 실패로 굳힐까 */
    fun permanentAfter(consecutiveTimeouts: Int): Boolean = consecutiveTimeouts >= MAX_TIMEOUTS
}
