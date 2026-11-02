package app.donghaeng.car.adapter.voice

import app.donghaeng.car.core.common.DhLog
import app.donghaeng.car.domain.model.Priority
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.roundToLong

/** 요청 → 시작 지연의 최근 [capacity]건 창 — 마지막·평균·최대 */
class VoiceLatencyWindow(private val capacity: Int = CAPACITY) {
    private val samples = ArrayDeque<Long>()

    @Synchronized
    fun add(ms: Long) {
        samples.addLast(ms.coerceAtLeast(0))
        while (samples.size > capacity) samples.removeFirst()
    }

    @Synchronized
    fun stats(): VoiceLatencyStats =
        if (samples.isEmpty()) VoiceLatencyStats()
        else VoiceLatencyStats(lastMs = samples.last(), avgMs = samples.average().roundToLong(), maxMs = samples.max(), count = samples.size)

    companion object {
        const val CAPACITY = 10
    }
}

/**
 * 체인 경로 선택 — 순수 함수(docs/05 §3.5).
 *
 * 우선순위대로 보며 **처음 만나는 준비된(ready=true) 후보**가 지금 경로다. 강등된 후보·불가(ready=false) 후보는 건너뛴다.
 * 더 높은 순위 후보가 아직 초기화 중(ready=null)이고 선택 상한([selectTimeoutMs], 30 s) 안이면 `selecting=true` — 그동안 온 발화는
 * 그때 준비된 가장 높은 순위로(없으면 화면만) 나가고, 위 후보가 나중에 준비되면 그쪽으로 올라간다(상한을 넘긴 뒤에 준비돼도 올라간다).
 * 아무것도 준비되지 않으면 [VoicePath.DISPLAY_ONLY].
 */
object VoiceChainPolicy {
    data class Candidate(val path: VoicePath, val ready: Boolean?, val demoted: Boolean = false, val detail: String? = null)
    data class Choice(val path: VoicePath, val selecting: Boolean, val reason: String)

    fun choose(candidates: List<Candidate>, elapsedMs: Long, selectTimeoutMs: Long): Choice {
        val notes = mutableListOf<String>()
        var pending = false
        for (c in candidates) {
            when {
                c.demoted -> notes += "${c.path} 강등(연속 실패)"
                c.ready == true -> return Choice(c.path, pending, reason(notes, pending))
                c.ready == false -> notes += "${c.path} 불가(${c.detail?.takeIf { it.isNotBlank() } ?: "?"})"
                elapsedMs < selectTimeoutMs -> { pending = true; notes += "${c.path} 초기화 중" }
                else -> notes += "${c.path} 초기화 ${selectTimeoutMs / 1_000}s 넘음"
            }
        }
        return Choice(VoicePath.DISPLAY_ONLY, pending, if (candidates.isEmpty()) "소리 후보 없음" else reason(notes, pending))
    }

    private fun reason(notes: List<String>, pending: Boolean): String =
        (if (notes.isEmpty()) "1순위 준비" else notes.joinToString(" · ")) + if (pending) " (선택 중)" else ""
}

/**
 * 음성 체인 (R19 해결, CLAUDE.md 규칙 5 — 준비 중 SDK 는 "가능하면 쓰고, 실패하면 다음 수단으로 자동 전환").
 *
 * 후보 [routes] = 우선순위 순(앱: Android TTS → Pleos TTS(pleos flavor 만) — DI 가 주입) + 끝에 암묵적으로 [VoicePath.DISPLAY_ONLY].
 * - 후보는 생성과 동시에 **나란히** 초기화한다(Android 엔진 적재가 20 s 넘게 걸려도 그동안 Pleos 로 읽게).
 * - 경로 선택은 [VoiceChainPolicy.choose] — 후보 상태가 바뀔 때·실패 신호가 올 때·선택 상한([selectTimeoutMs])이 될 때 다시 본다.
 * - 지금 경로가 **연속 [demoteAfter]번 실패**([UtteranceSignal.Failed])하면 그 후보를 강등(프로세스 수명 동안)하고 다음 경로로 내린다. 시작·완료 신호가 오면 0 으로.
 * - 실패한 그 문장을 다음 경로로 다시 읽지는 않는다(늦게 소리가 나 두 번 들리는 것을 피한다) — 위험 이벤트는 화면 강조가 받친다(docs/05 §5.2).
 * - **경로가 바뀌면(승격·강등) 이전 경로를 [VoiceRoute.stop]** — 대기·재생 중이던 문장은 버린다(Dropped, 실패 아님). 그대로 두면
 *   선택 중 Pleos 큐에 있던 문장이 5~9 s 뒤에 시작해 새 경로(Android)의 다음 문장과 두 목소리가 겹친다(리뷰 2026-10-28). 손실은 앱 시작 직후
 *   한 번 정도이고 소식 줄(10초)이 받친다.
 * - 상태 `ready`: true = 소리 경로 있음, false = 화면 표시만으로 정해짐, null = 소리 경로가 아직 없고 **초기화 중인 후보가 있음**
 *   (선택 상한 안이거나, 상한 뒤에도 Pleos 처럼 늦게·다시 시도로 준비될 수 있는 후보 — 준비되면 올라간다).
 * - 로그: 경로나 '선택 중' 여부가 바뀔 때 한 줄 `voice: 경로 = ANDROID_TTS(engine=…)|PLEOS_TTS|DISPLAY_ONLY 사유=…`.
 * - 지연: 후보가 보내는 [UtteranceSignal.Started] 의 요청 → 시작을 경로별 최근 10건으로 모은다. [status] 의 `latency` = 지금 경로.
 * - 문장 원문은 어떤 로그에도 남기지 않는다(길이만, 규칙 8).
 */
class ChainedVoiceAdapter(
    private val routes: List<VoiceRoute>,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val selectTimeoutMs: Long = SELECT_TIMEOUT_MS,
    private val demoteAfter: Int = DEMOTE_AFTER_FAILURES,
) : VoiceAdapter {

    private val lock = Any()
    private val startedAtMs = clock()
    private val demoted = mutableSetOf<VoicePath>()
    private val failures = mutableMapOf<VoicePath, Int>()
    private val latency: Map<VoicePath, VoiceLatencyWindow> = VoicePath.entries.associateWith { VoiceLatencyWindow() }
    private var spoken = 0
    private var fallback = 0
    private var failed = 0
    private var lastUtteranceMs: Long? = null
    private var choice: VoiceChainPolicy.Choice = VoiceChainPolicy.Choice(VoicePath.DISPLAY_ONLY, selecting = true, reason = "시작 전")
    private var logged: Pair<VoicePath, Boolean>? = null

    private val _signals = MutableSharedFlow<UtteranceSignal>(extraBufferCapacity = 64)
    override val signals: SharedFlow<UtteranceSignal> = _signals

    private val _status = MutableStateFlow(VoiceAdapterStatus())
    override val status: StateFlow<VoiceAdapterStatus> = _status

    /** 지금 경로(테스트·진단) */
    val currentPath: VoicePath get() = synchronized(lock) { choice.path }

    init {
        refresh()
        if (routes.isNotEmpty()) scope.launch { combine(routes.map { it.status }) { it.toList() }.collect { refresh() } }
        routes.forEach { r -> scope.launch { r.signals.collect { onSignal(it) } } }
        // 선택 상한: 위 후보가 그때까지 초기화 중이면 지금 준비된 것으로 확정(로그 한 줄)
        scope.launch { delay(selectTimeoutMs); refresh() }
    }

    override suspend fun speak(text: String, priority: Priority) = speak(text, priority, null)

    override suspend fun speak(text: String, priority: Priority, tag: String?) {
        val path = synchronized(lock) { lastUtteranceMs = clock(); choice.path }
        val route = routes.firstOrNull { it.path == path }
        if (route == null) {
            // 화면 표시만 — 소리 경로 없음. 위험 이벤트 강조는 NoAudio 를 보고 바로 켠다
            synchronized(lock) { fallback++ }
            DhLog.i("TTS 폴백(화면 표시만) [$priority] (len=${text.length})")
            _signals.tryEmit(UtteranceSignal.NoAudio(tag, clock()))
            refresh()
            return
        }
        // 급한 안내(에어백)는 경로가 막 바뀌어 다른 후보가 아직 읽고 있어도 겹치지 않게 그쪽을 먼저 멈춘다
        if (priority == Priority.CRITICAL) routes.filter { it !== route }.forEach { r -> runCatching { r.stop() } }
        try {
            route.speak(text, priority, tag)
            synchronized(lock) { spoken++ }
            refresh()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onSignal(UtteranceSignal.Failed(route.path, tag, clock(), "speak 예외 ${e.javaClass.simpleName}"))
        }
    }

    private val waitSeq = AtomicLong(0)

    /**
     * F16(무전 "보낼까요?" 뒤에만 마이크, 수신 무전은 "○○ 무전" 알림 뒤 재생): 지금 경로로 읽고 그 문장이 끝날 때까지 기다린다 — 경로와 무관하게
     * [signals] 로 판정한다(전용 꼬리표 `wait-N`; 이벤트 eventId 가 아니라 위험 강조와 섞이지 않는다). Done → SPOKEN, 화면만(NoAudio) → FALLBACK,
     * Failed·Dropped → FAILED, 상한 → TIMEOUT. 신호를 놓치지 않게 구독을 먼저 시작하고(UNDISPATCHED) 읽는다.
     * **상한을 넘기거나 기다리던 쪽이 취소되면 그 문장을 버린다**([cancel], B012) — 늦게 나온 "보낼까요?"·"○○ 무전"이 열린 마이크·녹음 재생과
     * 겹치지 않게. 다른 문장(급제동 HIGH 등)은 그대로 둔다. 로그 `voice: wait result=… len=… path=… ms=…`(문장 원문 없음).
     */
    override suspend fun speakAndWait(text: String, priority: Priority, timeoutMs: Long): SpeakResult {
        val tag = "wait-${waitSeq.incrementAndGet()}"
        val t0 = clock()
        var ended = false
        try {
            val result = coroutineScope {
                val end = async(start = CoroutineStart.UNDISPATCHED) {
                    _signals.first { it.tag == tag && it !is UtteranceSignal.Started }
                }
                speak(text, priority, tag)
                val sig = withTimeoutOrNull(timeoutMs) { end.await() }
                if (sig == null) end.cancel() else ended = true
                when (sig) {
                    is UtteranceSignal.Done -> SpeakResult.SPOKEN
                    is UtteranceSignal.NoAudio -> SpeakResult.FALLBACK
                    is UtteranceSignal.Failed, is UtteranceSignal.Dropped -> SpeakResult.FAILED
                    null -> SpeakResult.TIMEOUT
                    else -> SpeakResult.UNKNOWN
                }
            }
            DhLog.i("voice: wait result=$result len=${text.length} path=${currentPath} ms=${clock() - t0}")
            return result
        } finally {
            // 끝 신호를 못 받았다(상한·취소) — 그 문장만 버린다(늦게 나와 마이크·재생과 겹치지 않게)
            if (!ended) cancel(tag)
        }
    }

    override fun stop() {
        routes.forEach { r -> runCatching { r.stop() } }
    }

    /** 꼬리표 하나만 버린다 — 모든 후보에 묻는다(그 꼬리표를 가진 후보만 반응한다). 신호는 후보가 낸다(Dropped "cancelled"·"interrupted") */
    override fun cancel(tag: String) {
        routes.forEach { r -> runCatching { r.cancel(tag) } }
    }

    /** 체인은 꼬리표 붙인 문장마다 끝 신호를 낸다 — 화면만(NoAudio)·speak 예외(Failed)·후보 신호(Done·Failed·Dropped) */
    override val reportsEnd: Boolean get() = true

    private fun onSignal(s: UtteranceSignal) {
        synchronized(lock) {
            when (s) {
                is UtteranceSignal.Started -> { latency.getValue(s.path).add(s.latencyMs); failures[s.path] = 0 }
                is UtteranceSignal.Done -> failures[s.path] = 0
                is UtteranceSignal.Failed -> {
                    failed++
                    val n = (failures[s.path] ?: 0) + 1
                    failures[s.path] = n
                    if (n >= demoteAfter && s.path != VoicePath.DISPLAY_ONLY && demoted.add(s.path)) {
                        DhLog.w("voice: ${s.path} 연속 실패 ${n}회(마지막 ${s.reason}) → 강등, 다음 경로로")
                    }
                }
                is UtteranceSignal.Dropped, is UtteranceSignal.NoAudio -> Unit
            }
        }
        _signals.tryEmit(s)
        refresh()
    }

    /** 경로 다시 고르기 + 상태 갱신 + (바뀌었으면) 로그 한 줄·이전 경로 멈춤 */
    private fun refresh() {
        var retired: VoiceRoute? = null
        synchronized(lock) {
            val statuses = routes.map { it.status.value }
            val c = VoiceChainPolicy.choose(
                routes.mapIndexed { i, r -> VoiceChainPolicy.Candidate(r.path, statuses[i].ready, r.path in demoted, statuses[i].detail) },
                elapsedMs = clock() - startedAtMs,
                selectTimeoutMs = selectTimeoutMs,
            )
            if (c.path != choice.path) retired = routes.firstOrNull { it.path == choice.path }
            choice = c
            val activeIndex = routes.indexOfFirst { it.path == c.path }
            val active = statuses.getOrNull(activeIndex)
            if (logged != (c.path to c.selecting)) {
                logged = c.path to c.selecting
                DhLog.i("voice: 경로 = ${pathLabel(c.path, active?.engine)} 사유=${c.reason}")
            }
            _status.value = VoiceAdapterStatus(
                engine = active?.engine,
                ready = when {
                    c.path != VoicePath.DISPLAY_ONLY -> true
                    c.selecting || routes.indices.any { statuses[it].ready == null && routes[it].path !in demoted } -> null
                    else -> false
                },
                detail = active?.detail ?: c.reason,
                spokenCount = spoken,
                fallbackCount = fallback,
                failedCount = failed,
                focusGranted = active?.focusGranted ?: false,
                lastUtteranceMs = lastUtteranceMs,
                path = c.path,
                pathReason = c.reason,
                selecting = c.selecting,
                latency = latency.getValue(c.path).stats(),
                candidates = routes.mapIndexed { i, r ->
                    VoiceCandidateStatus(
                        path = r.path, ready = statuses[i].ready, engine = statuses[i].engine, detail = statuses[i].detail,
                        demoted = r.path in demoted, consecutiveFailures = failures[r.path] ?: 0, latency = latency.getValue(r.path).stats(),
                    )
                },
            )
        }
        // 이전 경로의 대기·재생 문장을 멈춘다(두 목소리 겹침 방지) — 잠금 밖에서(후보 stop 이 신호를 낸다)
        retired?.let { r ->
            DhLog.i("voice: 이전 경로 ${r.path} 멈춤(경로 바뀜 — 대기·재생 문장 버림)")
            runCatching { r.stop() }
        }
    }

    companion object {
        /** 위 순위 후보 초기화를 기다리는 상한(그 뒤에 준비돼도 올라간다) */
        const val SELECT_TIMEOUT_MS = 30_000L
        /** 지금 경로를 내리는 연속 실패 수 */
        const val DEMOTE_AFTER_FAILURES = 3

        /** 로그용 경로 표기 — Android 는 엔진 패키지까지(사람 이름·좌표 없음) */
        fun pathLabel(path: VoicePath, engine: String?): String =
            if (path == VoicePath.ANDROID_TTS) "ANDROID_TTS(engine=${engine ?: "?"})" else path.name

        /** 앱 체인 — 후보를 생성 순서(=우선순위)대로, 자체 스코프에서 */
        fun create(routes: List<VoiceRoute>): ChainedVoiceAdapter =
            ChainedVoiceAdapter(routes, CoroutineScope(SupervisorJob() + Dispatchers.Default))
    }
}
