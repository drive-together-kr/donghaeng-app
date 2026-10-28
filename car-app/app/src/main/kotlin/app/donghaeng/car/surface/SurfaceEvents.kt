package app.donghaeng.car.surface

import app.donghaeng.car.domain.session.ReceivedGroupEvent
import app.donghaeng.car.feature.drive.DriveFormat
import app.donghaeng.car.feature.drive.EventBannerUi

/**
 * 표면(플로팅 패널 소식 줄·정차 대시보드 띠·지난 소식)이 **같은 규칙으로** 이벤트를 고르게 하는 순수 함수 (docs/05 §5.2).
 *
 * - 내 차 출처 제외 — 카드([EventCardPolicy])·음성(VoiceAnnouncer)과 같다(내 차 일은 내가 안다).
 * - '방금 소식'(패널 소식 줄·대시보드 띠) = 기기에서 받은 지 [BANNER_WINDOW_MS](10초) 안 **그리고** 서버 시계로 [FRESHNESS_MS](60초, 카드와 같은 기준) 안에 생긴 이벤트.
 *   HiveMqttGateway 는 세션을 유지(cleanStart=false + sessionExpiry)하므로 터널 등으로 끊겼다 재접속하면 브로커에 쌓인 QoS1 옛 이벤트가 한꺼번에
 *   '지금' 수신된다(receivedAt = 지금). 이들은 봉투 ts 가 오래돼 여기서 빠진다 — 카드(60 s)·음성(5 s)이 거르는 이벤트를 패널·띠가 '방금'으로 띄우지 않게.
 * - 나이("N초 전") = 서버 시계 지금 − 봉투 ts(발생 시각). 시계 동기화 전이면 serverNow = 로컬 시계(오차는 수 초).
 * - 순서 = 발생 시각(봉투 ts) 최신 먼저 — 재접속 때 한꺼번에 들어와도 실제 순서대로.
 */
object SurfaceEvents {
    const val BANNER_WINDOW_MS = 10_000L
    const val FRESHNESS_MS = EventCardPolicy.FRESHNESS_MS

    private val newestFirst: Comparator<ReceivedGroupEvent> =
        compareByDescending<ReceivedGroupEvent> { it.envelopeTsMs }.thenByDescending { it.receivedAtMs }

    fun othersOnly(events: List<ReceivedGroupEvent>, myVehicleId: String?): List<ReceivedGroupEvent> =
        if (myVehicleId == null) events else events.filter { it.event.sourceVehicleId != myVehicleId }

    /** 서버 시계 기준 나이(ms, 0 이상) */
    fun ageMs(e: ReceivedGroupEvent, serverNowMs: Long): Long = (serverNowMs - e.envelopeTsMs).coerceAtLeast(0)

    fun isFresh(e: ReceivedGroupEvent, serverNowMs: Long): Boolean = serverNowMs - e.envelopeTsMs <= FRESHNESS_MS

    /** '방금 소식' 후보 중 가장 최근(없으면 null) */
    fun banner(events: List<ReceivedGroupEvent>, myVehicleId: String?, nowMs: Long, serverNowMs: Long): ReceivedGroupEvent? =
        othersOnly(events, myVehicleId)
            .filter { nowMs - it.receivedAtMs <= BANNER_WINDOW_MS && isFresh(it, serverNowMs) }
            .minWithOrNull(newestFirst)

    fun bannerUi(events: List<ReceivedGroupEvent>, myVehicleId: String?, nowMs: Long, serverNowMs: Long): EventBannerUi? =
        banner(events, myVehicleId, nowMs, serverNowMs)?.let { e ->
            EventBannerUi(
                text = DriveFormat.eventText(e.event.type, e.event.speech),
                tone = DriveFormat.eventTone(e.event.type),
                ageS = (ageMs(e, serverNowMs) / 1_000).toInt(),
            )
        }

    /** 지난 소식 — 내 차 제외, 발생 시각 최신 먼저 [count]개 */
    fun recent(events: List<ReceivedGroupEvent>, myVehicleId: String?, count: Int): List<ReceivedGroupEvent> =
        othersOnly(events, myVehicleId).sortedWith(newestFirst).take(count)
}
