package app.donghaeng.voice

import app.donghaeng.common.api.ApiException
import app.donghaeng.event.GroupEventSink
import app.donghaeng.event.SpeechComposer
import app.donghaeng.group.Caller
import app.donghaeng.group.GroupAccessApi
import app.donghaeng.identity.VehicleInfo
import app.donghaeng.protocol.DhJson
import app.donghaeng.protocol.GroupEvent
import app.donghaeng.protocol.GroupEventType
import app.donghaeng.protocol.Severity
import app.donghaeng.protocol.VoiceMessagePayload
import app.donghaeng.protocol.rest.ErrorCodes
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [VoiceService] 검사 순서·빈도 제한·이벤트 내용 — 그룹 권한·저장소·이벤트 출구는 메모리 대역 */
class VoiceServiceTest {

    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }

    /** 그룹 권한 대역: [members] 의 차만 멤버, [active] 가 false 면 끝난 그룹(멤버면 409) */
    private class FakeAccess(var members: Set<String>, var active: Boolean = true, private val nicknames: Map<String, String>) : GroupAccessApi {
        var lockCalls = 0
        override fun lockActiveMemberVehicle(caller: Caller.Vehicle, groupId: String): VehicleInfo {
            lockCalls++
            if (groupId != GID || caller.vehicleId !in members) throw ApiException(HttpStatus.NOT_FOUND, ErrorCodes.NOT_FOUND, "그룹을 찾을 수 없어요.")
            if (!active) throw ApiException(HttpStatus.CONFLICT, ErrorCodes.GROUP_ENDED, "이미 종료된 그룹이에요.")
            return VehicleInfo(caller.vehicleId, "usr_${caller.vehicleId}", nicknames.getValue(caller.vehicleId), "KIA", "EV9", true, true)
        }

        override fun requireViewer(caller: Caller, groupId: String): Boolean {
            val ok = groupId == GID && when (caller) {
                is Caller.Vehicle -> caller.vehicleId in members
                is Caller.User -> members.any { "usr_$it" == caller.userId }
            }
            if (!ok) throw ApiException(HttpStatus.NOT_FOUND, ErrorCodes.NOT_FOUND, "그룹을 찾을 수 없어요.")
            return active
        }
    }

    private class InMemoryStore : VoiceStore {
        val rows = mutableListOf<StoredVoice>()
        val locked = mutableListOf<String>()
        var findCalls = 0
        override fun lockSender(vehicleId: String) { locked += vehicleId }
        override fun lastCreatedAt(vehicleId: String): Instant? = rows.filter { it.meta.senderVehicleId == vehicleId }.maxOfOrNull { it.meta.createdAt }
        override fun insert(meta: VoiceMeta, audio: ByteArray) { rows += StoredVoice(meta, audio) }
        override fun find(groupId: String, voiceId: String, now: Instant): StoredVoice? {
            findCalls++
            return rows.firstOrNull { it.meta.groupId == groupId && it.meta.voiceId == voiceId && it.meta.expiresAt.isAfter(now) }
        }
        override fun deleteByGroup(groupId: String): Int = rows.count { it.meta.groupId == groupId }.also { rows.removeAll { r -> r.meta.groupId == groupId } }
        override fun deleteExpired(now: Instant): Int = rows.count { !it.meta.expiresAt.isAfter(now) }.also { rows.removeAll { r -> !r.meta.expiresAt.isAfter(now) } }
    }

    private class RecordingSink : GroupEventSink {
        val recorded = mutableListOf<Pair<String, GroupEvent>>()
        override fun record(groupId: String, type: GroupEventType, sourceVehicleId: String?, severity: Severity, speech: String?, payload: JsonObject, audience: List<String>?): GroupEvent {
            val event = GroupEvent("evt_${recorded.size + 1}", type.name, sourceVehicleId, severity, speech, payload, audience)
            recorded += groupId to event
            return event
        }
    }

    /** 바이트를 읽었는지 기록하는 파트 — 검사가 끝나기 전에는 읽지 않아야 한다 */
    private class Part(override val contentType: String?, private val data: ByteArray, override val size: Long = data.size.toLong()) : VoiceAudioPart {
        var reads = 0
        override fun bytes(): ByteArray { reads++; return data }
    }

    private companion object {
        const val GID = "grp_1"
        const val A = "veh_A"
        const val B = "veh_B"
        const val OUT = "veh_OUT"
    }

    private val t0: Instant = Instant.parse("2026-10-28T05:00:00Z")
    private val clock = MutableClock(t0)
    private val access = FakeAccess(setOf(A, B), nicknames = mapOf(A to "아빠 차", B to "엄마 차"))
    private val store = InMemoryStore()
    private val sink = RecordingSink()
    private val service = VoiceService(access, store, sink, SpeechComposer(), clock)

    private fun vehicle(vid: String) = Caller.Vehicle(vid, "usr_$vid")
    private fun audio(size: Int = 12_000, mime: String? = "audio/mp4") = Part(mime, ByteArray(size) { (it % 251).toByte() })

    private fun assertApiError(status: Int, code: String, block: () -> Unit) {
        val e = assertThrows<ApiException> { block() }
        assertEquals(status, e.status.value(), e.message)
        assertEquals(code, e.code, e.message)
    }

    @Test
    fun `업로드 — VOICE_MESSAGE 이벤트(그룹 전체, '{닉네임} 무전')와 저장, 응답은 이벤트와 같은 값`() {
        val part = audio(mime = "audio/mp4; codecs=mp4a.40.2")
        val res = service.upload(vehicle(B), GID, part, "7000")

        assertTrue(res.voiceId.startsWith("vce_") && VoiceRules.isVoiceId(res.voiceId), res.voiceId)
        assertEquals(7_000, res.durationMs)
        assertEquals(t0.plus(Duration.ofHours(24)).toEpochMilli(), res.expiresAtMs)

        val (groupId, event) = sink.recorded.single()
        assertEquals(GID, groupId)
        assertEquals(res.eventId, event.eventId)
        assertEquals(GroupEventType.VOICE_MESSAGE.name, event.type)
        assertEquals(B, event.sourceVehicleId)
        assertEquals(Severity.INFO, event.severity)
        assertEquals("엄마 차 무전", event.speech)
        assertNull(event.audience, "그룹 전체")
        val payload = DhJson.payloadAs(event.payload, VoiceMessagePayload.serializer())
        assertEquals(VoiceMessagePayload(res.voiceId, 7_000, "audio/mp4", 12_000, res.expiresAtMs), payload)

        val stored = store.rows.single()
        assertEquals(VoiceMeta(res.voiceId, GID, B, event.eventId, "audio/mp4", 7_000, 12_000, t0, t0.plus(Duration.ofHours(24))), stored.meta)
        assertContentEquals(part.bytes(), stored.audio)
        assertEquals(listOf(B), store.locked, "같은 차 업로드 직렬화")
    }

    @Test
    fun `멤버·종료 검사가 먼저 — 비멤버는 파일 형식과 상관없이 404, 끝난 그룹은 409, 오디오는 읽지 않는다`() {
        val bad = audio(mime = "audio/ogg")
        assertApiError(404, ErrorCodes.NOT_FOUND) { service.upload(vehicle(OUT), GID, bad, "abc") }
        assertApiError(404, ErrorCodes.NOT_FOUND) { service.upload(vehicle(A), "grp_OTHER", audio(), "1000") }
        access.active = false
        assertApiError(409, ErrorCodes.GROUP_ENDED) { service.upload(vehicle(A), GID, bad, "1000") }
        assertEquals(0, bad.reads)
        assertTrue(sink.recorded.isEmpty() && store.rows.isEmpty())
    }

    @Test
    fun `형식 검사 — 없음·크기·mime·길이·빈 파일`() {
        assertApiError(400, ErrorCodes.INVALID_VOICE) { service.upload(vehicle(A), GID, null, "1000") }

        val tooBig = Part("audio/mp4", ByteArray(0), size = VoiceRules.MAX_BYTES + 1L)
        assertApiError(413, ErrorCodes.VOICE_TOO_LARGE) { service.upload(vehicle(A), GID, tooBig, "1000") }
        assertEquals(0, tooBig.reads, "선언 크기만으로 거른다")
        // 선언 크기와 실제 바이트가 다르면 실제 바이트로 한 번 더
        val lying = Part("audio/mp4", ByteArray(VoiceRules.MAX_BYTES + 1), size = 10)
        assertApiError(413, ErrorCodes.VOICE_TOO_LARGE) { service.upload(vehicle(A), GID, lying, "1000") }

        assertApiError(415, ErrorCodes.UNSUPPORTED_VOICE_TYPE) { service.upload(vehicle(A), GID, audio(mime = "audio/ogg"), "1000") }
        assertApiError(415, ErrorCodes.UNSUPPORTED_VOICE_TYPE) { service.upload(vehicle(A), GID, audio(mime = null), "1000") }

        for (raw in listOf(null, "", "0", "16001", "abc", "1.5")) {
            assertApiError(400, ErrorCodes.INVALID_VOICE) { service.upload(vehicle(A), GID, audio(), raw) }
        }
        assertApiError(400, ErrorCodes.INVALID_VOICE) { service.upload(vehicle(A), GID, audio(size = 0), "1000") }
        assertTrue(sink.recorded.isEmpty() && store.rows.isEmpty(), "거절은 아무것도 남기지 않는다")

        // 경계값: 정확히 512 KB · 16 000 ms 는 통과
        service.upload(vehicle(A), GID, audio(size = VoiceRules.MAX_BYTES), "16000")
        clock.now = clock.now.plusSeconds(5)
        service.upload(vehicle(B), GID, audio(size = 1, mime = "audio/wav"), "1")
        assertEquals(listOf("audio/mp4", "audio/wav"), store.rows.map { it.meta.mime })
    }

    @Test
    fun `빈도 제한 — 같은 차 3초 안 두 번째는 429, 다른 차·3초 뒤·거절된 시도는 세지 않음`() {
        assertApiError(415, ErrorCodes.UNSUPPORTED_VOICE_TYPE) { service.upload(vehicle(A), GID, audio(mime = "audio/ogg"), "1000") }
        service.upload(vehicle(A), GID, audio(), "1000") // 거절된 시도 직후라도 통과
        clock.now = t0.plusMillis(2_999)
        assertApiError(429, ErrorCodes.VOICE_RATE_LIMITED) { service.upload(vehicle(A), GID, audio(), "1000") }
        service.upload(vehicle(B), GID, audio(), "1000") // 다른 차는 별개
        clock.now = t0.plusMillis(3_000)
        service.upload(vehicle(A), GID, audio(), "1000")
        assertEquals(listOf(A, B, A), store.rows.map { it.meta.senderVehicleId })
        assertEquals(3, sink.recorded.size, "429 는 이벤트를 남기지 않는다")
    }

    @Test
    fun `다운로드 — 멤버 차·소유자만, 끝난 그룹·만료·형식 오류는 404`() {
        val part = audio(size = 3_000)
        val res = service.upload(vehicle(B), GID, part, "2500")

        assertContentEquals(part.bytes(), service.download(vehicle(A), GID, res.voiceId).audio)
        assertEquals("audio/mp4", service.download(Caller.User("usr_$A"), GID, res.voiceId).meta.mime)
        assertApiError(404, ErrorCodes.NOT_FOUND) { service.download(vehicle(OUT), GID, res.voiceId) }
        assertApiError(404, ErrorCodes.NOT_FOUND) { service.download(Caller.User("usr_OUT"), GID, res.voiceId) }

        val before = store.findCalls
        assertApiError(404, ErrorCodes.NOT_FOUND) { service.download(vehicle(A), GID, "vce_bad") }
        assertEquals(before, store.findCalls, "형식이 틀리면 저장소를 보지 않는다")
        assertApiError(404, ErrorCodes.NOT_FOUND) { service.download(vehicle(A), GID, "vce_" + "0".repeat(26)) }

        clock.now = t0.plus(Duration.ofHours(24))
        assertApiError(404, ErrorCodes.NOT_FOUND) { service.download(vehicle(A), GID, res.voiceId) }
        clock.now = t0
        access.active = false
        assertApiError(404, ErrorCodes.NOT_FOUND) { service.download(vehicle(A), GID, res.voiceId) }
    }
}
