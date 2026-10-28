package app.donghaeng.car.adapter.voice

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import app.donghaeng.car.core.common.DhLog
import app.donghaeng.car.domain.model.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 체인 2순위 후보 — Pleos TextToSpeech SDK 3.3.1 **ON_DEVICE**(준비 중 SDK, CLAUDE.md 규칙 5: 가능하면 쓰고 실패하면 다음 수단으로).
 * SDK 타입은 이 파일(adapter/, pleos 소스셋) 밖으로 나가지 않는다(규칙 1) — 밖에서는 [VoiceRoute]·[UtteranceSignal] 만 본다.
 *
 * 순서(2026-10-28 에뮬레이터 실측, docs/10 R19 · javap 로 시그니처 확인):
 * 1. `pleos.car.permission.TTS_SERVICE`(normal, pleos 매니페스트) 승인 확인 — 없으면 SDK 가 SecurityException.
 * 2. Pleos CaaS(`ai.pleos.playground.caas`, SDK 최소 4.1.29 = versionCode 4001029) 설치 확인 — 없으면 불가.
 * 3. 메인 스레드에서 `TextToSpeech(context, Mode.ON_DEVICE)` → `initialize(OnInitializeListener)`. 시간 초과·재시도는 [PleosTtsInitPolicy]:
 *    15 s 안에 답이 없으면 '응답 늦음'으로 표시만 하고 **인스턴스를 그대로 두어 늦은 onSuccess 도 받는다**(깨끗한 AVD 부팅 직후 CaaS 콜드 스타트),
 *    한 시도가 45 s 를 넘기거나 onFailure·예외면 그 인스턴스를 놓고(release) 5 s → 20 s 뒤 다시(최대 3번). 그동안 상태는 초기화 중(ready=null)이라
 *    체인이 준비되는 순간 올라간다. 권한·CaaS 없음은 다시 해도 같아 바로 불가 확정.
 * 4. `onSuccess` **안에서** `addEventListener`(initialize 전이면 UninitializedPropertyAccessException) → `setAudioAttributes`(내비 안내 용도) →
 *    `setLocale(Locale.KOREA)`("ko" 만 주면 엔진이 `unsupported locale: ko-` 로 ON_DEVICE_ENGINE_ERROR) → 준비.
 * 5. `speak(text, isFinal = true)` — 두 번째 인자는 스트리밍 입력의 '마지막 조각' 표시(SDK 내부 SpeakRequest 의 `isFinal`)라 한 문장 = true.
 *    `stop()` 은 재생 중지(onStop), `release()` 는 서비스 연결 해제 — 시도를 놓을 때·불가 확정 때만 부른다(준비되면 앱 수명 동안 유지).
 * SERVER·HYBRID 모드는 Pleos 앱 키(registerApp)가 필요해 쓰지 않는다.
 *
 * 리스너가 문장 구분 없는 전역 콜백이라 [SerialUtterancePlayer] 가 한 문장씩 넘기고 시작·끝·시간 초과를 맞춘다(요청 → onStart 지연 측정 포함).
 * 오디오 포커스는 Android 후보와 같은 정책([AudioFocusGuard]). 부하가 크면 요청 → 시작 5~9 s(실측) — 위험 이벤트는 화면 강조가 먼저 뜬다(docs/05 §5.2).
 * 문장 원문은 로그에 남기지 않는다(길이만).
 */
class PleosTtsVoiceAdapter(
    context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : VoiceRoute {

    override val path: VoicePath = VoicePath.PLEOS_TTS

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val createdAt = System.currentTimeMillis()

    private val _status = MutableStateFlow(VoiceAdapterStatus(engine = ENGINE_LABEL))
    override val status: StateFlow<VoiceAdapterStatus> = _status

    private val _signals = MutableSharedFlow<UtteranceSignal>(extraBufferCapacity = 64)
    override val signals: SharedFlow<UtteranceSignal> = _signals

    @Volatile
    private var tts: TextToSpeech? = null

    /** 아래 둘은 메인 스레드에서만 — 지금까지 한 initialize 시도 수, 불가 확정(다시 시도 안 함) */
    private var attempt = 0
    private var gaveUp = false

    private val focus = object : AudioFocusPort {
        private val guard = AudioFocusGuard(appContext)
        override fun request(): Boolean = guard.request().also { g -> _status.update { it.copy(focusGranted = g) } }
        override fun abandon() { guard.abandon(); _status.update { it.copy(focusGranted = false) } }
    }

    private val player = SerialUtterancePlayer(
        path = path,
        backend = object : SerialUtterancePlayer.Backend {
            override suspend fun speak(text: String) {
                val t = tts ?: throw IllegalStateException("tts 없음")
                withContext(Dispatchers.Main) { t.speak(text, true) }
            }
            override fun stop() {
                val t = tts ?: return
                main.post { runCatching { t.stop() }.onFailure { DhLog.w("Pleos TTS stop 실패 ${it.javaClass.simpleName}") } }
            }
        },
        focus = focus,
        scope = scope,
        clock = System::currentTimeMillis,
        emit = ::onPlayerSignal,
    )

    /** SDK 전역 리스너 → 순서 맞추기 */
    private val listener = object : EventListener {
        override fun onStart() = player.onStart()
        override fun onReady() = Unit
        override fun onDone() = player.onDone()
        override fun onError(type: ErrorType) {
            DhLog.w("Pleos TTS onError $type")
            player.onError(type.name)
        }
        override fun onStop() = player.onStop()
        override fun onUpdatedRms(rms: Double) = Unit
    }

    init {
        main.post { initialize() }
    }

    private fun ms() = System.currentTimeMillis() - createdAt

    /** 초기화 중(ready=null) — 시도 중·응답 늦음·다시 시도 대기. 체인은 준비되는 순간 올라간다 */
    private fun pending(detail: String) {
        _status.update { it.copy(ready = null, detail = detail) }
    }

    /** 다시 해도 같은 불가(권한·CaaS 없음·시도 소진) — 인스턴스를 놓고 끝 */
    private fun unavailableFinal(detail: String) {
        if (gaveUp) return
        gaveUp = true
        DhLog.w("Pleos TTS 불가: $detail → 다음 경로")
        _status.update { it.copy(ready = false, detail = detail) }
        tts?.let { drop(it) }
    }

    private fun drop(t: TextToSpeech) {
        if (tts === t) tts = null
        runCatching { t.removeEventListener(listener) }
        runCatching { t.release() }
    }

    private fun initialize() {
        if (gaveUp) return
        if (appContext.checkSelfPermission(PERMISSION_TTS) != PackageManager.PERMISSION_GRANTED) {
            unavailableFinal("TTS_SERVICE 권한 없음")
            return
        }
        val caas = try {
            appContext.packageManager.getPackageInfo(CAAS_PACKAGE, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            unavailableFinal("Pleos CaaS 없음($CAAS_PACKAGE)")
            return
        } catch (e: Exception) {
            unavailableFinal("CaaS 조회 실패 ${e.javaClass.simpleName}")
            return
        }
        val caasVersion = caas.versionName ?: "?"
        if (caas.longVersionCode < CAAS_MIN_VERSION_CODE) {
            unavailableFinal("CaaS $caasVersion < 4.1.29")
            return
        }
        val n = ++attempt
        val tag = "시도 $n/${PleosTtsInitPolicy.MAX_ATTEMPTS}"
        val t = try {
            TextToSpeech(appContext, Mode.ON_DEVICE)
        } catch (e: Throwable) {
            attemptFailed(null, "생성 실패 ${e.javaClass.simpleName}")
            return
        }
        tts = t
        pending("초기화 중 (CaaS $caasVersion, $tag)")
        // 응답 늦음: 표시만 — 인스턴스·리스너는 그대로 두어 늦게 온 onSuccess 로 준비된다
        main.postDelayed({
            if (tts === t && _status.value.ready == null) {
                DhLog.w("Pleos TTS initialize 응답 늦음 ${PleosTtsInitPolicy.SLOW_MS / 1_000}s — 계속 기다림 ($tag)")
                pending("initialize 응답 늦음 ${PleosTtsInitPolicy.SLOW_MS / 1_000}s — 계속 기다림 ($tag)")
            }
        }, PleosTtsInitPolicy.SLOW_MS)
        // 한 시도의 상한: 놓고 다시
        main.postDelayed({
            if (tts === t && _status.value.ready == null) attemptFailed(t, "initialize 응답 없음 ${PleosTtsInitPolicy.GIVE_UP_MS / 1_000}s")
        }, PleosTtsInitPolicy.GIVE_UP_MS)
        try {
            t.initialize(object : OnInitializeListener {
                override fun onSuccess() { main.post { onInitialized(t, caasVersion, n) } }
                override fun onFailure(type: ErrorType) { main.post { attemptFailed(t, "initialize 실패 $type") } }
            })
        } catch (e: Throwable) {
            attemptFailed(t, "initialize 예외 ${e.javaClass.simpleName}")
        }
    }

    /** 한 시도 실패 — 그 인스턴스를 놓고, 남은 시도가 있으면 기다렸다 다시([PleosTtsInitPolicy.retryDelayMs]), 없으면 불가 확정 */
    private fun attemptFailed(t: TextToSpeech?, detail: String) {
        if (gaveUp) return
        if (t != null && tts !== t) return   // 이미 놓은 시도의 늦은 신호
        t?.let { drop(it) }
        val delay = PleosTtsInitPolicy.retryDelayMs(attempt)
        if (delay == null) {
            unavailableFinal("$detail (시도 ${attempt}회)")
            return
        }
        val tag = "$attempt/${PleosTtsInitPolicy.MAX_ATTEMPTS}"
        DhLog.w("Pleos TTS $detail → ${delay / 1_000}s 뒤 다시 시도 ($tag)")
        pending("$detail — ${delay / 1_000}s 뒤 다시 시도 ($tag)")
        main.postDelayed({ initialize() }, delay)
    }

    private fun onInitialized(t: TextToSpeech, caasVersion: String, n: Int) {
        if (gaveUp || tts !== t) return   // 놓은 시도의 늦은 onSuccess
        try {
            t.addEventListener(listener)
        } catch (e: Throwable) {
            attemptFailed(t, "addEventListener 실패 ${e.javaClass.simpleName}")
            return
        }
        runCatching { t.setAudioAttributes(AudioFocusGuard.NAV_GUIDANCE) }
            .onFailure { DhLog.w("Pleos TTS setAudioAttributes 실패 ${it.javaClass.simpleName} — 기본 용도로 읽는다") }
        try {
            t.setLocale(Locale.KOREA)
        } catch (e: Throwable) {
            attemptFailed(t, "setLocale(ko_KR) 실패 ${e.javaClass.simpleName}")
            return
        }
        _status.update { it.copy(ready = true, detail = "ON_DEVICE ko_KR 준비 (CaaS $caasVersion)") }
        DhLog.i("Pleos TTS 준비 완료 (ON_DEVICE, ko_KR, CaaS $caasVersion) ${ms()}ms" + if (n > 1) " (시도 $n)" else "")
    }

    override suspend fun speak(text: String, priority: Priority) = speak(text, priority, null)

    override suspend fun speak(text: String, priority: Priority, tag: String?) {
        if (_status.value.ready != true || tts == null) {
            _status.update { it.copy(fallbackCount = it.fallbackCount + 1) }
            _signals.tryEmit(UtteranceSignal.Failed(path, tag, System.currentTimeMillis(), "준비 안 됨"))
            return
        }
        player.enqueue(text, priority, tag)
        _status.update { it.copy(spokenCount = it.spokenCount + 1, lastUtteranceMs = System.currentTimeMillis()) }
        DhLog.d("Pleos TTS speak [$priority] (len=${text.length}) 대기=${player.pending}")
    }

    override fun stop() = player.stopAll()

    private fun onPlayerSignal(s: UtteranceSignal) {
        when (s) {
            is UtteranceSignal.Started -> DhLog.i("Pleos TTS 시작 지연=${s.latencyMs}ms")
            is UtteranceSignal.Failed -> {
                DhLog.w("Pleos TTS 실패 ${s.reason}")
                _status.update { it.copy(failedCount = it.failedCount + 1) }
            }
            is UtteranceSignal.Dropped -> DhLog.i("Pleos TTS 생략 ${s.reason}")
            else -> Unit
        }
        _signals.tryEmit(s)
    }

    companion object {
        const val ENGINE_LABEL = "Pleos TextToSpeech ON_DEVICE"
        const val PERMISSION_TTS = "pleos.car.permission.TTS_SERVICE"
        const val CAAS_PACKAGE = "ai.pleos.playground.caas"
        /** TextToSpeech 3.3.1 AAR 매니페스트 `ai.pleos.playground.caas.min_version_code` */
        const val CAAS_MIN_VERSION_CODE = 4_001_029L
    }
}

/**
 * Pleos TTS `initialize` 시간 초과·다시 시도 규칙 — 순수(JVM 테스트, docs/05 §3.5).
 * 깨끗한 Pleos AVD 를 막 부팅해 부하가 크면 CaaS 바인딩·ON_DEVICE 엔진 적재가 15 s 를 넘길 수 있다(리뷰 2026-10-28) — 그때 영구 불가로
 * 굳으면 심사 환경에서 소리가 안 난다. 그래서 늦음은 표시만, 놓는 것은 한 시도 상한 뒤, 다시 시도는 두 번까지.
 */
internal object PleosTtsInitPolicy {
    /** 이 안에 onSuccess 가 없으면 '응답 늦음' 표시만(인스턴스 유지 — 늦은 onSuccess 로 준비) */
    const val SLOW_MS = 15_000L
    /** 한 시도의 상한 — 넘기면 그 인스턴스를 놓고(release) 다시 시도 */
    const val GIVE_UP_MS = 45_000L
    const val MAX_ATTEMPTS = 3
    private val BACKOFF_MS = longArrayOf(5_000L, 20_000L)

    /** [failedAttempt](1부터)번째 시도가 실패했을 때 다음 시도까지 기다릴 시간. null = 더 하지 않음(불가 확정) */
    fun retryDelayMs(failedAttempt: Int): Long? =
        if (failedAttempt >= MAX_ATTEMPTS) null else BACKOFF_MS[(failedAttempt - 1).coerceIn(0, BACKOFF_MS.size - 1)]
}
