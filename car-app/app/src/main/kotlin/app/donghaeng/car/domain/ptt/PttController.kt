package app.donghaeng.car.domain.ptt

import app.donghaeng.car.adapter.audio.RecordStart
import app.donghaeng.car.adapter.audio.RecordStop
import app.donghaeng.car.adapter.audio.RecordedClip
import app.donghaeng.car.adapter.audio.VoiceRecorder
import app.donghaeng.car.adapter.speech.ChainOutcome
import app.donghaeng.car.adapter.speech.RecognizerKind
import app.donghaeng.car.adapter.speech.YesNoListener
import app.donghaeng.car.adapter.voice.VoiceAdapter
import app.donghaeng.car.core.common.DhLog
import app.donghaeng.car.domain.model.Priority
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * F16 무전 보내기 상태 기계 (docs/05 § 무전, 사용자 지시 2026-10-28) — 순수 로직 + 코루틴, 시계·녹음기·인식기·음성·전송을 주입받는다.
 *
 * ```
 * IDLE ─press→ RECORDING(최대 15초, 다시 누르면 끝)
 *   ├ 0.7초 미만 → NOTICE TOO_SHORT("너무 짧아요", 버림)
 *   └→ CONFIRMING
 *        음성 확인: TTS "보낼까요?" 를 다 읽은 뒤 마이크 5초(원형 카운트다운) — 인식 체인(Pleos STT → Android STT → Vosk)
 *          ├ 네   → SENDING → SENT 3초("N대에 보냈어요") / 실패 → '보내지 않은 무전' + NOTICE SEND_FAILED
 *          ├ 아니 → NOTICE CANCELLED("취소했어요", 버림)
 *          ├ 무응답 → '보내지 않은 무전' + NOTICE KEPT_UNSENT("보내지 않았어요. 멈춘 뒤 목록에서 보낼 수 있어요")
 *          └ 인식기가 듣다가 모두 실패 → 카운트다운 모드로
 *        카운트다운 모드(인식기가 하나도 없음): "5초 뒤 보낼게요 — 취소하려면 무전 버튼" → 5초 뒤 자동 전송, 그 사이 누르면 취소
 *          (디버그 주입 답 `RecognizerChain.injectAnswer` 은 카운트다운 중에도 받는다 — 네 = 바로 보냄, 아니 = 취소, 무응답 = 보관)
 *        확인 중(질문을 읽는 동안 포함) 누르면 = 취소
 * SENT·NOTICE 중 누르면 새 녹음. SENDING 중 누름은 무시.
 * ```
 * 사이클은 한 번에 하나만 돈다: 새 사이클은 옛 사이클이 **끝난 뒤**(cancelAndJoin) 시작하고, 녹음기를 여는 동안에도 단계는 RECORDING 이다 —
 * 녹음기는 하나라 옛 사이클의 정리(`recorder.cancel()`)가 새 사이클의 녹음을 풀면 말한 무전이 사라진다.
 * 주행·정차와 무관하게 동작한다(입력은 버튼·핸들 키뿐). '보내지 않은 무전' 다시 보내기·지우기([resend]·[deleteUnsent])는 **정차 중에만**.
 * 녹음 내용·인식 원문·닉네임은 로그에 남기지 않는다 — `ptt: …` 줄은 단계·길이·바이트·판정·근거만(규칙 8).
 *
 * '보내지 않은 무전'은 [store](기기: 앱 내부 저장소 `files/ptt_unsent/`)에 오디오 + 메타로 둔다(사용자 결정 2026-10-28) — 앱 재시작·프로세스 종료 뒤
 * [start] 가 읽어 두고(`ptt: unsent restored …`) 그룹 세션이 복원되면 **같은 그룹 것만** 목록에 다시 보인다(다른 그룹 것은 지움). 시작 뒤
 * [PttTimings.restoreGraceMs] 안에 그룹이 오지 않으면(그룹 없음) 지운다. 다시 보냄 성공·지우기·그룹 종료(바뀜) 때 파일도 지운다.
 *
 * 사이클마다 번호를 매긴다([PttState.cycleSeq]·[PttState.idleCycleSeq]) — `PttRuntime` 이 받은 무전을 끊은 누름 뒤의 흐름이
 * IDLE 로 끝났는지(보냄·취소·보관·짧음·실패 어느 쪽이든) 가려 끊긴 무전을 처음부터 다시 틀게 한다.
 *
 * **음성 안내와 마이크가 섞이지 않게(B012·B013)**: 녹음을 열기 직전 [micOpening](기기: VoiceAnnouncer.yieldToMic — 이미 넘긴 NORMAL·LOW 안내를 멈추고
 * 무전 뒤 다시)을 부르고, 이 상태 기계가 낸 안내("보냈어요."·"취소했어요." 등 — 꼬리표 `ptt-N`)도 아직 남았으면 버린다. "보낼까요?"·"5초 뒤 보낼게요"를
 * 상한 안에 다 읽지 못하면(TIMEOUT) 체인이 그 문장을 버린다 — 늦게 나와 열린 마이크와 겹치지 않게.
 * **마이크를 쓸 수 없는 백그라운드(B015)**: [micUsable] 이 false(API 30+ 에서 동행 화면이 안 보이고 포그라운드 서비스에 microphone 타입이 없음 —
 * 녹음이 무음이 된다)면 녹음하지 않고 [PttPrompts.MIC_BACKGROUND] + NOTICE RECORD_FAILED. 디버그 주입 녹음은 이 검사를 건너뛴다.
 * **멱등 키(B073, docs/03 계약 3)**: 녹음 한 개마다 키 하나(`u<시각>-<순번>` — '보내지 않은 무전' id 와 같다)를 만들어 첫 전송과 다시 보내기에 같은 키를
 * `Idempotency-Key` 로 보낸다 — 서버가 커밋했는데 응답을 잃은 뒤 다시 보내도 그룹에 두 번 나가지 않는다.
 *
 * @param recipients 받는 차 수(나 제외, 모르면 null → 0)
 * @param micGranted 마이크 권한을 다시 읽는다(누를 때마다)
 * @param micUsable 지금 녹음하면 소리가 담기는가(권한과 별개 — 백그라운드 while-in-use 제한). 기본 true
 * @param micOpening 녹음을 열기 직전(음성 안내를 비킨다). 기본 = 아무것도 안 함
 * @param clock 단조 시계(기기: SystemClock.elapsedRealtime) — 녹음 길이·카운트다운
 * @param wallClock 벽시계 — '보내지 않은 무전' 시각 표시
 * @param store '보내지 않은 무전' 저장소(기본 메모리 — 기기 DI 는 파일)
 */
class PttController(
    private val recorder: VoiceRecorder,
    private val listener: YesNoListener,
    private val voice: VoiceAdapter,
    private val sender: VoiceSender,
    private val groupId: () -> String?,
    private val recipients: () -> Int? = { null },
    private val micGranted: () -> Boolean = { true },
    private val isDriving: () -> Boolean = { false },
    private val timings: PttTimings = PttTimings(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clock: () -> Long = System::currentTimeMillis,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val maxUnsent: Int = MAX_UNSENT,
    private val store: UnsentVoiceStore = InMemoryUnsentVoiceStore(),
    private val micUsable: () -> Boolean = { true },
    private val micOpening: () -> Unit = {},
) {
    private sealed interface Command {
        data class Press(val source: String) : Command
        data class Resend(val id: String, val done: CompletableDeferred<Boolean>) : Command
        data class Delete(val id: String, val done: CompletableDeferred<Boolean>) : Command
        data class GroupChanged(val groupId: String?) : Command

        /** 시작 — 저장소의 '보내지 않은 무전'을 읽어 그룹 확인까지 들고 있는다 */
        data object Restore : Command

        /** 시작 뒤 [PttTimings.restoreGraceMs] — 아직 그룹이 없으면 읽어 둔 것을 지운다 */
        data object RestoreTimeout : Command
    }

    /**
     * 사이클 하나가 쥔 녹음 파일 — 보냈거나 목록으로 넘기면 null(사이클이 끝날 때 남아 있으면 지운다).
     * [key] = 이 녹음의 전송 멱등 키(B073) — '보내지 않은 무전'에 넣으면 그 id 가 되어 다시 보내기도 같은 키를 쓴다.
     */
    private class ClipHolder(var clip: RecordedClip?, val key: String? = null)

    private sealed interface Race<out T> {
        data class Done<T>(val value: T) : Race<T>
        data object Pressed : Race<Nothing>
        data object Timeout : Race<Nothing>
        data class Error(val e: Exception) : Race<Nothing>
    }

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val inbox = Channel<Command>(Channel.UNLIMITED)
    /** RECORDING·CONFIRMING 중에 들어온 누름(끝내기·취소) */
    private val presses = Channel<Unit>(Channel.CONFLATED)
    private var actor: Job? = null
    private var cycle: Job? = null
    private var currentGroup: String? = null
    private var unsentSeq = 0
    /** 저장소에서 읽었지만 그룹을 아직 모르는 '보내지 않은 무전'(액터 안에서만) — null = 기다리는 것 없음 */
    private var restored: List<UnsentVoice>? = null
    /** 사이클 번호(액터 안에서만 올린다) */
    private var cycleCounter = 0
    /** 지금 도는 사이클의 번호 — 사이클은 한 번에 하나라(cancelAndJoin 뒤 올림) toIdle 이 자기 번호를 읽는다 */
    @Volatile private var runningCycle = 0

    private val _state = MutableStateFlow(PttState())
    val state: StateFlow<PttState> = _state.asStateFlow()

    private val _unsent = MutableStateFlow<List<UnsentVoice>>(emptyList())
    /** '보내지 않은 무전'(최신 먼저, 최대 [maxUnsent]개) — 정차 대시보드 목록 */
    val unsent: StateFlow<List<UnsentVoice>> = _unsent.asStateFlow()

    @Synchronized
    fun start() {
        if (actor?.isActive == true) return
        _state.update { it.copy(micGranted = safeMic()) }
        // 그룹 세션보다 먼저 처리된다(같은 inbox, 먼저 넣음)
        inbox.trySend(Command.Restore)
        actor = scope.launch {
            launch {
                delay(timings.restoreGraceMs)
                inbox.trySend(Command.RestoreTimeout)
            }
            for (c in inbox) handle(c)
        }
    }

    @Synchronized
    fun stop() {
        actor?.cancel(); actor = null
        cycle?.cancel(); cycle = null
        recorder.cancel()
    }

    /** 무전 버튼·핸들 키 한 번(패널 탭·대시보드 탭·MainActivity 키·MediaSession·디버그 — VoiceTriggerAdapter 가 부른다) */
    fun press(source: String = "UI") {
        inbox.trySend(Command.Press(source))
    }

    /** '보내지 않은 무전' 다시 보내기 — 정차 중이고 지금 다른 무전을 보내고 있지 않을 때만. 시작했으면 true */
    suspend fun resend(id: String): Boolean = CompletableDeferred<Boolean>().also { inbox.trySend(Command.Resend(id, it)) }.await()

    /** '보내지 않은 무전' 지우기 — 정차 중에만. 지웠으면 true */
    suspend fun deleteUnsent(id: String): Boolean = CompletableDeferred<Boolean>().also { inbox.trySend(Command.Delete(id, it)) }.await()

    /** 그룹이 바뀌거나 끝났다(PttRuntime 이 세션을 보고 부른다) — 진행 중인 녹음·확인을 버리고 '보내지 않은 무전'을 지운다 */
    fun onGroupChanged(groupId: String?) {
        inbox.trySend(Command.GroupChanged(groupId))
    }

    /** 마이크 권한을 다시 읽어 [PttState.micGranted] 에 반영(권한 다이얼로그 결과 뒤) */
    fun refreshPermissions() {
        val mic = safeMic()
        _state.update { it.copy(micGranted = mic) }
    }

    /** 디버그 `ptt_status` 한 줄(민감 정보 없음) */
    fun describe(): String {
        val s = _state.value
        return "stage=${s.stage} remaining=${s.remainingS}s listening=${s.listening} countdown=${s.countdownMode} notice=${s.notice ?: "-"} " +
            "mic=${s.micGranted} unsent=${_unsent.value.size} persisted=${persistedCount()} cycle=${s.cycleSeq}/${s.idleCycleSeq} " +
            "last=${s.lastAnswer ?: "-"}/${s.lastRecognizer ?: "-"} recognizers=[${listener.describe()}] ${s.stats}"
    }

    /** 저장소(앱 내부 저장소 파일)에 있는 '보내지 않은 무전' 수 — 디버그 `persisted=`(읽기 실패면 -1) */
    fun persistedCount(): Int = runCatching { store.count() }.getOrDefault(-1)

    // ---------------------------------------------------------------- 명령 처리(액터 — 한 번에 하나씩)

    private suspend fun handle(c: Command) {
        when (c) {
            is Command.Press -> onPress(c.source)
            is Command.Resend -> c.done.complete(onResend(c.id))
            is Command.Delete -> c.done.complete(onDelete(c.id))
            is Command.GroupChanged -> onGroup(c.groupId)
            Command.Restore -> onRestore()
            Command.RestoreTimeout -> onRestoreTimeout()
        }
    }

    private fun onRestore() {
        val loaded = runCatching { store.load() }.getOrElse {
            DhLog.w("ptt: unsent restore 실패 ${it.javaClass.simpleName}")
            emptyList()
        }
        if (loaded.isEmpty()) return
        val gid = currentGroup
        if (gid != null) {
            applyRestore(loaded, gid)
        } else {
            restored = loaded
            DhLog.i("ptt: unsent restored n=${loaded.size} (그룹 확인 대기 — 같은 그룹이면 목록으로, 아니면 지움)")
        }
    }

    private fun onRestoreTimeout() {
        val r = restored ?: return
        if (currentGroup != null) return
        DhLog.i("ptt: unsent restore — 시작 뒤 ${timings.restoreGraceMs / 1000}s 동안 그룹 없음 → 지움")
        applyRestore(r, null)
    }

    /** 읽어 둔 것 중 [gid] 그룹 것만 목록으로(최신 먼저, 상한), 나머지(다른 그룹·그룹 없음)는 저장소에서 지운다. 로그에는 수만 */
    private fun applyRestore(r: List<UnsentVoice>, gid: String?) {
        restored = null
        val keep = if (gid == null) emptyList() else r.filter { it.groupId == gid && it.file.isFile }
        val drop = r.filterNot { k -> keep.any { it.id == k.id } }
        drop.forEach { runCatching { store.remove(it.id) } }
        if (keep.isNotEmpty()) {
            val list = (_unsent.value + keep).distinctBy { it.id }.sortedByDescending { it.createdAtMs }
            list.drop(maxUnsent).forEach { runCatching { store.remove(it.id) }; it.file.delete() }
            _unsent.value = list.take(maxUnsent)
        }
        DhLog.i("ptt: unsent restored kept=${keep.size} dropped=${drop.size} (${if (gid == null) "그룹 없음" else "같은 그룹만"}) n=${_unsent.value.size} persisted=${persistedCount()}")
    }

    private suspend fun onPress(source: String) {
        val stage = _state.value.stage
        _state.update { it.copy(stats = it.stats.copy(presses = it.stats.presses + 1)) }
        DhLog.i("ptt: press source=$source stage=$stage")
        when (stage) {
            PttStage.RECORDING, PttStage.CONFIRMING -> presses.trySend(Unit)
            PttStage.SENDING -> {
                _state.update { it.copy(stats = it.stats.copy(ignoredPresses = it.stats.ignoredPresses + 1)) }
                DhLog.i("ptt: press ignored (sending)")
            }
            PttStage.IDLE, PttStage.SENT, PttStage.NOTICE -> {
                val gid = currentGroup ?: groupId()
                if (gid == null) {
                    DhLog.w("ptt: press without group → ignored")
                    launchCycle { notice(PttNotice.NO_GROUP) }
                    return
                }
                launchCycle { recordCycle(gid) }
            }
        }
    }

    private suspend fun onResend(id: String): Boolean {
        val stage = _state.value.stage
        if (stage.busy) {
            DhLog.i("ptt: resend refused (busy stage=$stage)")
            return false
        }
        if (isDriving()) {
            DhLog.i("ptt: resend refused (driving — 정차 중에만)")
            return false
        }
        val item = _unsent.value.firstOrNull { it.id == id } ?: return false
        val gid = currentGroup ?: groupId()
        if (gid == null || gid != item.groupId || !item.file.isFile) {
            DhLog.w("ptt: resend dropped id=$id (group changed or file missing)")
            removeUnsent(id, deleteFile = true)
            return false
        }
        launchCycle { resendCycle(gid, item) }
        return true
    }

    private fun onDelete(id: String): Boolean {
        if (isDriving()) {
            DhLog.i("ptt: delete refused (driving — 정차 중에만)")
            return false
        }
        return removeUnsent(id, deleteFile = true).also { if (it) DhLog.i("ptt: unsent deleted n=${_unsent.value.size}") }
    }

    private suspend fun onGroup(gid: String?) {
        if (gid == currentGroup) return
        val had = currentGroup
        currentGroup = gid
        if (had != null || _unsent.value.isNotEmpty() || _state.value.stage.busy) {
            // 옛 사이클의 정리(finally)가 끝난 뒤 다음 명령을 받는다 — 바로 이어진 누름의 새 녹음을 옛 정리가 풀지 않게
            cycle?.cancelAndJoin(); cycle = null
            recorder.cancel()
            val dropped = _unsent.value
            dropped.forEach { runCatching { store.remove(it.id) }; it.file.delete() }
            // 그룹이 끝났거나 바뀌었다 — 저장소도 비운다(그룹을 아직 몰라 읽어만 둔 것은 아래 applyRestore 가 가린다)
            val cleared = if (had != null) runCatching { store.clear() }.getOrDefault(0) else 0
            _unsent.value = emptyList()
            _state.update { PttState(micGranted = it.micGranted, stats = it.stats, cycleSeq = it.cycleSeq, idleCycleSeq = it.idleCycleSeq) }
            DhLog.i("ptt: group changed → reset (unsent dropped=${dropped.size} persisted cleared=$cleared)")
        }
        restored?.let { applyRestore(it, gid) }
    }

    private suspend fun launchCycle(block: suspend () -> Unit) {
        // 옛 사이클이 끝날 때까지 기다린다(녹음기 start 가 동기라 그 안에서는 취소를 못 알아챈다) — 두 사이클이 녹음기 하나를 같이 쓰지 않게
        cycle?.cancelAndJoin()
        drainPresses()
        // 옛 사이클이 완전히 끝난 뒤 번호를 올린다 — 옛 사이클의 toIdle 은 옛 번호로만 남는다
        val seq = ++cycleCounter
        runningCycle = seq
        _state.update { it.copy(cycleSeq = seq) }
        cycle = scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DhLog.e("ptt: cycle failed ${e.javaClass.simpleName}")
                toIdle()
            }
        }
    }

    // ---------------------------------------------------------------- 사이클

    private suspend fun recordCycle(gid: String) {
        val holder = ClipHolder(null, key = newUnsentId())
        var recording = false
        try {
            val mic = safeMic()
            _state.update { it.copy(micGranted = mic) }
            if (!mic && !recorder.hasPendingInjection) {
                DhLog.w("ptt: mic permission missing → 녹음 안 함")
                speakLater(PttPrompts.MIC_PERMISSION)
                notice(PttNotice.MIC_PERMISSION)
                return
            }
            // 권한은 있어도 지금 녹음하면 무음이 되는 상태(백그라운드 + 포그라운드 서비스에 microphone 없음, B015) — 무음을 보내지 않고 안내만
            if (!recorder.hasPendingInjection && !safeMicUsable()) {
                DhLog.w("ptt: mic not usable in background (FGS microphone 없음) → 녹음 안 함 — 동행 화면을 한 번 열면 붙는다")
                _state.update { it.copy(stats = it.stats.copy(micBlocked = it.stats.micBlocked + 1)) }
                speakLater(PttPrompts.MIC_BACKGROUND)
                notice(PttNotice.RECORD_FAILED)
                return
            }
            // 마이크를 열기 전에 음성 안내를 비킨다(B013) — 이 상태 기계의 지난 안내("보냈어요."·"취소했어요."…)와 이미 넘긴 NORMAL·LOW 안내가
            // 녹음 한가운데 나와 그룹에 보내는 무전에 섞이지 않게(Pleos 경로는 시작이 5~9 s 늦다). 급제동(HIGH)·에어백은 그대로 둔다
            cancelOwnPrompts()
            runCatching(micOpening).onFailure { DhLog.w("ptt: micOpening 실패 ${it.javaClass.simpleName}") }
            // 녹음기를 여는 동안(MediaRecorder prepare·포커스 — 실기기 수백 ms)에도 바쁨으로 보인다. 그 사이 누름은 presses 로 가서 녹음 루프가 시작할 때 버린다
            // (빠른 두 번 탭이 새 사이클을 세워 녹음기를 다투지 않게). 화면은 바로 '녹음 중'이 된다
            _state.update {
                it.copy(
                    stage = PttStage.RECORDING, remainingS = PttFormat.ceilSeconds(timings.maxRecordMs), totalS = (timings.maxRecordMs / 1000).toInt(),
                    level = 0f, listening = false, countdownMode = false, notice = null, sentTo = 0,
                )
            }
            when (val s = recorder.start()) {
                is RecordStart.Failed -> {
                    DhLog.w("ptt: record start failed reason=${s.reason}")
                    speakLater(PttPrompts.RECORD_FAILED)
                    notice(PttNotice.RECORD_FAILED)
                    return
                }
                RecordStart.Started -> recording = true
            }
            _state.update { it.copy(stats = it.stats.copy(recordings = it.stats.recordings + 1)) }
            // 인식 준비(Vosk 모델 적재 수 초)는 녹음하는 동안 미리
            scope.launch { runCatching { listener.prepare() } }
            val loop = recordLoop()
            val endReason = loop.reason
            recording = false
            val clip = when (val stop = recorder.stop()) {
                is RecordStop.Clip -> stop.clip
                is RecordStop.Failed -> {
                    if (stop.elapsedMs < timings.minRecordMs) tooShort(stop.elapsedMs)
                    else {
                        DhLog.w("ptt: record stop failed reason=${stop.reason}")
                        speakLater(PttPrompts.RECORD_FAILED)
                        notice(PttNotice.RECORD_FAILED)
                    }
                    return
                }
            }
            holder.clip = clip
            // 실마이크인데 녹음 내내 입력이 0 이면 무음(마이크 막힘·AVD hw.audioInput=no) — 막지는 않고 로그만(보관 뒤 다시 보내기 시연이 있다)
            val silent = !clip.injected && loop.peakLevel <= 0f
            DhLog.i("ptt: recorded end=$endReason dur=${clip.durationMs}ms bytes=${clip.sizeBytes} inject=${clip.injected}${if (silent) " silent=true" else ""}")
            if (clip.durationMs < timings.minRecordMs) {
                tooShort(clip.durationMs)
                return
            }
            confirm(gid, holder)
        } finally {
            if (recording) recorder.cancel()
            holder.clip?.file?.delete()
        }
    }

    private suspend fun resendCycle(gid: String, item: UnsentVoice) {
        DhLog.i("ptt: resend id=${item.id} dur=${item.durationMs}ms")
        // 파일은 목록이 쥐고 있다 — 보내면 목록에서 빼며 지우고, 실패하면 목록에 그대로 둔다
        send(gid, ClipHolder(null), item.toClip(), unsentId = item.id)
    }

    /** 녹음 루프 결과 — [reason] 다시 누르면 "press", 15초가 차면 "max". [peakLevel] = 녹음 동안 입력 크기 최댓값(0 = 무음) */
    private class LoopResult(val reason: String, val peakLevel: Float)

    /** 녹음 루프 — 다시 누르면 "press", 15초가 차면 "max" */
    private suspend fun recordLoop(): LoopResult {
        val started = clock()
        var peak = 0f
        drainPresses()
        while (true) {
            val left = timings.maxRecordMs - (clock() - started)
            if (left <= 0) return LoopResult("max", peak)
            val level = recorder.currentLevel()
            if (level > peak) peak = level
            _state.update {
                it.copy(
                    stage = PttStage.RECORDING, remainingS = PttFormat.ceilSeconds(left), totalS = (timings.maxRecordMs / 1000).toInt(),
                    level = level, listening = false, countdownMode = false, notice = null, sentTo = 0,
                )
            }
            if (withTimeoutOrNull(minOf(timings.tickMs, left)) { presses.receive() } != null) return LoopResult("press", peak)
        }
    }

    private suspend fun confirm(gid: String, holder: ClipHolder) {
        // 녹음이 끝나는 순간 겹친 누름(끝내려던 누름)은 취소로 치지 않는다
        drainPresses()
        if (!listener.hasCandidate()) {
            DhLog.i("ptt: confirm mode=countdown (${listener.describe()})")
            countdown(gid, holder)
            return
        }
        val confirmS = (timings.confirmMs / 1000).toInt()
        _state.update {
            it.copy(stage = PttStage.CONFIRMING, remainingS = confirmS, totalS = confirmS, level = 0f, listening = false, countdownMode = false, notice = null)
        }
        DhLog.i("ptt: confirm mode=voice")
        // 질문을 다 읽은 뒤에만 마이크를 연다(질문이 인식에 섞이지 않게). 읽는 동안 누르면 취소
        when (race(timeoutMs = null) { voice.speakAndWait(PttPrompts.ASK, Priority.NORMAL, timings.promptTimeoutMs) }) {
            Race.Pressed -> return cancel(holder, "press-during-prompt")
            else -> Unit
        }
        val deadline = clock() + timings.confirmMs
        _state.update { it.copy(listening = true, remainingS = confirmS) }
        val r = race(timeoutMs = timings.confirmMs + timings.listenGraceMs, tickerDeadline = deadline) { listener.listen(timings.confirmMs) }
        _state.update { it.copy(listening = false) }
        when (r) {
            Race.Pressed -> cancel(holder, "press-during-confirm")
            Race.Timeout -> keep(gid, holder, UnsentReason.NO_ANSWER, null)
            is Race.Error -> {
                DhLog.w("ptt: listen 실패 ${r.e.javaClass.simpleName} → 카운트다운")
                countdown(gid, holder)
            }
            is Race.Done -> onAnswer(gid, holder, r.value)
        }
    }

    private suspend fun onAnswer(gid: String, holder: ClipHolder, outcome: ChainOutcome) {
        _state.update { it.copy(lastAnswer = outcome.answer, lastRecognizer = outcome.kind) }
        when (outcome.answer) {
            YesNo.YES -> send(gid, holder, holder.clip ?: return)
            YesNo.NO -> cancel(holder, "answer-no")
            YesNo.NONE -> keep(gid, holder, UnsentReason.NO_ANSWER, null)
            null -> {
                DhLog.i("ptt: recognizers all failed → countdown")
                countdown(gid, holder)
            }
        }
    }

    private suspend fun countdown(gid: String, holder: ClipHolder) {
        val s = (timings.countdownMs / 1000).toInt()
        _state.update {
            it.copy(
                stage = PttStage.CONFIRMING, remainingS = s, totalS = s, level = 0f, listening = false, countdownMode = true, notice = null,
                stats = it.stats.copy(countdowns = it.stats.countdowns + 1),
            )
        }
        drainPresses()
        if (race(timeoutMs = null) { voice.speakAndWait(PttPrompts.COUNTDOWN, Priority.NORMAL, timings.promptTimeoutMs) } is Race.Pressed) {
            return cancel(holder, "press-during-countdown-prompt")
        }
        val deadline = clock() + timings.countdownMs
        // 카운트다운 중에도 디버그 주입 답(ptt_answer)은 받는다 — 네 = 바로 보냄, 아니 = 취소, 무응답 = 보관
        when (val r = race(timeoutMs = timings.countdownMs, tickerDeadline = deadline) { listener.awaitInjected() }) {
            Race.Pressed -> cancel(holder, "press-during-countdown")
            is Race.Done -> {
                _state.update { it.copy(lastAnswer = r.value, lastRecognizer = RecognizerKind.INJECTED) }
                when (r.value) {
                    YesNo.YES -> send(gid, holder, holder.clip ?: return)
                    YesNo.NO -> cancel(holder, "injected-no")
                    YesNo.NONE -> keep(gid, holder, UnsentReason.NO_ANSWER, null)
                }
            }
            Race.Timeout, is Race.Error -> {
                DhLog.i("ptt: countdown elapsed → send")
                send(gid, holder, holder.clip ?: return)
            }
        }
    }

    /** 보내기 — 성공이면 파일을 지우고 SENT 3초, 실패면 '보내지 않은 무전'에 두고 NOTICE SEND_FAILED */
    private suspend fun send(gid: String, holder: ClipHolder, clip: RecordedClip, unsentId: String? = null) {
        _state.update { it.copy(stage = PttStage.SENDING, remainingS = 0, listening = false, countdownMode = false, notice = null) }
        // 같은 녹음이면 첫 전송·다시 보내기가 같은 키(B073) — 응답을 잃고 다시 보내도 서버가 한 번만 저장·알린다
        val key = (unsentId ?: holder.key)?.let(::idempotencyKeyOf)
        val outcome = try {
            sender.send(gid, clip, key)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SendOutcome.Failed("exception-${e.javaClass.simpleName}")
        }
        when (outcome) {
            is SendOutcome.Sent -> {
                holder.clip = null
                if (unsentId != null) removeUnsent(unsentId, deleteFile = true) else clip.file.delete()
                val n = (recipients() ?: 0).coerceAtLeast(0)
                DhLog.i("ptt: sent voiceId=${outcome.voiceId} to=$n dur=${clip.durationMs}ms")
                _state.update { it.copy(stage = PttStage.SENT, sentTo = n, stats = it.stats.copy(sent = it.stats.sent + 1)) }
                speakLater(PttPrompts.SENT)
                delay(timings.sentLingerMs)
                toIdle()
            }
            is SendOutcome.Failed -> {
                DhLog.w("ptt: send failed reason=${outcome.reason} → 보내지 않은 무전")
                _state.update { it.copy(stats = it.stats.copy(sendFailed = it.stats.sendFailed + 1)) }
                if (unsentId != null) {
                    _unsent.update { list -> list.map { if (it.id == unsentId) it.copy(reason = UnsentReason.SEND_FAILED, failure = outcome.reason) else it } }
                    _unsent.value.firstOrNull { it.id == unsentId }?.let { runCatching { store.update(it) } }
                } else {
                    addUnsent(gid, holder, UnsentReason.SEND_FAILED, outcome.reason)
                }
                speakLater(PttPrompts.SEND_FAILED)
                notice(PttNotice.SEND_FAILED)
            }
        }
    }

    private suspend fun keep(gid: String, holder: ClipHolder, reason: UnsentReason, failure: String?) {
        addUnsent(gid, holder, reason, failure)
        _state.update { it.copy(stats = it.stats.copy(kept = it.stats.kept + 1)) }
        speakLater(PttPrompts.KEPT)
        notice(PttNotice.KEPT_UNSENT)
    }

    private suspend fun cancel(holder: ClipHolder, why: String) {
        DhLog.i("ptt: cancelled why=$why")
        holder.clip?.file?.delete()
        holder.clip = null
        _state.update { it.copy(stats = it.stats.copy(cancelled = it.stats.cancelled + 1)) }
        speakLater(PttPrompts.CANCELLED)
        notice(PttNotice.CANCELLED)
    }

    private suspend fun tooShort(durationMs: Long) {
        DhLog.i("ptt: too short dur=${durationMs}ms (< ${timings.minRecordMs}ms) → 버림")
        _state.update { it.copy(stats = it.stats.copy(tooShort = it.stats.tooShort + 1)) }
        speakLater(PttPrompts.TOO_SHORT)
        notice(PttNotice.TOO_SHORT)
    }

    private suspend fun notice(n: PttNotice) {
        _state.update { it.copy(stage = PttStage.NOTICE, notice = n, remainingS = 0, listening = false, countdownMode = false, level = 0f) }
        delay(timings.noticeMs)
        toIdle()
    }

    /** 사이클이 끝났다 — 이 사이클 번호를 [PttState.idleCycleSeq] 에 남긴다(PttRuntime 이 끊긴 무전을 다시 트는 때) */
    private fun toIdle() {
        val seq = runningCycle
        _state.update {
            it.copy(
                stage = PttStage.IDLE, notice = null, remainingS = 0, totalS = 0, level = 0f, listening = false, countdownMode = false, sentTo = 0,
                idleCycleSeq = maxOf(it.idleCycleSeq, seq),
            )
        }
    }

    // ---------------------------------------------------------------- 보내지 않은 무전

    private fun addUnsent(gid: String, holder: ClipHolder, reason: UnsentReason, failure: String?) {
        val clip = holder.clip ?: return
        holder.clip = null   // 이제 목록(과 저장소)이 파일을 쥔다
        val now = wallClock()
        // 녹음 때 만든 키를 id 로 — 다시 보내기가 첫 전송과 같은 멱등 키를 쓴다(B073)
        val id = holder.key?.takeIf { k -> _unsent.value.none { it.id == k } } ?: newUnsentId()
        val draft = UnsentVoice(id, gid, clip.file, clip.mime, clip.durationMs, now, reason, failure)
        // 앱 내부 저장소로 옮긴다(재시작 뒤에도 남게). 실패하면 이번 프로세스 동안 캐시 파일로 들고 있는다
        val stored = runCatching { store.add(draft) }.getOrNull()
        val item = stored ?: draft
        val list = listOf(item) + _unsent.value
        list.drop(maxUnsent).forEach { runCatching { store.remove(it.id) }; it.file.delete() }
        _unsent.value = list.take(maxUnsent)
        DhLog.i("ptt: kept unsent reason=$reason n=${_unsent.value.size} persisted=${if (stored != null) "yes" else "no"} total=${persistedCount()}")
    }

    /** '보내지 않은 무전' id 겸 녹음 멱등 키 — `u<벽시계 ms>-<순번>`(파일 이름으로도 쓴다 — 영숫자·`-` 만) */
    private fun newUnsentId(): String {
        val now = wallClock()
        var id: String
        do {
            id = "u$now-${++unsentSeq}"
        } while (_unsent.value.any { it.id == id })
        return id
    }

    private fun removeUnsent(id: String, deleteFile: Boolean): Boolean {
        val item = _unsent.value.firstOrNull { it.id == id } ?: return false
        if (deleteFile) {
            runCatching { store.remove(id) }
            item.file.delete()
        }
        _unsent.update { list -> list.filterNot { it.id == id } }
        return true
    }

    // ---------------------------------------------------------------- 도우미

    /** [block]·누름·시간 초과 중 먼저 오는 것. [tickerDeadline] 이 있으면 남은 초를 200 ms 마다 갱신 */
    private suspend fun <T> race(timeoutMs: Long?, tickerDeadline: Long? = null, block: suspend () -> T): Race<T> = coroutineScope {
        val result = CompletableDeferred<Race<T>>()
        val jobs = ArrayList<Job>(4)
        jobs += launch {
            try {
                result.complete(Race.Done(block()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                result.complete(Race.Error(e))
            }
        }
        jobs += launch { presses.receive(); result.complete(Race.Pressed) }
        if (timeoutMs != null) jobs += launch { delay(timeoutMs); result.complete(Race.Timeout) }
        if (tickerDeadline != null) jobs += launch {
            while (true) {
                _state.update { it.copy(remainingS = PttFormat.ceilSeconds(tickerDeadline - clock())) }
                delay(COUNTDOWN_TICK_MS)
            }
        }
        try {
            result.await()
        } finally {
            jobs.forEach { it.cancel() }
        }
    }

    private fun drainPresses() {
        while (presses.tryReceive().isSuccess) Unit
    }

    /** 이 상태 기계가 낸 짧은 안내의 꼬리표(최근 [MAX_PROMPT_TAGS]개) — 다음 녹음을 열 때 아직 남았으면 버린다(B013) */
    private val promptTags = ArrayDeque<String>()
    private var promptSeq = 0L

    /** 짧은 안내 음성 — 기다리지 않는다(화면 안내와 함께 나간다). 꼬리표 `ptt-N` 을 붙여 다음 녹음 직전에 버릴 수 있게 한다 */
    private fun speakLater(text: String) {
        val tag = synchronized(promptTags) {
            "ptt-${++promptSeq}".also { t ->
                promptTags.addLast(t)
                while (promptTags.size > MAX_PROMPT_TAGS) promptTags.removeFirst()
            }
        }
        scope.launch {
            try {
                voice.speak(text, Priority.NORMAL, tag)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DhLog.w("ptt: speak 실패 ${e.javaClass.simpleName}")
            }
        }
    }

    /** 지난 사이클의 안내("보냈어요."·"취소했어요."…)가 아직 남았으면 버린다 — 새 녹음에 섞이지 않게. 이미 끝난 꼬리표는 어댑터가 무시한다 */
    private fun cancelOwnPrompts() {
        val tags = synchronized(promptTags) { promptTags.toList().also { promptTags.clear() } }
        tags.forEach { t -> runCatching { voice.cancel(t) } }
    }

    private fun safeMic(): Boolean = runCatching(micGranted).getOrDefault(false)

    /** 판단이 예외로 실패하면 녹음을 막지 않는다(true) — 무음 가능성보다 무전을 못 보내는 쪽이 더 나쁘다 */
    private fun safeMicUsable(): Boolean = runCatching(micUsable).getOrDefault(true)

    companion object {
        const val MAX_UNSENT = 5
        const val COUNTDOWN_TICK_MS = 200L
        const val MAX_PROMPT_TAGS = 8

        /**
         * 녹음(= '보내지 않은 무전' id) → 업로드 멱등 키(docs/03 계약 3, `[A-Za-z0-9_-]{8,64}`) — 첫 전송과 다시 보내기가 같은 값.
         * 앞에 `ptt-` 를 붙여 짧은 id(테스트 시계)도 8자를 넘게 하고, 64자로 자른다.
         */
        fun idempotencyKeyOf(unsentId: String): String = "ptt-$unsentId".take(64)
    }
}
