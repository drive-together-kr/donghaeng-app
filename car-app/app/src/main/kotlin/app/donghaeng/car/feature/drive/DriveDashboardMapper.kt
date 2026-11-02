package app.donghaeng.car.feature.drive

import app.donghaeng.car.core.common.Geo
import app.donghaeng.car.feature.groupdrive.ConnState
import app.donghaeng.car.feature.groupdrive.GroupDriveUiState
import app.donghaeng.car.feature.groupdrive.MemberUi
import app.donghaeng.car.surface.FloatingPanelMapper
import app.donghaeng.car.surface.SurfaceEvents
import app.donghaeng.car.surface.SurfaceMode
import app.donghaeng.protocol.MemberStatus
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * 그룹 주행 상태 → 정차 대시보드(docs/05 §5.1) 화면 모델 — 순수 함수. 세션이 없으면 null(호출 쪽이 "끝났어요" 패널).
 *
 * - 대열 막대: 위치(선두까지 거리)를 아는 차만. offsetKm = (최후미 distanceToLeaderM − 그 차 값)/1000, 대열 길이 = 최후미 distanceToLeaderM.
 *   간격 경고 = 바로 앞차와 3 km 초과. 다음 정차지 = RouteSyncEngine 이 마지막으로 수락·발행한 경유지 — 막대 위치는 내 위치 + 직선거리(모르면 null → 선두 + 2 km).
 * - 그룹 도착 = 멤버 eta.destTs 최댓값과 그 차(연결 끊김·도착한 차는 뺀다 — 옛 ETA 로 그룹 도착이 늦게·빠르게 보이지 않게), 내 도착 = 내 eta.
 *   둘 다 모르면(순정 내비 안내 전) [ArrivalCardUi.noEtaPlace] = 목적지 이름 → 카드가 빈 상태 안내 + 정차 중 [목적지 안내 시작].
 * - 간격이 50 m 미만이면 "바로 옆", 대열 전체가 50 m 안이면 "모두 함께"(같은 주차장 출발에서 "0 m" 가 고장처럼 보였다, 4-2).
 * - 실시간 연결이 끊긴 채 그룹 상태가 15초 넘게 오래되면 [DriveDashboardUi.staleText]("2분 전") — 화면이 흐리게·"2분 전 정보"(E038).
 * - 다음 정차 카드는 경유지가 있을 때만(이름·내 위치에서 직선거리, 차별 ETA·주유/충전·도달 가능 여부는 아직 모름 → 생략·"—").
 * - 내 주행 줄: 제한속도 = 내비, 연속 주행 = [ContinuousDriveTimer](주행 구간 합, 15분 이상 쉬면 0, 2시간 이상 경고), 휴식 추천 = 경유지.
 * - 그룹 도착의 마지막 차가 나면 "마지막: 내 차".
 * - 이벤트 띠·지난 소식은 플로팅 패널과 같은 규칙([SurfaceEvents]): 내 차 출처 제외, 띠 = 받은 지 10초 안 + 서버 시계로 60초 안에 생긴 가장 최근 이벤트(없으면 숨김 —
 *   재접속 때 브로커가 다시 보낸 옛 이벤트는 빠진다), 지난 소식 = 발생 시각(봉투 ts) 최신 5개, "N초 전" = 서버 시계 − 봉투 ts. 프리셋(F8)은 아직 없어 비운다.
 *   띠의 위험 이벤트 강조(빨간 강조 + 깜빡임)는 패널과 같은 [app.donghaeng.car.surface.EventHighlightPolicy] · 같은 음성 값(`voiceCue`).
 * - 무전(F16, [ptt]): 상태 = `SurfaceState.ptt`(패널과 같은 값) 그대로, 보내지 않은 무전·지난 무전 = [PttDisplay.unsentRows]·[PttDisplay.pastRows]
 *   ("N분 전" 도 서버 시계), "핸들 버튼으로도 가능" = 설정 '핸들 버튼으로 무전'.
 * - 하단 지연 = T31 문구 그대로("이벤트 N ms · 상태 N ms", 표시 꺼짐이면 없음).
 * - 내 차 상태(5-3) = 스냅샷의 내 잔량(EV 배터리 우선, 없으면 연료).
 */
object DriveDashboardMapper {
    const val EVENT_WINDOW_MS = SurfaceEvents.BANNER_WINDOW_MS
    const val RECENT_COUNT = 5
    const val LONG_DRIVE_S = 2 * 3_600L
    const val WARN_GAP_M = 3_000
    /** 연결이 끊긴 채 이보다 오래된 그룹 상태는 '지난 정보'로 표시(E038) */
    const val STALE_AFTER_MS = 15_000L

    fun map(s: GroupDriveUiState, zone: ZoneId = ZoneId.systemDefault(), ptt: PttDashboardInput = PttDashboardInput()): DriveDashboardUi? {
        val session = s.session ?: return null
        val now = s.nowMs
        val me = s.me
        val placed = s.members.filter { it.distanceToLeaderM != null }
        val rearM = placed.maxOfOrNull { it.distanceToLeaderM!! } ?: 0
        fun offsetKm(m: MemberUi) = (rearM - m.distanceToLeaderM!!) / 1_000f
        val sorted = placed.sortedBy { offsetKm(it) }
        val cars = sorted.mapIndexed { i, m ->
            val ahead = sorted.getOrNull(i + 1)
            FormationCar(
                name = m.nickname,
                colorArgb = m.colorArgb,
                offsetKm = offsetKm(m),
                gapToMeText = if (m.isMe) null else gapToMe(m, me),
                isMe = m.isMe,
                isLeader = m.isLeader,
                lowEnergy = lowEnergy(m),
                gapWarn = ahead != null && (offsetKm(ahead) - offsetKm(m)) * 1_000 > WARN_GAP_M,
                offline = !m.online || m.status == MemberStatus.OFFLINE,
            )
        }

        val stop = s.nextStop
        val myLat = me?.lat ?: s.myLat
        val myLon = me?.lon ?: s.myLon
        val stopDistM = if (stop != null && myLat != null && myLon != null) Geo.distanceM(myLat, myLon, stop.lat, stop.lon).roundToInt() else null
        val myOffsetKm = me?.distanceToLeaderM?.let { (rearM - it) / 1_000f }
        val formation = FormationBarUi(
            cars = cars,
            nextStopName = stop?.name,
            nextStopOffsetKm = if (stopDistM != null && myOffsetKm != null) myOffsetKm + stopDistM / 1_000f else null,
            destinationText = listOfNotNull(session.destination.name.takeIf { it.isNotBlank() }, me?.remainM?.let(DriveFormat::distance)).joinToString(" "),
            formationLengthText = when {
                placed.size < 2 -> "대열 길이 " + DriveFormat.DASH
                rearM < DriveFormat.NEAR_M -> "모두 함께"
                else -> "대열 길이 " + DriveFormat.distance(rearM)
            },
        )

        val last = arrivalCandidates(s.members).maxByOrNull { it.etaDestTs!! }
        val arrival = ArrivalCardUi(
            groupArrivalText = DriveFormat.clock(last?.etaDestTs, zone),
            lastCarText = last?.let { "마지막: " + if (it.isMe) "내 차" else it.nickname } ?: "",
            myArrivalText = DriveFormat.clock(me?.etaDestTs, zone),
            myRemainText = DriveFormat.remain(me?.remainM, me?.remainS),
            noEtaPlace = if (last == null && me?.etaDestTs == null) session.destination.name.ifBlank { null } else null,
        )

        val nextStop = stop?.let {
            NextStopCardUi(
                name = it.name,
                distanceText = stopDistM?.let(DriveFormat::distance) ?: DriveFormat.DASH,
                perCar = emptyList(),
                fuelAvailable = false,
                chargeAvailable = false,
                reachable = null,
                reachableText = DriveFormat.DASH,
            )
        }

        val drivenS = s.continuousDriveMs?.let { it / 1_000 }
        val myDrive = MyDriveLineUi(
            speedLimitKph = s.speedLimitKph,
            nextTurnText = null,
            drivingTimeText = "연속 주행 " + (drivenS?.let(DriveFormat::duration) ?: DriveFormat.DASH),
            drivingTimeWarn = drivenS != null && drivenS >= LONG_DRIVE_S,
            restSuggestionText = stop?.let { "다음 휴식 추천: ${it.name}" },
            myEnergyPct = me?.socPct ?: me?.fuelPct,
            myEnergyEv = me?.socPct != null,
        )

        val serverNow = s.serverNowMs ?: now
        val myId = s.myVehicleId ?: me?.vehicleId
        val event = SurfaceEvents.bannerUi(s.recentEvents, myId, now, serverNow, s.voiceCue)
        val recent = SurfaceEvents.recent(s.recentEvents, myId, RECENT_COUNT).map {
            RecentEventUi(DriveFormat.eventText(it.event.type, it.event.speech), DriveFormat.eventTone(it.event.type), DriveFormat.ago(SurfaceEvents.ageMs(it, serverNow)), type = it.event.type)
        }

        return DriveDashboardUi(
            groupName = session.name,
            formation = formation,
            arrival = arrival,
            nextStop = nextStop,
            myDrive = myDrive,
            event = event,
            ptt = ptt.ptt,
            connected = s.connection == ConnState.CONNECTED,
            offline = s.connection == ConnState.OFFLINE,
            staleText = staleText(s),
            latencyMs = s.latencyMs,
            latencyText = s.latencyText,
            // 동행 화면이 앞에 있으면 순정 내비는 뒤에 있다 → 제한속도·연속 주행·휴식 추천을 이 화면이 보여 준다
            naviVisible = false,
            parked = !s.isDriving,
            presets = emptyList(),
            recentEvents = recent,
            unsentPtt = PttDisplay.unsentRows(ptt.unsent, zone),
            pastPtt = PttDisplay.pastRows(ptt.past, serverNow),
            pttKeyHint = ptt.handleButton,
            overlayNotice = s.surfaceMode == SurfaceMode.CARDS_ONLY,
        )
    }

    /** 그룹 도착 후보 — ETA 가 있고, 연결돼 있고, 아직 도착하지 않은 차 */
    fun arrivalCandidates(members: List<MemberUi>): List<MemberUi> =
        members.filter { it.etaDestTs != null && it.online && it.status != MemberStatus.OFFLINE && it.status != MemberStatus.ARRIVED }

    /** 연결이 끊긴 채 그룹 상태가 [STALE_AFTER_MS] 넘게 오래됐으면 "2분 전", 아니면 null(E038) */
    fun staleText(s: GroupDriveUiState): String? {
        if (s.connection == ConnState.CONNECTED) return null
        val ageS = s.snapshotAgeS ?: return null
        return if (ageS * 1_000 >= STALE_AFTER_MS) DriveFormat.ago(ageS * 1_000) else null
    }

    private fun gapToMe(m: MemberUi, me: MemberUi?): String? {
        val o = m.distanceToLeaderM ?: return null
        val mine = me?.distanceToLeaderM ?: return null
        return DriveFormat.gap(o - mine).full
    }

    private fun lowEnergy(m: MemberUi): Boolean =
        listOfNotNull(m.fuelPct, m.socPct).minOrNull()?.let { it <= FloatingPanelMapper.LOW_ENERGY_PCT } ?: false
}
