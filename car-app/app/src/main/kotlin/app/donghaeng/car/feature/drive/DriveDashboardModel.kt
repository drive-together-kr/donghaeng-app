package app.donghaeng.car.feature.drive

/**
 * 주행 대시보드(재설계안, docs/05 §5.1) 의 화면 전용 모델 — 문구는 [DriveDashboardMapper] 가 만들어 넣는다(여기엔 로직 없음).
 * 방향 재정정(2026-10-28): 주행 중 앞 화면은 순정 내비이고, 이 화면은 그룹 생성·합류·**정차 중**에 쓴다(주행 중 정보는 플로팅 패널·알림 카드·음성).
 *
 * 원칙(사용자 지침 2026-10-28): 주행 중엔 스크롤·탭 없이 전부 보이고, 핵심 숫자 72sp↑·보조 22sp↑, 정보 덩어리 최대 6개,
 * 색+아이콘 이중 표기(빨강=위험, 주황=주의, 초록=정보), 주행 중 버튼은 [무전]·[내비 보기] 둘뿐.
 */

/** 색약 대응을 위해 색과 아이콘을 함께 결정하는 톤 */
enum class Tone { DANGER, CAUTION, INFO, NEUTRAL }

/** 대열 막대의 차 한 대 */
data class FormationCar(
    val name: String,
    val colorArgb: Long,
    /** 대열 최후미 차를 0 으로 둔 경로상 위치(km) */
    val offsetKm: Float,
    /** 내 차 기준 간격 — 앞차 "앞 1.2 km", 뒤차 "뒤 2.1 km", 내 차는 null */
    val gapToMeText: String? = null,
    val isMe: Boolean = false,
    val isLeader: Boolean = false,
    val lowEnergy: Boolean = false,
    /** 이 차와 바로 앞차 사이가 벌어짐(주황 구간) */
    val gapWarn: Boolean = false,
    val offline: Boolean = false,
)

/** 축척 = 최후미 차 ~ 다음 정차지, 양끝 여백 10% */
data class FormationBarUi(
    val cars: List<FormationCar>,
    /** 다음 정차지(휴게소) 이름과 최후미 차 기준 위치(km). null 이면 선두 + 2 km 를 끝으로 쓴다 */
    val nextStopName: String?,
    val nextStopOffsetKm: Float?,
    /** 오른쪽 끝 글자 — "경포해변 144 km" */
    val destinationText: String,
    /** 카드 오른쪽 위 — "대열 길이 6.9 km" */
    val formationLengthText: String,
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

/** 휴게소 도착 예상 — 이름+시각, 가장 늦은 차는 굵게 */
data class NextStopCarEta(val name: String, val colorArgb: Long, val etaText: String, val latest: Boolean = false)

data class NextStopCardUi(
    val name: String,
    /** "18 km" */
    val distanceText: String,
    val perCar: List<NextStopCarEta>,
    val fuelAvailable: Boolean,
    val chargeAvailable: Boolean,
    /** null = 계산 불가 */
    val reachable: Boolean?,
    /** "내 연료로 도달 가능 · 잔량 38% ≈ 210 km" */
    val reachableText: String,
)

enum class TurnKind { STRAIGHT, LEFT, RIGHT, UTURN, NONE }

data class MyDriveLineUi(
    val speedLimitKph: Int?,
    /** "300 m 앞 우측 출구" */
    val nextTurnText: String?,
    val nextTurnKind: TurnKind = TurnKind.NONE,
    /** "연속 주행 1시간 42분" */
    val drivingTimeText: String,
    /** 2시간 초과 → 주황 + 휴식 권장 */
    val drivingTimeWarn: Boolean = false,
    /** "다음 휴식 추천: 덕평휴게소" — 순정 내비가 같은 화면에 보일 때 제한속도·회전 대신 크게 */
    val restSuggestionText: String? = null,
)

/**
 * 위험 이벤트(급제동·에어백) 강조 — `surface.EventHighlightPolicy`(docs/05 §5.2).
 * [BLINK] = 음성이 3초 안에 시작되지 않았다(또는 지금 경로가 늦을 게 분명) → 빨간 강조 + 깜빡임을 먼저,
 * [STEADY] = 강조 중에 음성이 시작됨 → 깜빡임만 멈추고 강조를 잠시(3초) 유지.
 */
enum class EventHighlight { NONE, BLINK, STEADY }

data class EventBannerUi(val text: String, val tone: Tone, val ageS: Int, val highlight: EventHighlight = EventHighlight.NONE)

enum class PttPhase { IDLE, RECORDING, CONFIRMING, SENT, PLAYING }

data class PttUi(
    val phase: PttPhase = PttPhase.IDLE,
    /** 녹음 남은 초 / 확인 대기 남은 초 / 재생 경과 초 */
    val seconds: Int = 0,
    /** 원형 카운트다운 분모(확인 대기 5 s, 녹음 15 s) */
    val totalSeconds: Int = 5,
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
    /** 재생 길이 "0:07" */
    val durationText: String? = null,
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
    /** T31 지연 문구("이벤트 12 ms · 상태 30 ms") — 있으면 [latencyMs] 대신 쓴다 */
    val latencyText: String? = null,
    /** 순정 내비가 같은 화면(2분할)에 보이는가 — 보이면 제한속도·회전 줄을 숨기고 휴식 추천 줄을 쓴다 */
    val naviVisible: Boolean = true,
    /** 정차(P) — 추가 블록(프리셋·이벤트 목록·보내지 않은 무전·그룹 관리·설정) 표시 */
    val parked: Boolean = false,
    val presets: List<String> = emptyList(),
    val recentEvents: List<RecentEventUi> = emptyList(),
    val unsentPtt: List<String> = emptyList(),
    /** 플로팅 창 권한이 없어 '알림 카드 + 음성' 모드 — 상단 안내 1줄(docs/05 §5.2) */
    val overlayNotice: Boolean = false,
)
