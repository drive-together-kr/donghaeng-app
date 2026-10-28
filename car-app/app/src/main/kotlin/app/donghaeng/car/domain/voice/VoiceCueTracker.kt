package app.donghaeng.car.domain.voice

import app.donghaeng.car.adapter.voice.UtteranceSignal
import app.donghaeng.car.adapter.voice.VoiceAdapter
import app.donghaeng.car.adapter.voice.VoicePath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 표면(패널 소식 줄·정차 대시보드 띠)이 위험 이벤트 강조를 정하려고 보는 음성 쪽 값(docs/05 §5.2).
 *
 * @property path 지금 음성 경로(체인). null = 모름
 * @property avgLatencyMs 지금 경로의 최근 10건 평균 요청 → 시작(ms). 표본 없으면 null
 * @property starts 문장 꼬리표(그룹 이벤트 eventId) → 음성 시작 시각(기기 벽시계 ms), 최근 [VoiceCueTracker.MAX_STARTS]개
 * @property lateAtReceipt 위험 이벤트 eventId → **받은 순간** '늦을 게 분명'이었나(표면 `SurfaceState.noteEvent` 가 한 번만 정해 붙인다 —
 *   강조 시작 시점이 그 뒤 평균 지연·경로 변화로 흔들리지 않게, docs/05 §5.2). 없으면 지금 값으로 판정
 */
data class VoiceCue(
    val path: VoicePath? = null,
    val avgLatencyMs: Long? = null,
    val starts: Map<String, Long> = emptyMap(),
    val lateAtReceipt: Map<String, Boolean> = emptyMap(),
)

/**
 * 음성 어댑터(체인)의 시작 신호를 eventId 별로 모은다. VoiceAnnouncer 가 이벤트 문장에 eventId 를 꼬리표로 붙이므로
 * "그 이벤트의 음성이 시작됐는가"를 표면이 알 수 있다(시작 신호가 없는 이벤트 = 아직 안 읽음·생략·실패 — 정책이 3초 뒤 강조).
 */
class VoiceCueTracker(voice: VoiceAdapter, scope: CoroutineScope) {

    private val starts = MutableStateFlow<Map<String, Long>>(emptyMap())

    val state: StateFlow<VoiceCue> = combine(voice.status, starts) { st, m -> VoiceCue(st.path, st.latency.avgMs, m) }
        .stateIn(scope, SharingStarted.Eagerly, VoiceCue(voice.status.value.path, voice.status.value.latency.avgMs))

    init {
        scope.launch {
            voice.signals.collect { s ->
                val tag = s.tag
                if (s is UtteranceSignal.Started && tag != null) starts.update { add(it, tag, s.atMs) }
            }
        }
    }

    companion object {
        const val MAX_STARTS = 32

        /** 같은 꼬리표는 처음 시작 시각을 유지하고, 오래된 것부터 [MAX_STARTS] 개만 남긴다 */
        fun add(map: Map<String, Long>, tag: String, atMs: Long): Map<String, Long> {
            if (tag in map) return map
            val next = LinkedHashMap(map).apply { put(tag, atMs) }
            while (next.size > MAX_STARTS) next.remove(next.keys.first())
            return next
        }
    }
}
