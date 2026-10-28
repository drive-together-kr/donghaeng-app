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
 * 3. 메인 스레드에서 `TextToSpeech(context, Mode.ON_DEVICE)` → `initialize(OnInitializeListener)` (응답 상한 [INIT_TIMEOUT_MS]).
 * 4. `onSuccess` **안에서** `addEventListener`(initialize 전이면 UninitializedPropertyAccessException) → `setAudioAttributes`(내비 안내 용도) →
 *    `setLocale(Locale.KOREA)`("ko" 만 주면 엔진이 `unsupported locale: ko-` 로 ON_DEVICE_ENGINE_ERROR) → 준비.
 * 5. `speak(text, isFinal = true)` — 두 번째 인자는 스트리밍 입력의 '마지막 조각' 표시(SDK 내부 SpeakRequest 의 `isFinal`)라 한 문장 = true.
 *    `stop()` 은 재생 중지(onStop), `release()` 는 서비스 연결 해제 — 불가로 끝날 때만 부른다(앱 수명 동안 유지).
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

    private fun unavailable(detail: String) {
        if (_status.value.ready == false) return
        DhLog.w("Pleos TTS 불가: $detail → 다음 경로")
        _status.update { it.copy(ready = false, detail = detail) }
        val t = tts ?: return
        tts = null
        runCatching { t.removeEventListener(listener) }
        runCatching { t.release() }
    }

    private fun initialize() {
        if (appContext.checkSelfPermission(PERMISSION_TTS) != PackageManager.PERMISSION_GRANTED) {
            unavailable("TTS_SERVICE 권한 없음")
            return
        }
        val caas = try {
            appContext.packageManager.getPackageInfo(CAAS_PACKAGE, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            unavailable("Pleos CaaS 없음($CAAS_PACKAGE)")
            return
        } catch (e: Exception) {
            unavailable("CaaS 조회 실패 ${e.javaClass.simpleName}")
            return
        }
        val caasVersion = caas.versionName ?: "?"
        if (caas.longVersionCode < CAAS_MIN_VERSION_CODE) {
            unavailable("CaaS $caasVersion < 4.1.29")
            return
        }
        val t = try {
            TextToSpeech(appContext, Mode.ON_DEVICE)
        } catch (e: Throwable) {
            unavailable("생성 실패 ${e.javaClass.simpleName}")
            return
        }
        tts = t
        _status.update { it.copy(detail = "초기화 중 (CaaS $caasVersion)") }
        main.postDelayed({ if (_status.value.ready == null) unavailable("initialize 응답 없음 ${INIT_TIMEOUT_MS / 1_000}s") }, INIT_TIMEOUT_MS)
        try {
            t.initialize(object : OnInitializeListener {
                override fun onSuccess() { main.post { onInitialized(t, caasVersion) } }
                override fun onFailure(type: ErrorType) { main.post { unavailable("initialize 실패 $type") } }
            })
        } catch (e: Throwable) {
            unavailable("initialize 예외 ${e.javaClass.simpleName}")
        }
    }

    private fun onInitialized(t: TextToSpeech, caasVersion: String) {
        if (tts !== t || _status.value.ready == false) return   // 시간 초과로 이미 불가
        try {
            t.addEventListener(listener)
        } catch (e: Throwable) {
            unavailable("addEventListener 실패 ${e.javaClass.simpleName}")
            return
        }
        runCatching { t.setAudioAttributes(AudioFocusGuard.NAV_GUIDANCE) }
            .onFailure { DhLog.w("Pleos TTS setAudioAttributes 실패 ${it.javaClass.simpleName} — 기본 용도로 읽는다") }
        try {
            t.setLocale(Locale.KOREA)
        } catch (e: Throwable) {
            unavailable("setLocale(ko_KR) 실패 ${e.javaClass.simpleName}")
            return
        }
        _status.update { it.copy(ready = true, detail = "ON_DEVICE ko_KR 준비 (CaaS $caasVersion)") }
        DhLog.i("Pleos TTS 준비 완료 (ON_DEVICE, ko_KR, CaaS $caasVersion) ${ms()}ms")
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
        const val INIT_TIMEOUT_MS = 15_000L
    }
}
