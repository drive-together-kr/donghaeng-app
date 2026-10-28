package app.donghaeng.car.surface

import app.donghaeng.car.adapter.voice.VoicePath
import app.donghaeng.car.domain.voice.VoiceCue
import app.donghaeng.car.feature.drive.DriveFormat
import app.donghaeng.car.feature.drive.EventHighlight
import app.donghaeng.car.feature.drive.Tone

/**
 * 위험 이벤트 강조 규칙 — 순수 함수(docs/05 §5.2, 2026-10-28 사용자 지시).
 *
 * 대상 = 위험 톤 이벤트(급제동 HARD_BRAKE · 에어백 AIRBAG_DEPLOYED, [DriveFormat.eventTone] = DANGER). 그 밖은 늘 [EventHighlight.NONE].
 * - **늦을 게 분명**([expectLate]) = 지금 음성 경로가 화면 표시만(DISPLAY_ONLY)이거나, 지금 경로의 최근 평균 요청 → 시작이 3초 초과
 *   (Pleos TTS 부하 시 5~9 s 실측) → 받은 순간부터 강조.
 * - 아니면 받은 지 [VOICE_DEADLINE_MS](3초)까지 그 이벤트의 음성 시작 신호(eventId 꼬리표)가 없을 때 강조.
 * - 강조 = 음성이 시작될 때까지 [EventHighlight.BLINK](빨간 바탕 + 깜빡임). 강조가 켜진 뒤 음성이 시작되면 [EventHighlight.STEADY]
 *   (깜빡임 멈춤, 빨간 강조만 [HOLD_AFTER_VOICE_MS] 3초) → 그다음 평소 모양. 강조 전에 음성이 시작됐으면 평소 모양 그대로.
 * - 음성이 끝내 시작되지 않으면(음성 꺼짐·중복·대상 아님·실패) 소식 줄이 보이는 동안(10초) 계속 깜빡인다.
 * 시각은 모두 기기 벽시계 — `ReceivedGroupEvent.receivedAtMs` 와 어댑터 시작 신호가 같은 시계다.
 */
object EventHighlightPolicy {
    const val VOICE_DEADLINE_MS = 3_000L
    const val HOLD_AFTER_VOICE_MS = 3_000L

    fun isDanger(type: String): Boolean = DriveFormat.eventTone(type) == Tone.DANGER

    fun expectLate(cue: VoiceCue): Boolean =
        cue.path == VoicePath.DISPLAY_ONLY || (cue.avgLatencyMs ?: 0L) > VOICE_DEADLINE_MS

    fun decide(type: String, eventId: String, receivedAtMs: Long, nowMs: Long, cue: VoiceCue): EventHighlight {
        if (!isDanger(type)) return EventHighlight.NONE
        val highlightFrom = if (expectLate(cue)) receivedAtMs else receivedAtMs + VOICE_DEADLINE_MS
        val startedAt = cue.starts[eventId]
        if (startedAt != null) {
            return if (startedAt >= highlightFrom && nowMs - startedAt < HOLD_AFTER_VOICE_MS) EventHighlight.STEADY else EventHighlight.NONE
        }
        return if (nowMs >= highlightFrom) EventHighlight.BLINK else EventHighlight.NONE
    }
}
