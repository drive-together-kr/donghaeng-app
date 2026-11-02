package app.donghaeng.car.adapter.voice

import app.donghaeng.car.domain.model.Priority
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 발화 id 가 없는 콜백형 TTS 엔진을 **한 문장씩 차례로** 돌리는 순수 로직(SDK 타입 없음 — JVM 단위 테스트 대상).
 * Pleos TextToSpeech SDK 는 리스너가 전역(`onStart/onDone/onError/onStop`, 문장 구분 없음)이라 여러 문장을 한꺼번에 넘기면 어느 신호가
 * 어느 문장 것인지 알 수 없다 → 앞 문장이 끝나야(또는 시간 초과) 다음 문장을 넘긴다.
 *
 * - 요청 시각 = [enqueue] 호출 시각. 시작 신호가 오면 [UtteranceSignal.Started](지연 = 요청 → 시작, 큐 대기 포함 — 사용자가 느끼는 지연).
 * - 시작 신호가 [startTimeoutMs](15 s — 실측 부하 시 5~9 s) 안에 없으면 [UtteranceSignal.Failed]("시작 시간 초과") + 엔진 stop.
 * - 시작 뒤 끝 신호가 [doneTimeoutMs] 안에 없으면 끝난 것으로 본다(Done) — 다음 문장이 막히지 않게.
 * - [Priority.CRITICAL] 은 큐를 비우고(Dropped "flushed") 재생 중인 문장을 끊는다(엔진 stop → 정지 신호를 최대 [stopSettleMs] 기다린 뒤 읽는다).
 * - 큐는 **우선순위 → 도착순**(B011): HIGH 는 대기 중인 NORMAL·LOW 앞(같은 HIGH 들 뒤)에 선다 — 급제동이 먼저 넘겨진 LOW 뒤에 밀리지 않게.
 *   재생 중인 문장은 HIGH 가 끊지 않는다(끊는 것은 CRITICAL 뿐).
 * - [cancel]: 꼬리표 하나만 버린다 — 큐에 있으면 빼고(Dropped "cancelled"), 읽는(또는 시작을 기다리는) 중이면 끊는다(Dropped "interrupted").
 *   PTT 안내 시간 초과·녹음 시작(B012·B013).
 * - 끊기 신호는 **그 문장을 겨냥한다**(Interrupt(target)) — 문장이 막 끝난 뒤 늦게 도착한 끊기 신호가 다음 문장을 버리지 않게.
 * - 큐에서 [maxQueueWaitMs](30 s) 넘게 기다린 문장은 읽지 않는다(Dropped — VoiceAnnouncer 의 큐 대기 상한과 같다).
 * - 오디오 포커스: 첫 문장 전에 요청, 큐가 비면 반납([AudioFocusPort]).
 */
class SerialUtterancePlayer(
    private val path: VoicePath,
    private val backend: Backend,
    private val focus: AudioFocusPort,
    scope: CoroutineScope,
    private val clock: () -> Long,
    private val emit: (UtteranceSignal) -> Unit,
    private val startTimeoutMs: Long = START_TIMEOUT_MS,
    private val doneTimeoutMs: (String) -> Long = { DONE_BASE_MS + it.length * DONE_PER_CHAR_MS },
    private val maxQueueWaitMs: Long = MAX_QUEUE_WAIT_MS,
    private val stopSettleMs: Long = STOP_SETTLE_MS,
) {
    /** 엔진 한 개 — 문장 넘기기·멈추기. speak 가 던지면 그 문장은 실패 */
    interface Backend {
        suspend fun speak(text: String)
        fun stop()
    }

    /** 문장 한 개 — 같은 내용이어도 요청마다 다른 객체(끊기 신호가 객체를 겨냥한다) */
    private class Request(val text: String, val priority: Priority, val tag: String?, val requestedAt: Long) {
        /** 끊기 신호를 이미 보냈다(같은 문장에 두 번 보내지 않게) — [lock] 안에서만 */
        var interruptSent = false
    }

    private sealed interface Msg {
        data object Start : Msg
        data object Done : Msg
        data class Error(val reason: String) : Msg
        data object Stop : Msg
        /** [target] 문장을 끊는다(엔진 stop 을 이미 불렀다) — 더 급한 문장·전부 멈춤·꼬리표 취소 */
        class Interrupt(val target: Request) : Msg
    }

    private val lock = Any()
    private val queue = ArrayDeque<Request>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val inbox = Channel<Msg>(Channel.UNLIMITED)
    private var current: Request? = null
    private var focusHeld = false

    init {
        scope.launch { run() }
    }

    /** 대기 + 재생 중 문장 수 */
    val pending: Int get() = synchronized(lock) { queue.size + if (current != null) 1 else 0 }

    fun enqueue(text: String, priority: Priority, tag: String?) {
        val r = Request(text, priority, tag, clock())
        var interrupt: Request? = null
        synchronized(lock) {
            if (priority == Priority.CRITICAL) {
                queue.forEach { emit(UtteranceSignal.Dropped(path, it.tag, clock(), "flushed")) }
                queue.clear()
                queue.addFirst(r)
                interrupt = markInterrupt(current)
            } else {
                // 우선순위 → 도착순: 자기보다 낮은 순위의 첫 문장 앞에 선다(없으면 맨 뒤)
                val at = queue.indexOfFirst { it.priority < priority }
                if (at < 0) queue.addLast(r) else queue.add(at, r)
            }
        }
        interrupt?.let { sendInterrupt(it) }
        wake.trySend(Unit)
    }

    /**
     * [tag] 문장 하나만 버린다 — 큐에 있으면 빼고(Dropped "cancelled"), 지금 읽는(또는 시작을 기다리는) 문장이면 끊는다(엔진 stop → Dropped "interrupted").
     * 이미 끝났거나 모르는 꼬리표면 아무것도 안 한다. 끊은 뒤 다음 문장은 정지 신호를 잠깐 기다렸다가 넘긴다(늦은 stop 에 끊기지 않게).
     */
    fun cancel(tag: String) {
        var interrupt: Request? = null
        synchronized(lock) {
            val removed = queue.filter { it.tag == tag }
            if (removed.isNotEmpty()) {
                queue.removeAll { it.tag == tag }
                removed.forEach { emit(UtteranceSignal.Dropped(path, it.tag, clock(), "cancelled")) }
            }
            if (current?.tag == tag) interrupt = markInterrupt(current)
        }
        interrupt?.let { sendInterrupt(it) }
    }

    /** 엔진 리스너 → 여기로(어느 스레드든) */
    fun onStart() { inbox.trySend(Msg.Start) }
    fun onDone() { inbox.trySend(Msg.Done) }
    fun onError(reason: String) { inbox.trySend(Msg.Error(reason)) }
    fun onStop() { inbox.trySend(Msg.Stop) }

    /**
     * 전부 멈춤(음성 끄기·체인 경로 바뀜) — 대기 문장은 Dropped("stopped"), 재생 중 문장은 Dropped("interrupted").
     * 넘긴 문장이 없으면 엔진은 건드리지 않는다(빈 stop 의 늦은 정지 신호가 다음 문장을 끊지 않게).
     */
    fun stopAll() {
        var interrupt: Request? = null
        synchronized(lock) {
            queue.forEach { emit(UtteranceSignal.Dropped(path, it.tag, clock(), "stopped")) }
            queue.clear()
            interrupt = markInterrupt(current)
        }
        interrupt?.let { sendInterrupt(it) }
    }

    /** [lock] 안에서 — 아직 끊기 신호를 보내지 않은 문장이면 표시하고 돌려준다 */
    private fun markInterrupt(r: Request?): Request? {
        if (r == null || r.interruptSent) return null
        r.interruptSent = true
        return r
    }

    private fun sendInterrupt(r: Request) {
        inbox.trySend(Msg.Interrupt(r))
        runCatching { backend.stop() }
    }

    private suspend fun run() {
        while (true) {
            val r = synchronized(lock) { queue.removeFirstOrNull()?.also { current = it } }
            if (r == null) {
                if (focusHeld) { focus.abandon(); focusHeld = false }
                wake.receive()
                continue
            }
            try {
                play(r)
            } finally {
                synchronized(lock) { if (current === r) current = null }
            }
        }
    }

    /** 그 문장이 끝났다 — 끝 신호를 내기 **전에** 지금 문장에서 뺀다(끝난 문장의 늦은 취소가 엔진을 멈추지 않게) */
    private fun end(r: Request, signal: UtteranceSignal) {
        synchronized(lock) { if (current === r) current = null }
        emit(signal)
    }

    private suspend fun play(r: Request) {
        if (clock() - r.requestedAt > maxQueueWaitMs) {
            end(r, UtteranceSignal.Dropped(path, r.tag, clock(), "queued-too-long"))
            return
        }
        // 앞 문장의 늦은 신호는 버린다. 이 문장을 겨냥한 끊기 신호(취소·급한 문장·전부 멈춤)가 벌써 왔으면 읽지 않는다
        var interrupted = false
        while (true) {
            val m = inbox.tryReceive().getOrNull() ?: break
            if (m is Msg.Interrupt && m.target === r) interrupted = true
        }
        if (interrupted) {
            end(r, UtteranceSignal.Dropped(path, r.tag, clock(), "interrupted"))
            return
        }
        if (!focusHeld) { focus.request(); focusHeld = true }
        try {
            backend.speak(r.text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            end(r, UtteranceSignal.Failed(path, r.tag, clock(), "speak 예외 ${e.javaClass.simpleName}"))
            return
        }
        // 1) 시작 기다리기
        val startDeadline = clock() + startTimeoutMs
        while (true) {
            when (val m = await(startDeadline)) {
                null -> {
                    end(r, UtteranceSignal.Failed(path, r.tag, clock(), "시작 시간 초과 ${startTimeoutMs / 1_000}s"))
                    runCatching { backend.stop() }
                    settle()
                    return
                }
                Msg.Start -> { val now = clock(); emit(UtteranceSignal.Started(path, r.tag, now, now - r.requestedAt)); break }
                // 시작 신호 없이 끝 — 성공으로 본다(지연 표본 없음)
                Msg.Done -> { end(r, UtteranceSignal.Done(path, r.tag, clock())); return }
                is Msg.Error -> { end(r, UtteranceSignal.Failed(path, r.tag, clock(), m.reason)); return }
                Msg.Stop -> { end(r, UtteranceSignal.Dropped(path, r.tag, clock(), "stopped")); return }
                is Msg.Interrupt -> if (m.target === r) { settle(); end(r, UtteranceSignal.Dropped(path, r.tag, clock(), "interrupted")); return }
            }
        }
        // 2) 끝 기다리기
        val doneDeadline = clock() + doneTimeoutMs(r.text)
        while (true) {
            when (val m = await(doneDeadline)) {
                null, Msg.Done -> { end(r, UtteranceSignal.Done(path, r.tag, clock())); return }
                Msg.Start -> Unit
                is Msg.Error -> { end(r, UtteranceSignal.Failed(path, r.tag, clock(), m.reason)); return }
                Msg.Stop -> { end(r, UtteranceSignal.Dropped(path, r.tag, clock(), "stopped")); return }
                is Msg.Interrupt -> if (m.target === r) { settle(); end(r, UtteranceSignal.Dropped(path, r.tag, clock(), "interrupted")); return }
            }
        }
    }

    /** 엔진이 멈췄다는 신호(Stop·Done·Error)를 잠깐 기다린다 — 바로 다음 speak 가 늦게 도착한 stop 에 끊기지 않게 */
    private suspend fun settle() {
        val deadline = clock() + stopSettleMs
        while (true) {
            when (await(deadline)) {
                null, Msg.Stop, Msg.Done, is Msg.Error -> return
                else -> Unit
            }
        }
    }

    private suspend fun await(deadline: Long): Msg? {
        val left = deadline - clock()
        if (left <= 0) return inbox.tryReceive().getOrNull()
        return withTimeoutOrNull(left) { inbox.receive() }
    }

    companion object {
        const val START_TIMEOUT_MS = 15_000L
        const val DONE_BASE_MS = 20_000L
        const val DONE_PER_CHAR_MS = 300L
        const val MAX_QUEUE_WAIT_MS = 30_000L
        const val STOP_SETTLE_MS = 1_000L
    }
}
