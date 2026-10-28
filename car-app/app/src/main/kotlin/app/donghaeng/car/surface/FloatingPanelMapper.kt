package app.donghaeng.car.surface

import app.donghaeng.car.domain.session.GroupSession
import app.donghaeng.car.domain.session.ReceivedGroupEvent
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
 * - 도착 시각 = `eta.destTs` 를 기기 시간대 HH:mm(없으면 "—"), 기준 문구 = "{그룹 목적지} 도착".
 * - 저잔량 = min(연료%, 배터리%) ≤ 15, 경고 = |간격| > 3 km 또는 연결 끊김, 연결 끊김 = !online 또는 OFFLINE.
 * - 이벤트 = [EVENT_WINDOW_MS](10초) 안에 받은 것 중 가장 최근, 내 차가 출처인 이벤트는 제외. 톤: 급제동·에어백 위험 / 저잔량·끊김·이탈 주의 / 그 밖 정보.
 */
object FloatingPanelMapper {
    const val MAX_ROWS = 4
    const val EVENT_WINDOW_MS = 10_000L
    const val WARN_GAP_M = 3_000
    const val LOW_ENERGY_PCT = 15

    fun map(
        session: GroupSession,
        state: GroupState?,
        myVehicleId: String?,
        events: List<ReceivedGroupEvent>,
        ptt: PttUi,
        connected: Boolean,
        nowMs: Long,
        zone: ZoneId,
    ): FloatingPanelUi {
        val members = state?.members.orEmpty()
        val me = members.firstOrNull { it.vehicleId == myVehicleId }
        val others = members
            .filter { it.vehicleId != myVehicleId }
            .sortedWith(compareBy(nullsLast()) { it.distanceToLeaderM })
            .map { car(it, me, zone) }
        return FloatingPanelUi(
            groupName = session.name,
            cars = others.take(MAX_ROWS),
            moreCount = (others.size - MAX_ROWS).coerceAtLeast(0),
            event = recentEvent(events, myVehicleId, nowMs),
            ptt = ptt,
            connected = connected,
            etaLabel = "${session.destination.name} 도착",
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
            etaText = DriveFormat.clock(m.eta?.destTs, zone),
            lowEnergy = isLowEnergy(m.energy),
            warn = offline || (gap != null && abs(gap) > WARN_GAP_M),
            offline = offline,
        )
    }

    /** 10초 안의 가장 최근 이벤트(내 차 출처 제외) */
    fun recentEvent(events: List<ReceivedGroupEvent>, myVehicleId: String?, nowMs: Long): EventBannerUi? =
        events
            .filter { nowMs - it.receivedAtMs <= EVENT_WINDOW_MS && (myVehicleId == null || it.event.sourceVehicleId != myVehicleId) }
            .maxByOrNull { it.receivedAtMs }
            ?.let { e ->
                EventBannerUi(
                    text = DriveFormat.eventText(e.event.type, e.event.speech),
                    tone = DriveFormat.eventTone(e.event.type),
                    ageS = ((nowMs - e.receivedAtMs).coerceAtLeast(0) / 1_000).toInt(),
                )
            }
}
