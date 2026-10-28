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
 * - 트리거 누름(패널·대시보드·MainActivity 키·MediaSession·디버그) → 재생 중인 받은 무전이 있으면 끊고([VoicePlayback.interrupt]) → [PttController.press].
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
                }
            }
            launch { trigger.presses.collect { onPress(it.source, it.key) } }
            launch { controller.state.map { it.stage.busy }.distinctUntilChanged().collect { playback.setHeld(it) } }
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
        if (playback.isPlaying) playback.interrupt("ptt-press")
        controller.press(if (key != null) "$source/$key" else source.name)
    }
}
