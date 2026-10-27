package app.donghaeng.companion.demo.donghaeng

import androidx.compose.ui.graphics.Color
import app.donghaeng.protocol.Energy
import app.donghaeng.protocol.GroupEventType
import app.donghaeng.protocol.MemberStatus

/* 동행 UI 공용 표기 도우미 (ETA·잔량·상태·이벤트 이름·색) */

fun parseHexColor(hex: String): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color.Gray)

fun statusLabel(status: MemberStatus): String = when (status) {
    MemberStatus.DRIVING -> "주행 중"
    MemberStatus.STOPPED -> "정차"
    MemberStatus.OFFLINE -> "오프라인"
    MemberStatus.ARRIVED -> "도착"
}

fun energyLabel(energy: Energy?): String? = when {
    energy == null -> null
    energy.socPct != null -> "배터리 ${energy.socPct}%"
    energy.fuelPct != null -> "연료 ${energy.fuelPct}%"
    else -> null
}

fun energyPct(energy: Energy?): Int? = energy?.socPct ?: energy?.fuelPct

fun etaLabel(remainS: Int?): String? {
    if (remainS == null) return null
    val minutes = (remainS + 59) / 60
    return when {
        minutes <= 0 -> "도착"
        minutes >= 60 -> "${minutes / 60}시간 ${minutes % 60}분"
        else -> "${minutes}분"
    }
}

fun distanceLabel(meters: Int?): String? = when {
    meters == null -> null
    meters >= 1000 -> String.format("%.1fkm", meters / 1000.0)
    else -> "${meters}m"
}

fun eventLabel(type: String): String = when (GroupEventType.entries.firstOrNull { it.name == type }) {
    GroupEventType.LOW_ENERGY -> "잔량 부족"
    GroupEventType.HARD_BRAKE -> "급제동"
    GroupEventType.HAZARD_ON -> "비상등"
    GroupEventType.AIRBAG_DEPLOYED -> "에어백"
    GroupEventType.ROUTE_CHANGED -> "경로 변경"
    GroupEventType.WAYPOINT_ARRIVED -> "경유지 도착"
    GroupEventType.DESTINATION_ARRIVED -> "목적지 도착"
    GroupEventType.STOPPED -> "정차"
    GroupEventType.RESUMED -> "재출발"
    GroupEventType.PRESET_MESSAGE -> "프리셋"
    GroupEventType.MEMBER_JOINED -> "합류"
    GroupEventType.MEMBER_LEFT -> "이탈"
    GroupEventType.LEADER_CHANGED -> "선두 변경"
    GroupEventType.MEMBER_OFFLINE -> "오프라인"
    GroupEventType.REST_STOP_PROPOSAL -> "휴게소 제안"
    GroupEventType.REST_STOP_VOTE_RESULT -> "투표 결과"
    GroupEventType.DEVIATION -> "경로 이탈"
    GroupEventType.PASSENGER_MESSAGE -> "동승자"
    GroupEventType.GROUP_ENDED -> "그룹 종료"
    null -> type
}

/** "방금", "3분 전" */
fun relativeTime(atMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val s = ((nowMs - atMs) / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "방금"
        s < 3600 -> "${s / 60}분 전"
        s < 86400 -> "${s / 3600}시간 전"
        else -> "${s / 86400}일 전"
    }
}
