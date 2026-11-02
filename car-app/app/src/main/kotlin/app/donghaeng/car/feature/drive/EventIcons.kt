package app.donghaeng.car.feature.drive

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * 소식 줄·지난 소식·이벤트 피드의 **종류별 아이콘**(4-4). 예전엔 정보 톤이면 모두 초록 체크(완료)라 정차·경로 변경이 '처리 끝'처럼 읽혔다.
 * 정차 = 일시정지, 재출발 = 재생, 경로 변경·경유지 = 길 안내, 도착 = 깃발, 합류 = 사람+, 나감 = 나가기, 메시지 = 말풍선, 무전 = 마이크,
 * 선두 변경 = 별, 위험·주의 = 경고. 모르는 종류는 톤 아이콘. 아이콘은 material-icons-core 에 없는 것만 경로로 직접 그린다(추가 의존성 없음).
 */
enum class EventIconKind { PAUSE, RESUME, ROUTE, FLAG, PERSON_ADD, LEAVE, CHAT, MIC, LEADER, WARNING, INFO }

object EventIcons {

    /** 종류 → 아이콘 종류(순수 — 단위 테스트) */
    fun kindOf(type: String?, tone: Tone): EventIconKind = when (type) {
        "STOPPED" -> EventIconKind.PAUSE
        "RESUMED" -> EventIconKind.RESUME
        "ROUTE_CHANGED", "REST_STOP_PROPOSAL", "REST_STOP_VOTE_RESULT", "DEVIATION" -> if (type == "DEVIATION") EventIconKind.WARNING else EventIconKind.ROUTE
        "WAYPOINT_ARRIVED", "DESTINATION_ARRIVED" -> EventIconKind.FLAG
        "MEMBER_JOINED" -> EventIconKind.PERSON_ADD
        "MEMBER_LEFT", "GROUP_ENDED" -> EventIconKind.LEAVE
        "PASSENGER_MESSAGE", "PRESET_MESSAGE" -> EventIconKind.CHAT
        "VOICE_MESSAGE" -> EventIconKind.MIC
        "LEADER_CHANGED" -> EventIconKind.LEADER
        else -> when (tone) {
            Tone.DANGER, Tone.CAUTION -> EventIconKind.WARNING
            Tone.INFO, Tone.NEUTRAL -> EventIconKind.INFO
        }
    }

    fun of(type: String?, tone: Tone): ImageVector = vectorOf(kindOf(type, tone))

    fun vectorOf(kind: EventIconKind): ImageVector = when (kind) {
        EventIconKind.PAUSE -> pause
        EventIconKind.RESUME -> Icons.Filled.PlayArrow
        EventIconKind.ROUTE -> route
        EventIconKind.FLAG -> flag
        EventIconKind.PERSON_ADD -> personAdd
        EventIconKind.LEAVE -> Icons.AutoMirrored.Filled.ExitToApp
        EventIconKind.CHAT -> chat
        EventIconKind.MIC -> mic
        EventIconKind.LEADER -> Icons.Filled.Star
        EventIconKind.WARNING -> Icons.Filled.Warning
        EventIconKind.INFO -> Icons.Filled.Info
    }

    private fun icon(name: String, path: String): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black))
            .build()

    private val pause by lazy { icon("dh.Pause", "M6,19h4V5H6v14zM14,5v14h4V5h-4z") }
    private val flag by lazy { icon("dh.Flag", "M14.4,6L14,4H5v17h2v-7h5.6l0.4,2h7V6z") }
    private val mic by lazy {
        icon("dh.Mic", "M12,14c1.66,0 2.99,-1.34 2.99,-3L15,5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6c0,1.66 1.34,3 3,3zM17.3,11c0,3 -2.54,5.1 -5.3,5.1S6.7,14 6.7,11L5,11c0,3.41 2.72,6.23 6,6.72L11,21h2v-3.28c3.28,-0.48 6,-3.3 6,-6.72h-1.7z")
    }
    private val chat by lazy {
        icon("dh.Chat", "M20,2H4c-1.1,0 -1.99,0.9 -1.99,2L2,22l4,-4h14c1.1,0 2,-0.9 2,-2V4c0,-1.1 -0.9,-2 -2,-2zM6,9h12v2H6V9zM14,14H6v-2h8v2zM18,8H6V6h12v2z")
    }
    private val route by lazy {
        icon("dh.Route", "M21.71,11.29l-9,-9c-0.39,-0.39 -1.02,-0.39 -1.41,0l-9,9c-0.39,0.39 -0.39,1.02 0,1.41l9,9c0.39,0.39 1.02,0.39 1.41,0l9,-9c0.39,-0.38 0.39,-1.01 0,-1.41zM14,14.5V12h-4v3H8v-4c0,-0.55 0.45,-1 1,-1h5V7.5l3.5,3.5 -3.5,3.5z")
    }
    private val personAdd by lazy {
        icon("dh.PersonAdd", "M15,12c2.21,0 4,-1.79 4,-4s-1.79,-4 -4,-4 -4,1.79 -4,4 1.79,4 4,4zM6,10V7H4v3H1v2h3v3h2v-3h3v-2H6zM15,14c-2.67,0 -8,1.34 -8,4v2h16v-2c0,-2.66 -5.33,-4 -8,-4z")
    }
}
