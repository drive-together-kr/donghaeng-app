package app.donghaeng.car.domain.ptt

import app.donghaeng.car.adapter.trigger.PttTriggerSource
import app.donghaeng.car.adapter.trigger.VoiceTriggerAdapter
import app.donghaeng.car.core.common.DhLog
import app.donghaeng.car.domain.session.GroupSession
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * F16 무전 배선 (Application 싱글턴 — DongHaengApp 이 [start]).
 *
 * - 그룹 세션 → [VoiceTriggerAdapter.setGroupActive](MediaSession 은 그룹이 있을 때만) + [PttController.onGroupChanged](그룹이 바뀌면 진행 중 무전·보내지 않은 무전 정리).
 * - 트리거 누름(패널·대시보드·MainActivity 키·MediaSession·디버그) → 재생 중인 받은 무전이 있으면 끊고([VoicePlayback.interrupt] — '다 못 들음') → [PttController.press].
 *   끊은 무전은 **그 누름 뒤의 내 녹음·확인 흐름이 IDLE 로 끝나면**(보냄·취소·보관·짧음·실패 어느 쪽이든 — [ResumeAfterFlow] 가 사이클 번호로 가린다)
 *   [VoicePlayback.resumeInterrupted] 로 처음부터 다시 튼다(사용자 결정 2026-10-28).
 * - 보내기 단계가 바쁘면(녹음·확인·전송) 받은 무전 재생을 미룬다([VoicePlayback.setHeld]).
 * - 보내기 바쁨 또는 받은 무전 재생 중이면 VoiceAnnouncer 의 NORMAL·LOW 문장을 붙잡는다([holdAnnouncer]).
 * - 두 상태를 합쳐 [publish] 로 내보낸다(DI: `SurfaceState.ptt` ← surface `PttUiMapper` — 패널·대시보드가 같은 값을 본다).
 */
class PttRuntime(
    private val trigger: VoiceTriggerAdapter,
    private val controller: PttController,
    private val playback: VoicePlayback,
    private val session: StateFlow<GroupSession?>,
    private val holdAnnouncer: (Boolean) -> Unit,
    private val publish: (PttState, VoicePlaybackState, List<UnsentVoice>) -> Unit,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var job: Job? = null
    private val resumeWatch = ResumeAfterFlow()

    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        controller.start()
        playback.start()
        trigger.start()
        job = scope.launch {
            launch {
                session.map { it?.groupId }.distinctUntilChanged().collect { gid ->
                    trigger.setGroupActive(gid != null)
                    controller.onGroupChanged(gid)
                    // 그룹이 바뀌면 끊긴 무전도 버려진다(VoicePlayback) — 기다릴 것 없음
                    resumeWatch.disarm()
                }
            }
            launch { trigger.presses.collect { onPress(it.source, it.key) } }
            launch { controller.state.map { it.stage.busy }.distinctUntilChanged().collect { playback.setHeld(it) } }
            launch {
                // 끊은 누름 뒤의 흐름이 IDLE 로 끝났다 → 끊긴 무전을 처음부터
                controller.state.collect { s -> if (resumeWatch.onState(s)) playback.resumeInterrupted("send-flow-idle cycle=${s.idleCycleSeq}") }
            }
            launch {
                combine(controller.state, playback.state) { c, p -> c.stage.busy || (p.playing != null && !p.playing.ended) }
                    .distinctUntilChanged()
                    .collect { holdAnnouncer(it) }
            }
            launch { combine(controller.state, playback.state, controller.unsent) { c, p, u -> Triple(c, p, u) }.collect { (c, p, u) -> publish(c, p, u) } }
        }
        DhLog.i("ptt: runtime started")
    }

    @Synchronized
    fun stop() {
        job?.cancel(); job = null
        trigger.stop()
        playback.stop()
        controller.stop()
    }

    private fun onPress(source: PttTriggerSource, key: String?) {
        if (playback.isPlaying) {
            // 상태 기계가 이 누름을 받기 전의 사이클 번호로 건다 — 그 뒤 사이클이 IDLE 로 끝나야 다시 튼다
            resumeWatch.arm(controller.state.value)
            playback.interrupt("ptt-press", resumeAfterFlow = true)
        }
        controller.press(if (key != null) "$source/$key" else source.name)
    }
}

/**
 * 끊긴 무전을 다시 틀 때(순수, F16) — 받은 무전을 끊은 누름 **뒤의** 무전 흐름이 IDLE 로 끝났는가.
 *
 * [arm] 은 누름이 상태 기계([PttController])에 닿기 전에 부른다. 그때 단계가
 * - IDLE·SENT·NOTICE 면 그 누름이 새 사이클(번호 +1)을 시작하므로 **지금 번호보다 큰** 사이클이 IDLE 로 끝나야 하고,
 * - 바쁨(RECORDING·CONFIRMING·SENDING — 누름은 지금 사이클 안에서 끝내기·취소·무시)이면 **지금 사이클**이 끝나면 된다.
 * 사이클 번호([PttState.idleCycleSeq])로 가리므로 상태 흐름이 합쳐져(StateFlow conflation) 중간 단계를 못 봐도, 누름이 닿기 전의 옛 IDLE 이
 * 늦게 와도 잘못 풀지 않는다. 한 번 풀면 다시 [arm] 할 때까지 잠잠하다.
 */
class ResumeAfterFlow {
    private var armedAt: Int? = null

    @Synchronized
    fun arm(now: PttState) {
        armedAt = if (now.stage.busy) now.cycleSeq - 1 else now.cycleSeq
    }

    @Synchronized
    fun disarm() {
        armedAt = null
    }

    val armed: Boolean
        @Synchronized get() = armedAt != null

    /** 상태 하나 → 지금 풀어야 하면 true(한 번만) */
    @Synchronized
    fun onState(s: PttState): Boolean {
        val at = armedAt ?: return false
        if (s.stage != PttStage.IDLE || s.idleCycleSeq <= at) return false
        armedAt = null
        return true
    }
}
