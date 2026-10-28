package app.donghaeng.companion.demo.donghaeng

import app.donghaeng.companion.sdk.GroupMember
import app.donghaeng.companion.sdk.MemberRole
import app.donghaeng.protocol.DhJson
import app.donghaeng.protocol.GroupEvent
import app.donghaeng.protocol.GroupEventType
import app.donghaeng.protocol.MemberState
import app.donghaeng.protocol.MemberStatus
import app.donghaeng.protocol.Severity
import app.donghaeng.protocol.VoiceMessagePayload
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 호스트 쪽 무전 목록 — sdk 가 덧붙인 payload 키 읽기·합치기·표기, 시연 신호음 WAV */
class VoiceItemsTest {

    private val now = 1_793_000_000_000L
    private val group = ActiveGroup(
        groupId = "grp_1",
        name = "추석 귀성길",
        myVehicleId = "veh_me",
        myVehicleNickname = "내 차",
        members = listOf(GroupMember("veh_lead", "아빠 차", "#2E7DFF", MemberRole.LEADER), GroupMember("veh_me", "내 차", "#FF6B2E", MemberRole.MEMBER)),
        joinedAtMs = now,
    )

    private fun member(id: String, nick: String, color: String) = MemberState(
        vehicleId = id, nickname = nick, color = color, online = true, lat = null, lon = null, heading = null, speedKph = null,
        ageMs = null, eta = null, energy = null, distanceToLeaderM = null, status = MemberStatus.DRIVING,
    )

    private fun voice(
        eventId: String,
        source: String? = "veh_lead",
        status: String? = "READY",
        path: String? = "/cache/donghaeng-voice/grp_1/vm_1.m4a",
        expiresAtMs: Long = now + VoiceEvents.RETENTION_MS - 60_000, // 1분 전에 보냄
        durationMs: Int = 7_000,
    ): GroupEvent {
        val base = DhJson.payloadOf(VoiceMessagePayload("vm_$eventId", durationMs, "audio/mp4", 1_000, expiresAtMs))
        val extra = buildMap {
            status?.let { put(VoiceEvents.KEY_STATUS, JsonPrimitive(it)) }
            path?.let { put(VoiceEvents.KEY_PATH, JsonPrimitive(it)) }
        }
        return GroupEvent(eventId, GroupEventType.VOICE_MESSAGE.name, source, Severity.INFO, "아빠 차 무전", JsonObject(base + extra))
    }

    @Test
    fun `toItem - sdk 가 붙인 READY·경로와 보낸 차 이름·색, 보낸 시각 = 만료 - 24시간`() {
        val item = VoiceEvents.toItem(voice("e1"), listOf(member("veh_lead", "아빠 차(스냅샷)", "#123456")), group, now)!!
        assertEquals("vm_e1", item.voiceId)
        assertEquals(VoiceAudioStatus.READY, item.status)
        assertEquals("/cache/donghaeng-voice/grp_1/vm_1.m4a", item.localPath)
        assertEquals("스냅샷 멤버가 먼저", "아빠 차(스냅샷)", item.senderNickname)
        assertEquals("#123456", item.senderColor)
        assertEquals(7_000, item.durationMs)
        assertEquals(now - 60_000, item.sentAtMs)
        assertFalse(item.mine)
    }

    @Test
    fun `toItem - 스냅샷에 없으면 합류 때 멤버, 그것도 없으면 음성 문구에서 이름`() {
        assertEquals("아빠 차", VoiceEvents.toItem(voice("e1"), emptyList(), group, now)!!.senderNickname)
        assertEquals("아빠 차", VoiceEvents.toItem(voice("e1", source = "veh_unknown"), emptyList(), group, now)!!.senderNickname)
        assertTrue("내 차가 보낸 무전", VoiceEvents.toItem(voice("e2", source = "veh_me"), emptyList(), group, now)!!.mine)
    }

    @Test
    fun `toItem - 상태 키가 없거나(옛 모듈) READY 인데 경로가 없으면 받는 중, 다른 종류는 null`() {
        val noStatus = VoiceEvents.toItem(voice("e1", status = null, path = null), emptyList(), group, now)!!
        assertEquals(VoiceAudioStatus.PENDING, noStatus.status)
        assertNull(noStatus.localPath)
        assertEquals(VoiceAudioStatus.PENDING, VoiceEvents.toItem(voice("e2", path = null), emptyList(), group, now)!!.status)
        val expired = VoiceEvents.toItem(voice("e3", status = "EXPIRED", path = "/x"), emptyList(), group, now)!!
        assertEquals(VoiceAudioStatus.EXPIRED, expired.status)
        assertNull("READY 가 아니면 경로를 쓰지 않는다", expired.localPath)
        val other = GroupEvent("e4", GroupEventType.PASSENGER_MESSAGE.name, "veh_lead", Severity.INFO, "hi", JsonObject(mapOf("text" to JsonPrimitive("hi"))))
        assertNull(VoiceEvents.toItem(other, emptyList(), group, now))
    }

    @Test
    fun `merge - 같은 이벤트는 새 상태로, 최신부터, 밀려난 것은 유지하되 만료·그룹 종료면 EXPIRED`() {
        val pending = VoiceEvents.toItem(voice("e1", status = "PENDING", path = null), emptyList(), group, now)!!
        val older = VoiceEvents.toItem(voice("e0", expiresAtMs = now + VoiceEvents.RETENTION_MS - 600_000), emptyList(), group, now)!!
        val first = VoiceEvents.merge(emptyList(), listOf(older, pending), groupEnded = false, nowMs = now)
        assertEquals(listOf("e1", "e0"), first.map { it.eventId })

        val ready = VoiceEvents.toItem(voice("e1"), emptyList(), group, now)!!
        val second = VoiceEvents.merge(first, listOf(ready), groupEnded = false, nowMs = now)
        assertEquals(VoiceAudioStatus.READY, second.first { it.eventId == "e1" }.status)
        assertEquals("목록 밖으로 밀려난 e0 유지", 2, second.size)

        val afterExpiry = VoiceEvents.merge(second, emptyList(), groupEnded = false, nowMs = older.expiresAtMs)
        assertEquals(VoiceAudioStatus.EXPIRED, afterExpiry.first { it.eventId == "e0" }.status)
        assertNull(afterExpiry.first { it.eventId == "e0" }.localPath)
        assertEquals(VoiceAudioStatus.READY, afterExpiry.first { it.eventId == "e1" }.status)

        val ended = VoiceEvents.merge(second, emptyList(), groupEnded = true, nowMs = now)
        assertTrue(ended.all { it.status == VoiceAudioStatus.EXPIRED })
    }

    @Test
    fun `merge - 최대 20개`() {
        val many = (1..25).map { VoiceEvents.toItem(voice("e$it", expiresAtMs = now + VoiceEvents.RETENTION_MS - it * 1_000L), emptyList(), group, now)!! }
        val merged = VoiceEvents.merge(emptyList(), many, groupEnded = false, nowMs = now)
        assertEquals(VoiceEvents.MAX_ITEMS, merged.size)
        assertEquals("가장 최근(e1)부터", "e1", merged.first().eventId)
    }

    @Test
    fun `durationLabel - 반올림, 0 초과면 최소 1초`() {
        assertEquals("0:07", durationLabel(7_000))
        assertEquals("0:07", durationLabel(6_600))
        assertEquals("0:01", durationLabel(200))
        assertEquals("0:00", durationLabel(0))
        assertEquals("0:16", durationLabel(16_000))
        assertEquals("1:05", durationLabel(65_000))
    }

    @Test
    fun `FakeVoiceTone - 16 kHz 모노 16-bit WAV 머리와 길이`() {
        val pcm = FakeVoiceTone.samples()
        val wav = FakeVoiceTone.wav(pcm)
        assertEquals(44 + pcm.size * 2, wav.size)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        assertEquals("data", String(wav, 36, 4, Charsets.US_ASCII))
        val header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1.toShort(), header.getShort(20)) // PCM
        assertEquals(1.toShort(), header.getShort(22)) // 모노
        assertEquals(16_000, header.getInt(24))
        assertEquals(16.toShort(), header.getShort(34))
        assertEquals(pcm.size * 2, header.getInt(40))
        val durationMs = pcm.size * 1000 / FakeVoiceTone.SAMPLE_RATE
        assertTrue("1~2초 신호음: $durationMs", durationMs in 1_000..2_000)
        assertTrue("무음이 아니다", pcm.any { it > 1_000 })
    }
}
