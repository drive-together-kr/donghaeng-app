package app.donghaeng.companion.sdk.internal

import app.donghaeng.protocol.DhJson
import app.donghaeng.protocol.GroupEvent
import app.donghaeng.protocol.GroupEventType
import app.donghaeng.protocol.Severity
import app.donghaeng.protocol.VoiceMessagePayload
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * F16 무전 수신 — `VOICE_MESSAGE` 해석·내려받기 경로·payload 덧붙이기([VoiceMessages])와 캐시·만료·재시도([VoiceInbox]).
 * 전송은 가짜 fetch 함수로 바꿔 끼운다(HTTP 경로는 HttpVoiceDownloadTest).
 */
class VoiceInboxTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val day = 24L * 60 * 60 * 1000
    private val t0 = 1_793_000_000_000L
    private var now = t0
    private val audio = byteArrayOf(0, 0, 0, 24, 102, 116, 121, 112) // "....ftyp" — m4a 머리 흉내

    private fun inbox(root: File = File(tmp.root, "voice"), downloadsPerPoll: Int = 3) =
        VoiceInbox(rootDir = root, clock = { now }, maxDownloadsPerPoll = downloadsPerPoll)

    private fun voiceEvent(
        eventId: String,
        voiceId: String,
        expiresAtMs: Long = t0 + day,
        durationMs: Int = 3_200,
        mime: String = "audio/mp4",
        source: String = "veh_lead",
    ) = GroupEvent(
        eventId = eventId,
        type = GroupEventType.VOICE_MESSAGE.name,
        sourceVehicleId = source,
        severity = Severity.INFO,
        speech = "아빠 차 무전",
        payload = DhJson.payloadOf(VoiceMessagePayload(voiceId, durationMs, mime, audio.size, expiresAtMs)),
    )

    private fun event(eventId: String, type: GroupEventType, payload: JsonObject = JsonObject(emptyMap())) =
        GroupEvent(eventId, type.name, "veh_lead", Severity.INFO, null, payload)

    private fun GroupEvent.status(): String? = payload[VoiceMessages.KEY_STATUS]?.jsonPrimitive?.content
    private fun GroupEvent.path(): String? = payload[VoiceMessages.KEY_PATH]?.jsonPrimitive?.content

    /** 요청 경로를 기록하는 가짜 전송 */
    private class FakeFetch(var next: (String) -> VoiceInbox.Fetch) {
        val paths = ArrayList<String>()
        suspend fun invoke(path: String): VoiceInbox.Fetch {
            paths += path
            return next(path)
        }
    }

    private fun ok(bytes: ByteArray) = FakeFetch { VoiceInbox.Fetch.Ok(bytes) }

    // ---------- VoiceMessages (순수) ----------

    @Test
    fun `parse - VOICE_MESSAGE payload 를 해석하고 다른 종류·형식 오류·위험한 voiceId 는 null`() {
        val parsed = VoiceMessages.parse(voiceEvent("evt_1", "vm_01HABC", durationMs = 7_000))
        assertNotNull(parsed)
        assertEquals("vm_01HABC", parsed!!.voiceId)
        assertEquals(7_000, parsed.durationMs)
        assertEquals("audio/mp4", parsed.mime)
        assertEquals(t0 + day, parsed.expiresAtMs)

        assertNull("다른 종류", VoiceMessages.parse(event("evt_2", GroupEventType.PASSENGER_MESSAGE, buildJsonObject { put("text", "hi") })))
        assertNull("필드 누락", VoiceMessages.parse(GroupEvent("evt_3", "VOICE_MESSAGE", "veh_a", Severity.INFO, null, buildJsonObject { put("voiceId", "vm_1") })))
        assertNull("경로 조작", VoiceMessages.parse(voiceEvent("evt_4", "../../etc")))
        assertNull("슬래시", VoiceMessages.parse(voiceEvent("evt_5", "vm/1")))
        assertNull("빈 ID", VoiceMessages.parse(voiceEvent("evt_6", "")))
        assertNull("길이 0", VoiceMessages.parse(voiceEvent("evt_7", "vm_1", durationMs = 0)))
    }

    @Test
    fun `parse - 모르는 키(덧붙인 상태 키 포함)가 있어도 해석된다`() {
        val annotated = VoiceMessages.annotate(voiceEvent("evt_1", "vm_1"), VoiceMessages.Status.PENDING)
        val extra = annotated.copy(payload = JsonObject(annotated.payload + ("future" to JsonPrimitive(1))))
        assertEquals("vm_1", VoiceMessages.parse(extra)?.voiceId)
    }

    @Test
    fun `downloadPath - docs 03 2_6 의 GET groups-id-voice-voiceId`() {
        assertEquals("/v1/groups/grp_01H/voice/vm_01HABC", VoiceMessages.downloadPath("grp_01H", "vm_01HABC"))
    }

    @Test
    fun `extensionFor - mime 별 캐시 확장자`() {
        assertEquals("m4a", VoiceMessages.extensionFor("audio/mp4"))
        assertEquals("m4a", VoiceMessages.extensionFor("Audio/MP4; codecs=mp4a.40.2"))
        assertEquals("aac", VoiceMessages.extensionFor("audio/aac"))
        assertEquals("wav", VoiceMessages.extensionFor("audio/wav"))
        assertEquals("bin", VoiceMessages.extensionFor("application/octet-stream"))
    }

    @Test
    fun `annotate - 원래 키는 그대로, 상태를 덧붙이고 경로는 READY 일 때만`() {
        val original = voiceEvent("evt_1", "vm_1")
        val file = File(tmp.root, "x.m4a")
        val ready = VoiceMessages.annotate(original, VoiceMessages.Status.READY, file)
        assertEquals("READY", ready.status())
        assertEquals(file.absolutePath, ready.path())
        assertEquals(original.payload["voiceId"], ready.payload["voiceId"])
        assertEquals(original.payload["expiresAtMs"], ready.payload["expiresAtMs"])
        assertEquals("다른 필드는 그대로", original.copy(payload = ready.payload), ready)

        val expired = VoiceMessages.annotate(ready, VoiceMessages.Status.EXPIRED, file)
        assertEquals("EXPIRED", expired.status())
        assertNull("READY 가 아니면 경로를 지운다", expired.path())
    }

    // ---------- VoiceInbox ----------

    @Test
    fun `무전도 그룹 종료도 없으면 그대로 돌려주고 파일 시스템을 건드리지 않는다`() = runTest {
        val root = File(tmp.root, "untouched")
        val events = listOf(event("evt_1", GroupEventType.MEMBER_JOINED), event("evt_2", GroupEventType.LOW_ENERGY))
        val fetch = ok(audio)
        val out = inbox(root).resolve("grp_1", events, fetch = fetch::invoke)
        assertSame(events, out)
        assertTrue(fetch.paths.isEmpty())
        assertFalse(root.exists())
    }

    @Test
    fun `내려받아 캐시에 저장하면 READY + 경로, 다음 폴링은 다시 받지 않는다`() = runTest {
        val inbox = inbox()
        val fetch = ok(audio)
        val events = listOf(event("evt_0", GroupEventType.MEMBER_JOINED), voiceEvent("evt_1", "vm_1"), event("evt_2", GroupEventType.LOW_ENERGY))

        val first = inbox.resolve("grp_1", events, fetch = fetch::invoke)

        assertEquals(listOf("/v1/groups/grp_1/voice/vm_1"), fetch.paths)
        assertEquals("순서·다른 이벤트 유지", events.map { it.eventId }, first.map { it.eventId })
        assertSame(events[0], first[0])
        assertSame(events[2], first[2])
        val voice = first[1]
        assertEquals("READY", voice.status())
        val file = File(voice.path()!!)
        assertTrue(file.isFile)
        assertEquals("vm_1.m4a", file.name)
        assertEquals("grp_1", file.parentFile!!.name)
        assertArrayEquals(audio, file.readBytes())
        assertFalse("임시 .part 파일이 남지 않는다", File(file.parentFile, "vm_1.m4a.part").exists())

        val second = inbox.resolve("grp_1", events, fetch = fetch::invoke)
        assertEquals("캐시 재사용", 1, fetch.paths.size)
        assertEquals("READY", second[1].status())
        assertEquals(file.absolutePath, second[1].path())
    }

    @Test
    fun `expiresAtMs 가 지난 무전은 요청 없이 EXPIRED, 받아 둔 파일도 지운다`() = runTest {
        val inbox = inbox()
        val fetch = ok(audio)
        val stale = inbox.resolve("grp_1", listOf(voiceEvent("evt_old", "vm_old", expiresAtMs = t0 - 1)), fetch = fetch::invoke)
        assertEquals("EXPIRED", stale[0].status())
        assertNull(stale[0].path())
        assertTrue(fetch.paths.isEmpty())

        val events = listOf(voiceEvent("evt_1", "vm_1", expiresAtMs = t0 + 60_000))
        val ready = inbox.resolve("grp_1", events, fetch = fetch::invoke)
        val file = File(ready[0].path()!!)
        assertTrue(file.isFile)

        now = t0 + 60_000 // 만료 시각 도달
        val expired = inbox.resolve("grp_1", events, fetch = fetch::invoke)
        assertEquals("EXPIRED", expired[0].status())
        assertNull(expired[0].path())
        assertFalse("만료되면 캐시 파일을 지운다", file.exists())
        assertEquals(1, fetch.paths.size)
    }

    @Test
    fun `만료는 서버 시계(Date 헤더) 기준 - 폰 시계가 이틀 빨라도 받는다`() = runTest {
        now = t0 + 2 * day // 이 기기 시계가 틀림
        val events = listOf(voiceEvent("evt_1", "vm_1", expiresAtMs = t0 + day - 60_000))

        val wrongClock = inbox(File(tmp.root, "a"))
        assertEquals("서버 시계를 모르면 기기 시계로 본다", "EXPIRED", wrongClock.resolve("grp_1", events, fetch = ok(audio)::invoke)[0].status())

        val synced = inbox(File(tmp.root, "b"))
        synced.observeServerDate(t0)
        assertEquals(t0, synced.serverNow())
        val fetch = ok(audio)
        assertEquals("READY", synced.resolve("grp_1", events, fetch = fetch::invoke)[0].status())
        assertEquals(1, fetch.paths.size)

        synced.observeServerDate(null) // Date 없는 응답은 맞춘 값을 지우지 않는다
        assertEquals(t0, synced.serverNow())
    }

    @Test
    fun `404(Gone) 는 EXPIRED 이고 다시 묻지 않는다`() = runTest {
        val inbox = inbox()
        val fetch = FakeFetch { VoiceInbox.Fetch.Gone }
        val events = listOf(voiceEvent("evt_1", "vm_1"))
        assertEquals("EXPIRED", inbox.resolve("grp_1", events, fetch = fetch::invoke)[0].status())
        assertEquals("EXPIRED", inbox.resolve("grp_1", events, fetch = fetch::invoke)[0].status())
        assertEquals(1, fetch.paths.size)
    }

    @Test
    fun `일시 오류는 PENDING 으로 다음 폴링에 다시, 3번째 실패면 FAILED 로 멈춘다`() = runTest {
        val inbox = inbox()
        val fetch = FakeFetch { VoiceInbox.Fetch.Failed("NETWORK", retryable = true) }
        val events = listOf(voiceEvent("evt_1", "vm_1"))
        assertEquals("PENDING", inbox.resolve("grp_1", events, fetch = fetch::invoke)[0].status())
        assertEquals("PENDING", inbox.resolve("grp_1", events, fetch = fetch::invoke)[0].status())
        assertEquals("FAILED", inbox.resolve("grp_1", events, fetch = fetch::invoke)[0].status())
        assertEquals("FAILED", inbox.resolve("grp_1", events, fetch = fetch::invoke)[0].status())
        assertEquals(3, fetch.paths.size)
    }

    @Test
    fun `일시 오류 뒤 성공하면 READY`() = runTest {
        val inbox = inbox()
        var fail = true
        val fetch = FakeFetch { if (fail) VoiceInbox.Fetch.Failed("HTTP_503", retryable = true) else VoiceInbox.Fetch.Ok(audio) }
        val events = listOf(voiceEvent("evt_1", "vm_1"))
        assertEquals("PENDING", inbox.resolve("grp_1", events, fetch = fetch::invoke)[0].status())
        fail = false
        assertEquals("READY", inbox.resolve("grp_1", events, fetch = fetch::invoke)[0].status())
    }

    @Test
    fun `영구 오류(크기 초과·403)와 빈 본문은 바로 FAILED`() = runTest {
        val inbox = inbox()
        val tooLarge = FakeFetch { VoiceInbox.Fetch.Failed("TOO_LARGE", retryable = false) }
        assertEquals("FAILED", inbox.resolve("grp_1", listOf(voiceEvent("evt_1", "vm_1")), fetch = tooLarge::invoke)[0].status())
        val empty = ok(ByteArray(0))
        assertEquals("FAILED", inbox.resolve("grp_1", listOf(voiceEvent("evt_2", "vm_2")), fetch = empty::invoke)[0].status())
    }

    @Test
    fun `형식이 깨진 VOICE_MESSAGE 는 FAILED 로 표시만 하고 요청하지 않는다`() = runTest {
        val fetch = ok(audio)
        val broken = GroupEvent("evt_1", "VOICE_MESSAGE", "veh_a", Severity.INFO, null, buildJsonObject { put("voiceId", "../x") })
        val out = inbox().resolve("grp_1", listOf(broken), fetch = fetch::invoke)
        assertEquals("FAILED", out[0].status())
        assertTrue(fetch.paths.isEmpty())
    }

    @Test
    fun `한 번 폴링에 최대 3개 - 최신 무전부터, 나머지는 PENDING 으로 다음 폴링에`() = runTest {
        val inbox = inbox()
        val fetch = ok(audio)
        val events = (1..5).map { voiceEvent("evt_$it", "vm_$it") } // 오래된 것부터

        val first = inbox.resolve("grp_1", events, fetch = fetch::invoke)
        assertEquals(listOf("PENDING", "PENDING", "READY", "READY", "READY"), first.map { it.status() })
        assertEquals(listOf("vm_5", "vm_4", "vm_3"), fetch.paths.map { it.substringAfterLast('/') })

        val second = inbox.resolve("grp_1", events, fetch = fetch::invoke)
        assertEquals(List(5) { "READY" }, second.map { it.status() })
        assertEquals(5, fetch.paths.size)
    }

    @Test
    fun `GROUP_ENDED 가 보이면 그 그룹 캐시를 지우고 무전은 EXPIRED`() = runTest {
        val root = File(tmp.root, "voice")
        val inbox = inbox(root)
        val fetch = ok(audio)
        val voice = voiceEvent("evt_1", "vm_1")
        val ready = inbox.resolve("grp_1", listOf(voice), fetch = fetch::invoke)
        val file = File(ready[0].path()!!)
        assertTrue(file.isFile)
        // 다른 그룹 파일은 남는다
        val other = inbox.resolve("grp_2", listOf(voiceEvent("evt_9", "vm_9")), fetch = fetch::invoke)
        val otherFile = File(other[0].path()!!)

        val ended = inbox.resolve("grp_1", listOf(voice, event("evt_2", GroupEventType.GROUP_ENDED)), fetch = fetch::invoke)

        assertEquals("EXPIRED", ended[0].status())
        assertNull(ended[0].path())
        assertNull("GROUP_ENDED 이벤트 자체는 그대로", ended[1].payload[VoiceMessages.KEY_STATUS])
        assertFalse(file.exists())
        assertFalse(File(root, "grp_1").exists())
        assertTrue(otherFile.isFile)
        assertEquals(2, fetch.paths.size)
    }

    @Test
    fun `서버 그룹 상태가 ENDED 면 GROUP_ENDED 이벤트가 밀려나 있어도 캐시를 지우고 EXPIRED`() = runTest {
        val inbox = inbox()
        val fetch = ok(audio)
        val voice = voiceEvent("evt_1", "vm_1")
        val file = File(inbox.resolve("grp_1", listOf(voice), fetch = fetch::invoke)[0].path()!!)

        val out = inbox.resolve("grp_1", listOf(voice), groupEnded = true, fetch = fetch::invoke)

        assertEquals("EXPIRED", out[0].status())
        assertFalse(file.exists())
        assertEquals(1, fetch.paths.size)
    }

    @Test
    fun `forgetGroup - 더 볼 수 없는 그룹(나간 뒤 404)의 캐시를 지운다`() = runTest {
        val root = File(tmp.root, "voice")
        val inbox = inbox(root)
        val fetch = ok(audio)
        val file = File(inbox.resolve("grp_1", listOf(voiceEvent("evt_1", "vm_1")), fetch = fetch::invoke)[0].path()!!)
        assertTrue(file.isFile)

        inbox.forgetGroup("grp_1")
        inbox.forgetGroup("../etc") // 안전하지 않은 ID 는 무시

        assertFalse(file.exists())
        assertFalse(File(root, "grp_1").exists())
    }

    @Test
    fun `이전 프로세스가 받아 둔 파일은 다시 받지 않는다`() = runTest {
        val root = File(tmp.root, "voice")
        val cached = File(File(root, "grp_1"), "vm_1.m4a")
        cached.parentFile!!.mkdirs()
        cached.writeBytes(audio)
        cached.setLastModified(now) // 시작 때 정리(하루 넘은 파일)의 기준이 이 테스트 시계라서
        val fetch = ok(byteArrayOf(9))

        val out = inbox(root).resolve("grp_1", listOf(voiceEvent("evt_1", "vm_1")), fetch = fetch::invoke)

        assertEquals("READY", out[0].status())
        assertEquals(cached.absolutePath, out[0].path())
        assertTrue(fetch.paths.isEmpty())
    }

    @Test
    fun `안전하지 않은 groupId 는 내려받지 않고 FAILED`() = runTest {
        val fetch = ok(audio)
        val out = inbox().resolve("../grp", listOf(voiceEvent("evt_1", "vm_1")), fetch = fetch::invoke)
        assertEquals("FAILED", out[0].status())
        assertTrue(fetch.paths.isEmpty())
    }

    @Test
    fun `wav mime 은 wav 확장자로 저장된다`() = runTest {
        val out = inbox().resolve("grp_1", listOf(voiceEvent("evt_1", "vm_w", mime = "audio/wav")), fetch = ok(audio)::invoke)
        assertTrue(out[0].path()!!.endsWith("vm_w.wav"))
    }
}
