package app.donghaeng.car.feature.settings

import app.donghaeng.car.adapter.voice.VoiceAdapterStatus
import app.donghaeng.car.adapter.voice.VoiceCandidateStatus
import app.donghaeng.car.adapter.voice.VoiceLatencyStats
import app.donghaeng.car.adapter.voice.VoicePath
import org.junit.Assert.assertEquals
import org.junit.Test

/** 진단 '음성 안내' 카드 — 경로·사유·후보·지연 문구 */
class VoiceDiagFormatTest {

    private val pleosNow = VoiceAdapterStatus(
        engine = "Pleos TextToSpeech ON_DEVICE", ready = true, path = VoicePath.PLEOS_TTS,
        pathReason = "ANDROID_TTS 불가(초기화 실패 status=-1 (엔진 없음))",
        latency = VoiceLatencyStats(lastMs = 5_240, avgMs = 6_080, maxMs = 8_810, count = 4),
        spokenCount = 4, fallbackCount = 1, failedCount = 0,
        candidates = listOf(
            VoiceCandidateStatus(VoicePath.ANDROID_TTS, ready = false, engine = "Android TextToSpeech", detail = "초기화 실패 status=-1 (엔진 없음)"),
            VoiceCandidateStatus(VoicePath.PLEOS_TTS, ready = true, engine = "Pleos TextToSpeech ON_DEVICE", detail = "ON_DEVICE ko_KR 준비 (CaaS 4.2.10)", latency = VoiceLatencyStats(avgMs = 6_080, count = 4)),
        ),
    )

    @Test
    fun `pleos path on a clean pleos avd`() {
        assertEquals("PLEOS_TTS", VoiceDiagFormat.pathCode(pleosNow))
        assertEquals("Pleos TTS (ON_DEVICE · ko_KR)", VoiceDiagFormat.pathLine(pleosNow))
        assertEquals("ANDROID_TTS 불가(초기화 실패 status=-1 (엔진 없음))", VoiceDiagFormat.reasonLine(pleosNow))
        assertEquals("마지막 5.2 s · 평균 6.1 s · 최대 8.8 s (최근 4건)", VoiceDiagFormat.latencyLine(pleosNow.latency))
        assertEquals(
            "ANDROID_TTS 불가 — 초기화 실패 status=-1 (엔진 없음)\nPLEOS_TTS 준비 — ON_DEVICE ko_KR 준비 (CaaS 4.2.10) · 평균 6.1 s",
            VoiceDiagFormat.candidatesText(pleosNow),
        )
        assertEquals("소리 4 / 화면만 1 / 실패 0   포커스 -", VoiceDiagFormat.countsLine(pleosNow))
    }

    @Test
    fun `android, display only, selecting and demoted lines`() {
        assertEquals("Android TTS (com.k2fsa.sherpa.onnx.tts.engine)", VoiceDiagFormat.pathLine(VoiceAdapterStatus(engine = "com.k2fsa.sherpa.onnx.tts.engine", ready = true, path = VoicePath.ANDROID_TTS)))
        assertEquals("화면 표시만 (소리 없음)", VoiceDiagFormat.pathLine(VoiceAdapterStatus(ready = false, path = VoicePath.DISPLAY_ONLY)))
        assertEquals(
            "Pleos TTS (ON_DEVICE · ko_KR)  · 위 순위 초기화 기다리는 중",
            VoiceDiagFormat.pathLine(VoiceAdapterStatus(ready = true, path = VoicePath.PLEOS_TTS, selecting = true)),
        )
        assertEquals(
            "ANDROID_TTS 강등(연속 실패 3회) — ko_KR 준비 (isLanguageAvailable=1)",
            VoiceDiagFormat.candidateLine(VoiceCandidateStatus(VoicePath.ANDROID_TTS, ready = true, engine = "x", detail = "ko_KR 준비 (isLanguageAvailable=1)", demoted = true, consecutiveFailures = 3)),
        )
        assertEquals("PLEOS_TTS 초기화 중", VoiceDiagFormat.candidateLine(VoiceCandidateStatus(VoicePath.PLEOS_TTS, ready = null, engine = null, detail = null)))
        assertEquals("-", VoiceDiagFormat.latencyLine(VoiceLatencyStats()))
        assertEquals("-", VoiceDiagFormat.candidatesText(VoiceAdapterStatus()))
        assertEquals("초기화 중", VoiceDiagFormat.pathLine(VoiceAdapterStatus()))
    }
}
