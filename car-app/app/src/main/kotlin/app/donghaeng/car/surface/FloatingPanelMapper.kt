package app.donghaeng.car.surface

import app.donghaeng.car.domain.session.GroupSession
import app.donghaeng.car.domain.session.ReceivedGroupEvent
import app.donghaeng.car.domain.voice.VoiceCue
import app.donghaeng.car.feature.drive.DriveFormat
import app.donghaeng.car.feature.drive.EventBannerUi
import app.donghaeng.car.feature.drive.FloatingCar
import app.donghaeng.car.feature.drive.FloatingPanelUi
import app.donghaeng.car.feature.drive.PttUi
import app.donghaeng.car.feature.groupdrive.GroupDriveViewModel
import app.donghaeng.protocol.Energy
import app.donghaeng.protocol.GroupState
import app.donghaeng.protocol.MemberState
import app.donghaeng.protocol.MemberStatus
import java.time.ZoneId
import kotlin.math.abs

/**
 * 그룹 스냅샷 → 플로팅 패널 화면 모델 (docs/05 §5.2) — 순수 함수.
 *
 * - 다른 차만, 선두부터(`distanceToLeaderM` 오름차순, 모르면 뒤로). 최대 [MAX_ROWS]대, 나머지는 "외 N대".
 * - 간격 = 상대.distanceToLeaderM − 나.distanceToLeaderM → 음수 "앞", 양수 "뒤"(1 km 미만 m), 어느 쪽이든 모르면 "—".
 * - 도착 시각 = `eta.destTs` 를 기기 시간대 HH:mm(없으면 "—"), 기준 문구 = "{그룹 목적지} 도착"(`etaPlace` = 목적지 이름, 머리줄 배치는 FloatingPanel).
 * - 머리글 = 그룹 이름(`GroupSession.name` 그대로 — 폭이 모자라면 화면에서 그룹 이름만 말줄임).
 * - 저잔량 = min(연료%, 배터리%) ≤ 15, 경고 = |간격| > 3 km 또는 연결 끊김, 연결 끊김 = !online 또는 OFFLINE.
 * - 이벤트 = [SurfaceEvents.banner]: 받은 지 10초 안 + 서버 시계로 60초 안에 생긴 것 중 가장 최근, 내 차가 출처인 이벤트는 제외(재접속 때 밀려 온 옛 이벤트는 빠진다).
 *   나이는 서버 시계 − 봉투 ts. 톤: 급제동·에어백 위험 / 저잔량·끊김·이탈 주의 / 그 밖 정보.
 *   위험 이벤트는 음성 시작이 3초를 넘기면(또는 늦을 게 분명하면) 빨간 강조 + 깜빡임([EventHighlightPolicy], `voiceCue`).
 * - 도착 시각을 모르면 "--:--"(머리줄 "경포해변 도착" 옆의 "—" 가 '이미 도착'으로 읽혔다, 4-3). 간격이 50 m 미만이면 "바로 옆".
 * - 실시간 연결이 끊긴 채 그룹 상태가 [STALE_AFTER_MS] 넘게 오래되면 [FloatingPanelUi.staleText]("2분 전" — 머리줄 "연결 끊김 · 2분 전", 차 줄 흐리게),
 *   [STALE_DROP_MS] 넘으면 간격 숫자를 "—" 로(지금 값처럼 믿지 않게, E038). 도착 시각은 절대 시각이라 남긴다.
 */
object FloatingPanelMapper {
    const val MAX_ROWS = 4
    const val EVENT_WINDOW_MS = SurfaceEvents.BANNER_WINDOW_MS
    const val WARN_GAP_M = 3_000
    const val LOW_ENERGY_PCT = 15
    const val STALE_AFTER_MS = 15_000L
    const val STALE_DROP_MS = 60_000L

    fun map(
        session: GroupSession,
        state: GroupState?,
        myVehicleId: String?,
        events: List<ReceivedGroupEvent>,
        ptt: PttUi,
        connected: Boolean,
        nowMs: Long,
        zone: ZoneId,
        /** 서버 시계 기준 지금(ClockSync.serverNow) — 이벤트 신선도·나이. 기본 = [nowMs] */
        serverNowMs: Long = nowMs,
        /** 음성 경로·지연·시작 신호 — 위험 이벤트 강조([EventHighlightPolicy]). null = 강조 없음 */
        voiceCue: VoiceCue? = null,
        /** 마지막 그룹 상태를 받은 뒤 지난 시간(ms). null = 모름(오래됨 표시 없음) */
        stateAgeMs: Long? = null,
    ): FloatingPanelUi {
        val members = state?.members.orEmpty()
        val me = members.firstOrNull { it.vehicleId == myVehicleId }
        val stale = !connected && stateAgeMs != null && stateAgeMs >= STALE_AFTER_MS
        val dropGap = stale && stateAgeMs!! >= STALE_DROP_MS
        val others = members
            .filter { it.vehicleId != myVehicleId }
            .sortedWith(compareBy(nullsLast()) { it.distanceToLeaderM })
            .map { car(it, me, zone) }
            .map { if (dropGap) it.copy(gapText = DriveFormat.DASH, gapUnit = "") else it }
        return FloatingPanelUi(
            groupName = session.name,
            cars = others.take(MAX_ROWS),
            moreCount = (others.size - MAX_ROWS).coerceAtLeast(0),
            event = recentEvent(events, myVehicleId, nowMs, serverNowMs, voiceCue),
            ptt = ptt,
            connected = connected,
            etaPlace = session.destination.name,
            staleText = if (stale) DriveFormat.ago(stateAgeMs!!) else null,
        )
    }

    /** 상대 − 나 (m). 둘 중 하나라도 모르면 null */
    fun gapM(other: MemberState, me: MemberState?): Int? {
        val o = other.distanceToLeaderM ?: return null
        val m = me?.distanceToLeaderM ?: return null
        return o - m
    }

    fun isOffline(m: MemberState): Boolean = !m.online || m.status == MemberStatus.OFFLINE

    fun isLowEnergy(e: Energy?): Boolean = listOfNotNull(e?.fuelPct, e?.socPct).minOrNull()?.let { it <= LOW_ENERGY_PCT } ?: false

    private fun car(m: MemberState, me: MemberState?, zone: ZoneId): FloatingCar {
        val gap = gapM(m, me)
        val g = DriveFormat.gap(gap)
        val offline = isOffline(m)
        return FloatingCar(
            name = DriveFormat.panelName(m.nickname),
            colorArgb = GroupDriveViewModel.parseColor(m.color),
            gapText = g.text,
            gapUnit = g.unit,
            etaText = DriveFormat.panelClock(m.eta?.destTs, zone),
            lowEnergy = isLowEnergy(m.energy),
            warn = offline || (gap != null && abs(gap) > WARN_GAP_M),
            offline = offline,
        )
    }

    /** 소식 줄 — [SurfaceEvents.bannerUi] (10초 안·서버 시계 60초 안·내 차 출처 제외, 위험 이벤트 강조) */
    fun recentEvent(events: List<ReceivedGroupEvent>, myVehicleId: String?, nowMs: Long, serverNowMs: Long = nowMs, voiceCue: VoiceCue? = null): EventBannerUi? =
        SurfaceEvents.bannerUi(events, myVehicleId, nowMs, serverNowMs, voiceCue)
}
