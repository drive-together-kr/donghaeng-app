package app.donghaeng.car.feature.drive

/**
 * 주행 대시보드(재설계안, docs/05 §5.1) 의 화면 전용 모델 — 문구는 ViewModel/매퍼가 만들어 넣는다(여기엔 로직 없음).
 *
 * 원칙(사용자 지침 2026-10-28): 주행 중엔 스크롤·탭 없이 전부 보이고, 핵심 숫자 28sp↑·보조 18sp↑, 정보 덩어리 최대 6개,
 * 색+아이콘 이중 표기(빨강=위험, 주황=주의, 초록=정보), 주행 중 버튼은 [무전]·[내비 보기] 둘뿐.
 */

/** 색약 대응을 위해 색과 아이콘을 함께 결정하는 톤 */
enum class Tone { DANGER, CAUTION, INFO, NEUTRAL }

/** 대열 막대의 차 한 대 */
data class FormationCar(
    val name: String,
    val colorArgb: Long,
    /** 경로 진행률 0..1 (선두가 가장 크다) */
    val progress: Float,
    /** "+1.2 km / +2분" — 선두와의 간격, 선두면 null */
    val gapText: String? = null,
    val isMe: Boolean = false,
    val isLeader: Boolean = false,
    val lowEnergy: Boolean = false,
    /** 선두와 벌어짐(주황 구간) */
    val gapWarn: Boolean = false,
    val offline: Boolean = false,
)

data class FormationBarUi(
    val cars: List<FormationCar>,
    val destinationName: String,
    /** "남은 144 km" 같은 보조 문구 */
    val routeText: String,
)

data class ArrivalCardUi(
    /** "14:35" */
    val groupArrivalText: String,
    /** "마지막: 둘째 차" */
    val lastCarText: String,
    /** "14:28" */
    val myArrivalText: String,
    /** "9.5 km · 9분" */
    val myRemainText: String,
)

data class NextStopCarEta(val name: String, val colorArgb: Long, val etaText: String)

data class NextStopCardUi(
    val name: String,
    /** "18 km" */
    val distanceText: String,
    val perCar: List<NextStopCarEta>,
    val fuelAvailable: Boolean,
    val chargeAvailable: Boolean,
    /** null = 계산 불가 */
    val reachable: Boolean?,
    /** "내 연료로 도달 가능 (잔량 38% · 약 210 km)" */
    val reachableText: String,
)

enum class TurnKind { STRAIGHT, LEFT, RIGHT, UTURN, NONE }

data class MyDriveLineUi(
    val speedLimitKph: Int?,
    /** "300 m 우회전" */
    val nextTurnText: String?,
    val nextTurnKind: TurnKind = TurnKind.NONE,
    /** "연속 주행 1시간 42분" */
    val drivingTimeText: String,
    /** 2시간 초과 → 주황 + 휴식 권장 */
    val drivingTimeWarn: Boolean = false,
)

data class EventBannerUi(val text: String, val tone: Tone, val ageS: Int)

enum class PttPhase { IDLE, RECORDING, CONFIRMING, SENT, PLAYING }

data class PttUi(
    val phase: PttPhase = PttPhase.IDLE,
    /** 녹음 남은 초 / 확인 대기 남은 초 / 재생 경과 초 */
    val seconds: Int = 0,
    /** 0..1 입력 레벨(파형) */
    val level: Float = 0f,
    /** 수신 재생 시 보낸 차 */
    val fromName: String? = null,
    val fromColorArgb: Long? = null,
    /** 확인 대기 중 마이크 열림 */
    val listening: Boolean = false,
    /** 전송된 차 수 */
    val sentTo: Int = 0,
    /** STT 불가 → 카운트다운 전송 안내 */
    val fallbackCountdown: Boolean = false,
)

data class RecentEventUi(val text: String, val tone: Tone, val whenText: String)

data class DriveDashboardUi(
    val groupName: String,
    val formation: FormationBarUi,
    val arrival: ArrivalCardUi,
    val nextStop: NextStopCardUi?,
    val myDrive: MyDriveLineUi,
    val event: EventBannerUi?,
    val ptt: PttUi = PttUi(),
    val connected: Boolean = true,
    val latencyMs: Int? = null,
    /** 정차(P) — 추가 블록(프리셋·이벤트 목록·보내지 않은 무전·그룹 관리·설정) 표시 */
    val parked: Boolean = false,
    val presets: List<String> = emptyList(),
    val recentEvents: List<RecentEventUi> = emptyList(),
    val unsentPtt: List<String> = emptyList(),
)
