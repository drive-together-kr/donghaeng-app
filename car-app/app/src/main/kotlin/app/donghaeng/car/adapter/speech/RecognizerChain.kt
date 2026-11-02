package app.donghaeng.car.adapter.speech

import app.donghaeng.car.core.common.DhLog
import app.donghaeng.car.domain.ptt.YesNo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * '네/아니' 인식 체인 (F16, docs/05 § 무전) — [stages] 순서대로 시도한다(DI: Pleos STT(pleos flavor, 선택적) → Android SpeechRecognizer → Vosk).
 *
 * - 쓸 수 없는 단계(서비스·모델·권한 없음 — [YesNoRecognizer.unavailableReason])는 건너뛴다.
 * - 듣다가 실패하면([ListenResult.Failed]) **남은 시간으로** 다음 단계를 시도한다. `permanent` 실패(SpeechRecognizer error 9 등)는 앱이 사는 동안 다시 쓰지 않는다.
 * - 한 단계가 [YesNo.NONE](말 없음·네/아니 아님)을 주고 시간이 [relistenMinMs] 이상 남으면 같은 단계로 다시 듣는다(최대 [maxAttemptsPerStage]번).
 * - 들을 수 있는 단계가 하나도 없거나 모두 실패하면 [ChainOutcome.answer] = null → PttController 가 **카운트다운 모드**로 바꾼다.
 * - 디버그 주입: [injectAnswer](판정 직접 — 듣는 중에 와도 바로 끝낸다), [injectWav](다음 한 번은 마이크 대신 그 WAV 를 WAV 를 받을 수 있는 첫 단계(Vosk)에 흘린다).
 * - 로그는 `ptt: recognizer=VOSK answer=YES conf=0.91 (ANDROID_STT=failed(stt error=9))` 처럼 판정·근거만(인식 원문 없음, 규칙 8).
 */
class RecognizerChain(
    private val stages: List<YesNoRecognizer>,
    private val clock: () -> Long,
    private val minStageMs: Long = MIN_STAGE_MS,
    private val relistenMinMs: Long = RELISTEN_MIN_MS,
    private val maxAttemptsPerStage: Int = MAX_ATTEMPTS_PER_STAGE,
) : YesNoListener {

    private val sticky = ConcurrentHashMap<RecognizerKind, String>()
    private val injectedAnswer = MutableStateFlow<YesNo?>(null)
    @Volatile private var injectedWav: File? = null
    @Volatile var lastOutcome: ChainOutcome? = null
        private set

    /** 디버그 `ptt_answer --es answer yes|no|none` — 듣는 중이면 그 듣기를 바로 끝내고, 아니면 다음 듣기의 답이 된다 */
    fun injectAnswer(answer: YesNo) {
        injectedAnswer.value = answer
        DhLog.i("ptt: answer injection armed answer=$answer")
    }

    /** 디버그 `ptt_answer --es file <wav>` — 다음 듣기 한 번은 마이크 대신 이 WAV(16 kHz 모노 16 bit) */
    fun injectWav(file: File) {
        injectedWav = file
        DhLog.i("ptt: answer wav injection armed bytes=${file.length()}")
    }

    val hasPendingInjection: Boolean get() = injectedAnswer.value != null || injectedWav != null

    /**
     * 디버그·시연 `ptt_countdown --ez on true` — 실제 인식기를 모두 건너뛰어 **카운트다운 모드**("5초 뒤 보낼게요")를 보인다.
     * 주입한 답·WAV 는 그대로 받는다. 기기에 Vosk 모델이 있어도 카운트다운 장면을 찍을 수 있게(기본 false).
     */
    @Volatile
    var forceCountdown: Boolean = false
        set(value) {
            field = value
            DhLog.i("ptt: recognizers forced off=$value (카운트다운 모드)")
        }

    override fun hasCandidate(): Boolean = hasPendingInjection || (!forceCountdown && stages.any { usable(it) })

    private fun usable(s: YesNoRecognizer): Boolean = !sticky.containsKey(s.kind) && safeReason(s) == null

    private fun safeReason(s: YesNoRecognizer): String? = try {
        s.unavailableReason()
    } catch (e: Exception) {
        "check-${e.javaClass.simpleName}"
    }

    /**
     * 단계별 준비를 **나란히** 한다(B064) — 차례로 하면 Pleos STT 초기화(최대 3 s)가 끝나야 Vosk 모델 적재(수 초)가 시작돼, 첫 무전의 확인 단계에서
     * Vosk 가 아직 적재 중일 수 있다. 실패는 단계마다 로그만.
     */
    override suspend fun prepare() {
        val targets = stages.filter { usable(it) || (injectedWav != null && it.wavUnavailableReason() == null) }
        coroutineScope {
            targets.forEach { s ->
                launch {
                    try {
                        s.prepare()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        DhLog.w("ptt: recognizer ${s.kind} prepare 실패 ${e.javaClass.simpleName}")
                    }
                }
            }
        }
    }

    override suspend fun listen(timeoutMs: Long): ChainOutcome {
        takeInjected()?.let { return finish(it) }
        val deadline = clock() + timeoutMs
        // 듣는 중에 주입된 답이 오면 그쪽이 먼저 — first() 가 나머지(마이크 듣기)를 취소한다
        val outcome = merge(
            flow { emit(runStages(deadline)) },
            injectedAnswer.filterNotNull().map { takeInjected() ?: ChainOutcome(it, RecognizerKind.INJECTED) },
        ).first()
        return finish(outcome)
    }

    override suspend fun awaitInjected(): YesNo {
        while (true) {
            val a = injectedAnswer.filterNotNull().first()
            if (injectedAnswer.compareAndSet(a, null)) {
                DhLog.i("ptt: recognizer=INJECTED answer=$a (countdown)")
                return a
            }
        }
    }

    private fun takeInjected(): ChainOutcome? {
        val a = injectedAnswer.value ?: return null
        return if (injectedAnswer.compareAndSet(a, null)) ChainOutcome(a, RecognizerKind.INJECTED, trail = listOf("injected")) else null
    }

    private fun finish(o: ChainOutcome): ChainOutcome {
        lastOutcome = o
        val trail = if (o.trail.isEmpty()) "" else " (${o.trail.joinToString(", ")})"
        if (o.answer == null) DhLog.i("ptt: recognizer=NONE → countdown$trail")
        else DhLog.i("ptt: recognizer=${o.kind} answer=${o.answer}${o.confidence?.let { String.format(Locale.ROOT, " conf=%.2f", it) } ?: ""}$trail")
        return o
    }

    private suspend fun runStages(deadline: Long): ChainOutcome {
        val trail = mutableListOf<String>()
        val wav = injectedWav
        injectedWav = null
        if (wav != null) {
            val s = stages.firstOrNull { !sticky.containsKey(it.kind) && it.wavUnavailableReason() == null }
            if (s == null) {
                trail += "wav=no-recognizer"
                DhLog.w("ptt: wav 주입을 받을 인식기가 없다(Vosk 모델 필요) → 마이크로 듣는다")
            } else {
                when (val r = attempt(s, deadline) { remaining -> s.listenWav(wav, remaining) }) {
                    is ListenResult.Heard -> return ChainOutcome(r.answer, s.kind, r.confidence, trail + "wav")
                    is ListenResult.Failed -> note(s, r, trail)
                }
            }
        }
        if (forceCountdown) {
            trail += "forced-countdown"
            return ChainOutcome(null, trail = trail)
        }
        for (s in stages) {
            val failed = sticky[s.kind]
            if (failed != null) {
                trail += "${s.kind}=failed($failed)"
                continue
            }
            val unavailable = safeReason(s)
            if (unavailable != null) {
                trail += "${s.kind}=unavailable($unavailable)"
                continue
            }
            var attempts = 0
            while (true) {
                val remaining = deadline - clock()
                if (remaining < minStageMs) {
                    trail += "${s.kind}=no-time"
                    return ChainOutcome(null, trail = trail)
                }
                attempts++
                when (val r = attempt(s, deadline) { s.listen(it) }) {
                    is ListenResult.Heard -> {
                        val left = deadline - clock()
                        if (r.answer == YesNo.NONE && left >= relistenMinMs && attempts < maxAttemptsPerStage) continue
                        return ChainOutcome(r.answer, s.kind, r.confidence, trail)
                    }
                    is ListenResult.Failed -> {
                        note(s, r, trail)
                        break
                    }
                }
            }
        }
        return ChainOutcome(null, trail = trail)
    }

    private suspend fun attempt(s: YesNoRecognizer, deadline: Long, block: suspend (Long) -> ListenResult): ListenResult = try {
        block((deadline - clock()).coerceAtLeast(0))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ListenResult.Failed("exception-${e.javaClass.simpleName}", permanent = false)
    }

    private fun note(s: YesNoRecognizer, r: ListenResult.Failed, trail: MutableList<String>) {
        if (r.permanent) sticky[s.kind] = r.reason
        trail += "${s.kind}=failed(${r.reason})"
        DhLog.i("ptt: recognizer ${s.kind} failed reason=${r.reason} permanent=${r.permanent}")
    }

    override fun describe(): String = stages.joinToString(" ") { s ->
        val state = sticky[s.kind]?.let { "failed($it)" } ?: safeReason(s)?.let { "unavailable($it)" } ?: "ready"
        "${s.kind}=$state"
    } + (if (hasPendingInjection) " injected=pending" else "") + (if (forceCountdown) " forcedCountdown" else "")

    companion object {
        /** 남은 시간이 이보다 짧으면 다음 단계를 시작하지 않는다 */
        const val MIN_STAGE_MS = 400L
        /** NONE 뒤 같은 단계로 다시 듣는 최소 남은 시간 */
        const val RELISTEN_MIN_MS = 1_500L
        const val MAX_ATTEMPTS_PER_STAGE = 3
    }
}
