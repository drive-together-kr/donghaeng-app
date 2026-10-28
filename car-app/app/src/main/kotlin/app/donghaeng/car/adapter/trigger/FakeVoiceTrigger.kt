package app.donghaeng.car.adapter.trigger

import app.donghaeng.car.domain.ptt.PttSettings
import kotlinx.coroutines.flow.update

/**
 * 테스트용 트리거 (F16, CLAUDE.md 규칙 2) — MediaSession 없이 [TriggerPolicy] 로 '세션이 켜졌을' 상태만 계산하고,
 * [simulateMediaKey] 로 미디어 버튼을 흉내 낸다(세션이 꺼져 있으면 키는 음악 앱 몫이라 false).
 */
class FakeVoiceTrigger(
    settings: PttSettings,
    clock: () -> Long,
    private val reclaimDelayMs: Long = TriggerPolicy.RECLAIM_DELAY_MS,
) : BaseVoiceTrigger(settings, clock) {

    private var otherMedia = false
    private var otherStoppedAt: Long? = null

    val sessionActive: Boolean
        get() = TriggerPolicy.sessionActive(groupActive.value, settings.handleButton.value, otherMedia, otherStoppedAt?.let { clock() - it }, reclaimDelayMs)

    fun setOtherMediaActive(active: Boolean) {
        if (otherMedia && !active) otherStoppedAt = clock()
        otherMedia = active
        _status.update { it.copy(otherMediaActive = active) }
        onInputsChanged()
    }

    /** 미디어 버튼 한 번(down 또는 up) — 세션이 켜져 있고 무전 키로 썼으면 true */
    fun simulateMediaKey(keyCode: Int, action: Int, repeatCount: Int = 0, eventTimeMs: Long = clock()): Boolean {
        if (!sessionActive) return false
        return onSessionKey(keyCode, action, repeatCount, eventTimeMs)
    }

    override fun onInputsChanged() {
        _status.update { it.copy(sessionActive = sessionActive, handleButton = settings.handleButton.value) }
    }

    override fun start() = onInputsChanged()
    override fun stop() = Unit
}
