package app.donghaeng.presence

import app.donghaeng.group.GroupMembershipApi
import app.donghaeng.group.GroupRoster
import app.donghaeng.protocol.DhJson
import app.donghaeng.protocol.Envelope
import app.donghaeng.protocol.LocSource
import app.donghaeng.protocol.MessageTypes
import app.donghaeng.protocol.Telemetry
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals

/** C099·계약 4: 텔레메트리 시각은 서버 수신 시각 — 차량 시계가 틀려도 OFFLINE·정차 판정이 흔들리지 않는다 */
class TelemetryIngestorTest {

    private class RecordingStore : PresenceStore {
        val telemetry = mutableListOf<Pair<String, Long>>()
        override fun recordTelemetry(vehicleId: String, telemetry: Telemetry, tsMs: Long) { this.telemetry += vehicleId to tsMs }
        override fun recordGeo(groupId: String, vehicleId: String, lat: Double, lon: Double) {}
        override fun recordPresence(vehicleId: String, online: Boolean, tsMs: Long) {}
        override fun stateOf(vehicleId: String): VehicleState? = null
        override fun onlineOf(vehicleId: String): Boolean? = null
    }

    private object NoGroup : GroupMembershipApi {
        override fun activeGroupIdOf(vehicleId: String): String? = null
        override fun rosterOf(groupId: String): GroupRoster? = null
        override fun activeRosters(): List<GroupRoster> = emptyList()
    }

    private val now = Instant.parse("2026-11-02T03:00:00Z")
    private val store = RecordingStore()
    private val lags = ClockLagTracker()
    private val ingestor = TelemetryIngestor(store, NoGroup, lags, Clock.fixed(now, ZoneOffset.UTC))

    private fun ingest(vid: String, envelopeTs: Long) {
        val data = DhJson.json.encodeToJsonElement(Telemetry.serializer(), Telemetry(lat = 37.5, lon = 127.0, heading = 0f, speedKph = 80f, locSource = LocSource.NAVI))
        ingestor.handle(vid, Envelope(type = MessageTypes.TELEMETRY, vid = vid, ts = envelopeTs, seq = 1, data = data))
    }

    @Test
    fun `the state timestamp is the server receive time, not the vehicle envelope ts`() {
        ingest("veh_SLOW", envelopeTs = now.toEpochMilli() - 90_000) // 차 시계가 90 s 늦다
        ingest("veh_FAST", envelopeTs = now.toEpochMilli() + 120_000) // 차 시계가 2분 빠르다
        assertEquals(listOf("veh_SLOW" to now.toEpochMilli(), "veh_FAST" to now.toEpochMilli()), store.telemetry)
    }

    @Test
    fun `the envelope ts only feeds the clock lag baseline`() {
        ingest("veh_SLOW", envelopeTs = now.toEpochMilli() - 90_000)
        assertEquals(90_000, lags.lagMs("veh_SLOW", now.toEpochMilli()))
    }
}
