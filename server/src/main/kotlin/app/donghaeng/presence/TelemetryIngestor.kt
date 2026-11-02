package app.donghaeng.presence

import app.donghaeng.group.GroupMembershipApi
import app.donghaeng.mqtt.MqttInboundHandler
import app.donghaeng.mqtt.PresenceInboundHandler
import app.donghaeng.protocol.DhJson
import app.donghaeng.protocol.Envelope
import app.donghaeng.protocol.Telemetry
import app.donghaeng.protocol.Topics
import kotlinx.serialization.json.JsonElement
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.concurrent.atomic.AtomicLong

/**
 * `dh/v1/vehicle/{vid}/telemetry` → Redis 상태 (docs/06 §3 파이프라인, T24).
 * 본문 해석 실패는 WARN 만(본문은 찍지 않는다). 소속 그룹이 있으면 GEO 인덱스도 갱신한다.
 *
 * **시각 = 서버 수신 시각(C099, 계약 4 — docs/03 §5.3)**: 마지막 위치 시각(`ageMs`·OFFLINE)·ETA 시각·정차 시작 시각은 차량 봉투 `ts` 가 아니라
 * 서버 [clock] 으로 적는다 — 시계가 1분 넘게 늦은 차가 달리는 중에도 OFFLINE 으로, 빠른 차가 끊긴 뒤에도 DRIVING 으로 보이던 문제.
 * 봉투 `ts` 는 [ClockLagTracker] 에 '수신 − ts' 표본으로만 넣는다(위험 이벤트 늦음 판정의 시계 차 보정, `VehicleEventService`).
 */
@Component
class TelemetryIngestor(
    private val store: PresenceStore,
    private val membership: GroupMembershipApi,
    private val lags: ClockLagTracker,
    private val clock: Clock,
) : MqttInboundHandler {

    override val kind: Topics.VehicleTopicKind = Topics.VehicleTopicKind.TELEMETRY

    private val count = AtomicLong()

    /** 받아서 저장한 건수 (진단·테스트용) */
    val ingested: Long get() = count.get()

    override fun handle(vehicleId: String, envelope: Envelope<JsonElement>) {
        val telemetry = try {
            DhJson.decodeData(envelope, Telemetry.serializer()).data
        } catch (e: Exception) {
            log.warn("telemetry dropped: bad body vid={} seq={} cause={}", vehicleId, envelope.seq, e.javaClass.simpleName)
            return
        }
        val receivedAtMs = clock.millis()
        store.recordTelemetry(vehicleId, telemetry, receivedAtMs)
        lags.record(vehicleId, sentAtMs = envelope.ts, receivedAtMs = receivedAtMs)
        membership.activeGroupIdOf(vehicleId)?.let { gid -> store.recordGeo(gid, vehicleId, telemetry.lat, telemetry.lon) }
        val n = count.incrementAndGet()
        if (n == 1L || n % LOG_EVERY == 0L) log.info("telemetry ingested total={} vid={} seq={} src={}", n, vehicleId, envelope.seq, telemetry.locSource)
    }

    companion object {
        private val log = LoggerFactory.getLogger(TelemetryIngestor::class.java)
        private const val LOG_EVERY = 60L
    }
}

/** `dh/v1/vehicle/{vid}/presence` (`{"online":…}`, 봉투 없음) → `veh:{vid}:online` */
@Component
class PresenceIngestor(private val store: PresenceStore) : PresenceInboundHandler {

    override fun onPresence(vehicleId: String, online: Boolean, tsMs: Long) {
        store.recordPresence(vehicleId, online, tsMs)
        log.info("presence vid={} online={}", vehicleId, online)
    }

    companion object {
        private val log = LoggerFactory.getLogger(PresenceIngestor::class.java)
    }
}
