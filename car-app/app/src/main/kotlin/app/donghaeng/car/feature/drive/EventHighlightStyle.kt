package app.donghaeng.car.feature.drive

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue

/**
 * 위험 이벤트 강조 모양(패널 소식 줄·대시보드 띠 공통, docs/05 §5.2).
 * - [EventHighlight.BLINK]: 빨간 바탕 + 흰 글씨, 줄 전체 불투명도가 1.0 ↔ [BLINK_MIN_ALPHA] 로 오간다(한 번 [BLINK_HALF_MS]×2 ≈ 0.9 s —
 *   초당 3회 미만이라 광과민 기준 아래).
 * - [EventHighlight.STEADY]: 같은 빨간 강조, 깜빡임 없음.
 */
object EventHighlightStyle {
    const val BLINK_HALF_MS = 450
    const val BLINK_MIN_ALPHA = 0.35f

    fun isOn(h: EventHighlight): Boolean = h != EventHighlight.NONE
}

/** 강조 줄의 불투명도 — 깜빡임일 때만 움직인다 */
@Composable
fun highlightAlpha(h: EventHighlight): Float {
    if (h != EventHighlight.BLINK) return 1f
    val t = rememberInfiniteTransition(label = "event-blink")
    val a by t.animateFloat(
        initialValue = 1f,
        targetValue = EventHighlightStyle.BLINK_MIN_ALPHA,
        animationSpec = infiniteRepeatable(tween(EventHighlightStyle.BLINK_HALF_MS), RepeatMode.Reverse),
        label = "event-blink-alpha",
    )
    return a
}
