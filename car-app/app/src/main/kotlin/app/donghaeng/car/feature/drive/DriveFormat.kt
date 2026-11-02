package app.donghaeng.car.feature.drive

import app.donghaeng.car.feature.events.EventsViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 주행 표면(플로팅 패널·정차 대시보드, docs/05 §5.1·§5.2) 공용 문구 규칙 — 순수 함수라 JVM 단위 테스트로 검증한다.
 * 문구는 화면에만 쓰고 로그에는 남기지 않는다(닉네임 포함 — CLAUDE.md 규칙 8).
 */
object DriveFormat {
    const val DASH = "—"

    /** 내 차 기준 간격: 방향+숫자([text], 크게)와 단위([unit], 작게). 값 없으면 ("—", "") */
    data class Gap(val text: String, val unit: String) {
        /** 한 줄 문구 — "앞 1.2 km" */
        val full: String get() = if (unit.isEmpty()) text else "$text $unit"
    }

    /** 이보다 가까우면(|간격| < 50 m) 숫자 대신 "바로 옆" — 같은 주차장 출발·붙어 달리기에서 "0 m" 가 고장처럼 보였다(4-2) */
    const val NEAR_M = 50
    const val NEAR_TEXT = "바로 옆"

    /** 도착 예상 시각을 모를 때 패널 숫자 자리 — "—" 는 '도착함'으로 읽혔다(4-3) */
    const val NO_CLOCK = "--:--"

    /**
     * [gapM] = 상대.distanceToLeaderM − 나.distanceToLeaderM. 음수 = 상대가 앞("앞"), 양수 = 뒤("뒤").
     * |간격| < [NEAR_M] 이면 "바로 옆"(단위 없음). 1 km 미만은 10 m 단위 반올림 m, 10 km 미만은 소수 1자리 km,
     * 그 이상은 정수 km(패널 폭 22% 안에 "뒤 28 km" 가 잘리지 않게 — 2026-10-28 실측 "뒤 28.5 km" 의 단위가 잘림).
     */
    fun gap(gapM: Int?): Gap {
        if (gapM == null) return Gap(DASH, "")
        val a = abs(gapM)
        if (a < NEAR_M) return Gap(NEAR_TEXT, "")
        val dir = when {
            gapM < 0 -> "앞 "
            gapM > 0 -> "뒤 "
            else -> ""
        }
        return if (a < 995) Gap("$dir${(a + 5) / 10 * 10}", "m") else Gap("$dir${km(a, decimalsBelowKm = 10)}", "km")
    }

    /** 거리 한 덩어리 — "350 m" / "9.5 km" / "144 km" (10 km 이상은 정수) */
    fun distance(meters: Int): String = if (meters < 995) "${(meters + 5) / 10 * 10} m" else "${km(meters, decimalsBelowKm = 10)} km"

    private fun km(meters: Int, decimalsBelowKm: Int): String {
        val v = meters / 1_000.0
        return if (v < decimalsBelowKm - 0.05) String.format(Locale.US, "%.1f", v) else v.roundToInt().toString()
    }

    private val HH_MM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

    /** 도착 예상 시각(epoch ms) → 기기 시간대 "13:12". 없으면 "—" */
    fun clock(epochMs: Long?, zone: ZoneId): String = epochMs?.let { HH_MM.format(Instant.ofEpochMilli(it).atZone(zone)) } ?: DASH

    /** 패널용 도착 시각 — 모르면 "--:--"([NO_CLOCK]). 머리줄 "경포해변 도착" 옆의 "—" 가 '이미 도착'으로 읽혀서 바꿨다 */
    fun panelClock(epochMs: Long?, zone: ZoneId): String = epochMs?.let { HH_MM.format(Instant.ofEpochMilli(it).atZone(zone)) } ?: NO_CLOCK

    /** 경과·남은 시간 → "1시간 42분" / "9분" */
    fun duration(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        return if (s >= 3_600) "${s / 3_600}시간 ${"%02d".format((s % 3_600) / 60)}분" else "${s / 60}분"
    }

    /** "9.5 km · 9분" — 아는 것만 */
    fun remain(remainM: Int?, remainS: Int?): String =
        listOfNotNull(remainM?.let(::distance), remainS?.let { duration(it.toLong()) }).joinToString(" · ")

    /** "3초 전" / "2분 전" / "1시간 전" */
    fun ago(ms: Long): String {
        val s = (ms / 1_000).coerceAtLeast(0)
        return when {
            s < 60 -> "${s}초 전"
            s < 3_600 -> "${s / 60}분 전"
            else -> "${s / 3_600}시간 전"
        }
    }

    /** 패널 이름 열은 좁다 — 닉네임 끝의 " 차" 를 뗀다("아빠 차" → "아빠") */
    fun panelName(nickname: String): String = nickname.trim().removeSuffix(" 차").trim().ifEmpty { nickname }

    /** 이벤트 문구 — 서버 음성 문구, 없으면 종류 라벨 */
    fun eventText(type: String, speech: String?): String = speech?.trim()?.takeIf { it.isNotEmpty() } ?: EventsViewModel.labelOf(type)

    /** 이벤트 톤(색+아이콘): 급제동·에어백 = 위험, 저잔량·연결 끊김·경로 이탈 = 주의, 정차·재출발 = 중립(초록 '완료'로 읽히지 않게, 4-4), 그 밖 = 정보 */
    fun eventTone(type: String): Tone = when (type) {
        "HARD_BRAKE", "AIRBAG_DEPLOYED" -> Tone.DANGER
        "LOW_ENERGY", "MEMBER_OFFLINE", "DEVIATION" -> Tone.CAUTION
        "STOPPED", "RESUMED" -> Tone.NEUTRAL
        else -> Tone.INFO
    }
}
