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

    private data class Request(val text: String, val priority: Priority, val tag: String?, val requestedAt: Long)

    private sealed interface Msg {
        data object Start : Msg
        data object Done : Msg
        data class Error(val reason: String) : Msg
        data object Stop : Msg
        /** 더 급한 문장이 끼어들었다(엔진 stop 을 이미 불렀다) */
        data object Interrupt : Msg
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
        var interrupt = false
        synchronized(lock) {
            if (priority == Priority.CRITICAL) {
                queue.forEach { emit(UtteranceSignal.Dropped(path, it.tag, clock(), "flushed")) }
                queue.clear()
                queue.addFirst(r)
                interrupt = current != null
            } else {
                queue.addLast(r)
            }
        }
        if (interrupt) {
            inbox.trySend(Msg.Interrupt)
            runCatching { backend.stop() }
        }
        wake.trySend(Unit)
    }

    /** 엔진 리스너 → 여기로(어느 스레드든) */
    fun onStart() { inbox.trySend(Msg.Start) }
    fun onDone() { inbox.trySend(Msg.Done) }
    fun onError(reason: String) { inbox.trySend(Msg.Error(reason)) }
    fun onStop() { inbox.trySend(Msg.Stop) }

    /** 전부 멈춤(음성 끄기·앱 정지) — 대기 문장은 Dropped("stopped") */
    fun stopAll() {
        val playing: Boolean
        synchronized(lock) {
            queue.forEach { emit(UtteranceSignal.Dropped(path, it.tag, clock(), "stopped")) }
            queue.clear()
            playing = current != null
        }
        if (playing) inbox.trySend(Msg.Interrupt)
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
                synchronized(lock) { current = null }
            }
        }
    }

    private suspend fun play(r: Request) {
        if (clock() - r.requestedAt > maxQueueWaitMs) {
            emit(UtteranceSignal.Dropped(path, r.tag, clock(), "queued-too-long"))
            return
        }
        // 앞 문장의 늦은 신호는 버린다. 이 문장을 끊으라는 신호(급한 문장이 방금 들어옴)가 있었으면 읽지 않는다
        var interrupted = false
        while (true) {
            val m = inbox.tryReceive().getOrNull() ?: break
            if (m == Msg.Interrupt) interrupted = true
        }
        if (interrupted && r.priority != Priority.CRITICAL) {
            emit(UtteranceSignal.Dropped(path, r.tag, clock(), "interrupted"))
            return
        }
        if (!focusHeld) { focus.request(); focusHeld = true }
        try {
            backend.speak(r.text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(UtteranceSignal.Failed(path, r.tag, clock(), "speak 예외 ${e.javaClass.simpleName}"))
            return
        }
        // 1) 시작 기다리기
        val startDeadline = clock() + startTimeoutMs
        while (true) {
            when (val m = await(startDeadline)) {
                null -> {
                    emit(UtteranceSignal.Failed(path, r.tag, clock(), "시작 시간 초과 ${startTimeoutMs / 1_000}s"))
                    runCatching { backend.stop() }
                    settle()
                    return
                }
                Msg.Start -> { val now = clock(); emit(UtteranceSignal.Started(path, r.tag, now, now - r.requestedAt)); break }
                // 시작 신호 없이 끝 — 성공으로 본다(지연 표본 없음)
                Msg.Done -> { emit(UtteranceSignal.Done(path, r.tag, clock())); return }
                is Msg.Error -> { emit(UtteranceSignal.Failed(path, r.tag, clock(), m.reason)); return }
                Msg.Stop -> { emit(UtteranceSignal.Dropped(path, r.tag, clock(), "stopped")); return }
                Msg.Interrupt -> { settle(); emit(UtteranceSignal.Dropped(path, r.tag, clock(), "interrupted")); return }
            }
        }
        // 2) 끝 기다리기
        val doneDeadline = clock() + doneTimeoutMs(r.text)
        while (true) {
            when (val m = await(doneDeadline)) {
                null, Msg.Done -> { emit(UtteranceSignal.Done(path, r.tag, clock())); return }
                Msg.Start -> Unit
                is Msg.Error -> { emit(UtteranceSignal.Failed(path, r.tag, clock(), m.reason)); return }
                Msg.Stop -> { emit(UtteranceSignal.Dropped(path, r.tag, clock(), "stopped")); return }
                Msg.Interrupt -> { settle(); emit(UtteranceSignal.Dropped(path, r.tag, clock(), "interrupted")); return }
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
