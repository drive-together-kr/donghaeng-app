package app.donghaeng.car.feature.settings

import app.donghaeng.car.adapter.voice.VoiceAdapterStatus
import app.donghaeng.car.adapter.voice.VoiceCandidateStatus
import app.donghaeng.car.adapter.voice.VoiceLatencyStats
import app.donghaeng.car.adapter.voice.VoicePath
import java.util.Locale

/**
 * 진단 화면 '음성 안내' 카드 문구 — 순수 함수(단위 테스트 대상, R19 체인).
 * 심사위원·통합 담당이 캡처 한 장으로 "어느 경로로 읽는지·왜·얼마나 늦는지"를 볼 수 있게 한다. 문장 원문·닉네임은 담지 않는다.
 */
object VoiceDiagFormat {

    /** "Pleos TTS (ON_DEVICE · ko_KR)" / "Android TTS (com.k2fsa…)" / "화면 표시만 (소리 없음)" + 선택 중 */
    fun pathLine(s: VoiceAdapterStatus): String {
        val base = when (s.path) {
            VoicePath.ANDROID_TTS -> "Android TTS (${s.engine ?: "?"})"
            VoicePath.PLEOS_TTS -> "Pleos TTS (ON_DEVICE · ko_KR)"
            VoicePath.DISPLAY_ONLY -> "화면 표시만 (소리 없음)"
            null -> when (s.ready) { null -> "초기화 중"; true -> "준비 (${s.engine ?: "?"})"; false -> "없음 → 화면 표시만" }
        }
        return base + if (s.selecting) "  · 위 순위 초기화 기다리는 중" else ""
    }

    /** 경로 코드(로그·demo.py 와 같은 이름) */
    fun pathCode(s: VoiceAdapterStatus): String = s.path?.name ?: "-"

    fun reasonLine(s: VoiceAdapterStatus): String = s.pathReason ?: s.detail ?: "-"

    /** "마지막 5.2 s · 평균 6.1 s · 최대 8.8 s (최근 10건)" — 표본 없으면 "-" */
    fun latencyLine(l: VoiceLatencyStats): String =
        if (l.count == 0) "-" else "마지막 ${sec(l.lastMs)} · 평균 ${sec(l.avgMs)} · 최대 ${sec(l.maxMs)} (최근 ${l.count}건)"

    /** 후보 한 줄: "ANDROID_TTS 불가 — 초기화 실패 status=-1" / "PLEOS_TTS 준비 — ON_DEVICE ko_KR 준비 (CaaS 4.2.10) · 평균 6.1 s" */
    fun candidateLine(c: VoiceCandidateStatus): String {
        val state = when {
            c.demoted -> "강등(연속 실패 ${c.consecutiveFailures}회)"
            c.ready == true -> "준비"
            c.ready == false -> "불가"
            else -> "초기화 중"
        }
        val detail = c.detail?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""
        val avg = c.latency.avgMs?.let { " · 평균 ${sec(it)}" } ?: ""
        return "${c.path} $state$detail$avg"
    }

    fun candidatesText(s: VoiceAdapterStatus): String =
        if (s.candidates.isEmpty()) "-" else s.candidates.joinToString("\n") { candidateLine(it) }

    fun countsLine(s: VoiceAdapterStatus): String =
        "소리 ${s.spokenCount} / 화면만 ${s.fallbackCount} / 실패 ${s.failedCount}   포커스 ${if (s.focusGranted) "보유" else "-"}"

    fun sec(ms: Long?): String = if (ms == null) "-" else String.format(Locale.ROOT, "%.1f s", ms / 1_000.0)
}
