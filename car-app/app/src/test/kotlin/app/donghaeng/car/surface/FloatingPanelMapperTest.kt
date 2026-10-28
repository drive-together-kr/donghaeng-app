package app.donghaeng.car.surface

import app.donghaeng.car.domain.session.GroupSession
import app.donghaeng.car.domain.session.ReceivedGroupEvent
import app.donghaeng.car.feature.drive.DriveFormat
import app.donghaeng.car.feature.drive.FloatingPanelSamples
import app.donghaeng.car.feature.drive.PttUi
import app.donghaeng.car.feature.drive.Tone
import app.donghaeng.protocol.Energy
import app.donghaeng.protocol.Eta
import app.donghaeng.protocol.GroupEvent
import app.donghaeng.protocol.GroupState
import app.donghaeng.protocol.GroupTopics
import app.donghaeng.protocol.MemberState
import app.donghaeng.protocol.MemberStatus
import app.donghaeng.protocol.Place
import app.donghaeng.protocol.Severity
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** 플로팅 패널 매퍼 — 간격 부호·단위, 정렬, 저잔량, 경고, 이벤트 톤·10초 창 */
class FloatingPanelMapperTest {

    private val zone: ZoneId = ZoneOffset.ofHours(9)
    private val session = GroupSession("grp_1", "강릉 가족 여행", "veh_A", Place(37.7719, 128.9479, "경포해변"), GroupTopics("s", "e"), 0L)
    private val now = 1_700_000_000_000L

    /** 오늘 13:12 (KST) epoch ms */
    private fun at(h: Int, m: Int): Long = LocalDateTime.of(2026, 10, 28, h, m).atZone(zone).toInstant().toEpochMilli()

    private fun member(
        vid: String, nick: String, dist: Int?, online: Boolean = true, status: MemberStatus = MemberStatus.DRIVING,
        destTs: Long? = null, fuel: Int? = null, soc: Int? = null, color: String = "#2E7DFF",
    ) = MemberState(vid, nick, color, online, 37.0, 127.0, 90f, 80f, 500, destTs?.let { Eta(it, 10_000, 600) }, Energy(fuel, soc), dist, status)

    private fun event(type: String, source: String?, ageMs: Long, speech: String? = "$type 문구", id: String = "e_${type}_$ageMs") =
        ReceivedGroupEvent(GroupEvent(id, type, source, Severity.INFO, speech, JsonObject(emptyMap())), envelopeTsMs = now - ageMs, receivedAtMs = now - ageMs)

    private fun map(members: List<MemberState>, events: List<ReceivedGroupEvent> = emptyList(), me: String? = "veh_ME") =
        FloatingPanelMapper.map(session, GroupState("grp_1", "veh_A", members), me, events, PttUi(), connected = true, nowMs = now, zone = zone)

    @Test
    fun `other cars are listed leader first with gaps relative to me - minus is ahead, plus is behind, metres under 1 km`() {
        val ui = map(
            listOf(
                member("veh_C", "둘째 차", dist = 6_900, destTs = at(14, 35), fuel = 12),
                member("veh_ME", "내 차", dist = 1_200),
                member("veh_A", "아빠 차", dist = 0, destTs = at(14, 28)),
                member("veh_B", "삼촌", dist = 1_550, destTs = at(14, 31)),
            ),
        )
        assertEquals("강릉 가족 여행", ui.groupName)
        assertEquals("경포해변 도착", ui.etaLabel)
        assertEquals("목적지 이름만 따로 — 머리줄이 이름만 말줄임하고 ' 도착' 은 남긴다", "경포해변", ui.etaPlace)
        assertEquals(listOf("아빠", "삼촌", "둘째"), ui.cars.map { it.name })   // 내 차 제외, " 차" 뗌, 선두부터
        val (a, b, c) = ui.cars
        assertEquals("앞 1.2" to "km", a.gapText to a.gapUnit)
        assertEquals("뒤 350" to "m", b.gapText to b.gapUnit)
        assertEquals("뒤 5.7" to "km", c.gapText to c.gapUnit)
        assertEquals("14:28", a.etaText)
        assertEquals("14:31", b.etaText)
        assertEquals(0xFF2E7DFFL, a.colorArgb)
        assertFalse(a.warn); assertFalse(b.warn)
        assertTrue("3 km 넘게 벌어짐", c.warn)
        assertTrue("연료 12% ≤ 15", c.lowEnergy)
        assertFalse(a.lowEnergy)
        assertEquals(0, ui.moreCount)
    }

    @Test
    fun `unknown distance or eta shows a dash and sorts last, offline cars warn`() {
        val ui = map(
            listOf(
                member("veh_ME", "나", dist = null),
                member("veh_X", "막내", dist = null, online = false),
                member("veh_A", "아빠", dist = 0),
                member("veh_B", "엄마", dist = 800, status = MemberStatus.OFFLINE),
            ),
        )
        assertEquals(listOf("아빠", "엄마", "막내"), ui.cars.map { it.name })
        ui.cars.forEach { assertEquals("내 거리 모름 → —", DriveFormat.DASH, it.gapText); assertEquals("", it.gapUnit); assertEquals(DriveFormat.DASH, it.etaText) }
        assertFalse(ui.cars[0].warn)
        assertTrue(ui.cars[1].offline); assertTrue(ui.cars[1].warn)
        assertTrue(ui.cars[2].offline); assertTrue(ui.cars[2].warn)
    }

    @Test
    fun `the header keeps the whole group name and destination - only the panel layout ellipsizes the group name`() {
        val long = session.copy(name = "아빠 차와 같이 가기", destination = Place(37.7719, 128.9479, "강릉 경포해변 중앙광장 공영주차장"))
        val ui = FloatingPanelMapper.map(long, GroupState("grp_1", "veh_A", emptyList()), "veh_ME", emptyList(), PttUi(), connected = true, nowMs = now, zone = zone)
        assertEquals("아빠 차와 같이 가기", ui.groupName)
        assertEquals("강릉 경포해변 중앙광장 공영주차장", ui.etaPlace)
        assertEquals("강릉 경포해변 중앙광장 공영주차장 도착", ui.etaLabel)
        // 시안 변형: panel_worst = 시연 기본 그룹 이름, panel_longdest = 상한에 걸리는 긴 목적지
        assertEquals("아빠 차와 같이 가기", FloatingPanelSamples.of("worst").groupName)
        assertEquals("경포해변 도착", FloatingPanelSamples.of("worst").etaLabel)
        assertTrue(FloatingPanelSamples.of("longdest").etaPlace.length > 10)
    }

    @Test
    fun `more than four other cars show four rows and the rest as a count`() {
        val others = (1..6).map { member("veh_$it", "차$it", dist = it * 100) }
        val ui = map(others + member("veh_ME", "나", dist = 0))
        assertEquals(4, ui.cars.size)
        assertEquals(2, ui.moreCount)
        assertEquals(listOf("차1", "차2", "차3", "차4"), ui.cars.map { it.name })
        assertEquals("뒤 100", ui.cars[0].gapText)
    }

    @Test
    fun `low energy uses the lower of fuel and battery`() {
        assertTrue(FloatingPanelMapper.isLowEnergy(Energy(fuelPct = 60, socPct = 15)))
        assertFalse(FloatingPanelMapper.isLowEnergy(Energy(fuelPct = 16)))
        assertFalse(FloatingPanelMapper.isLowEnergy(Energy()))
        assertFalse(FloatingPanelMapper.isLowEnergy(null))
    }

    @Test
    fun `the event line is the newest event from another car within 10 seconds with a tone by type`() {
        val members = listOf(member("veh_ME", "나", dist = 0), member("veh_A", "아빠", dist = 0))
        assertNull(map(members).event)
        // 11초 전 = 창 밖, 내 차 이벤트 제외
        assertNull(map(members, listOf(event("HARD_BRAKE", "veh_A", ageMs = 11_000), event("LOW_ENERGY", "veh_ME", ageMs = 1_000))).event)

        val ui = map(members, listOf(event("LOW_ENERGY", "veh_A", ageMs = 9_000), event("HARD_BRAKE", "veh_A", ageMs = 2_000), event("MEMBER_JOINED", "veh_ME", ageMs = 500)))
        assertEquals("HARD_BRAKE 문구", ui.event!!.text)
        assertEquals(Tone.DANGER, ui.event!!.tone)
        assertEquals(2, ui.event!!.ageS)

        fun toneOf(type: String) = map(members, listOf(event(type, "veh_A", ageMs = 0))).event!!.tone
        assertEquals(Tone.DANGER, toneOf("AIRBAG_DEPLOYED"))
        assertEquals(Tone.CAUTION, toneOf("LOW_ENERGY"))
        assertEquals(Tone.CAUTION, toneOf("MEMBER_OFFLINE"))
        assertEquals(Tone.CAUTION, toneOf("DEVIATION"))
        assertEquals(Tone.INFO, toneOf("WAYPOINT_ARRIVED"))
        assertEquals(Tone.INFO, toneOf("PASSENGER_MESSAGE"))
        // 문구 없는 이벤트는 종류 라벨
        assertEquals("경유지 도착", map(members, listOf(event("WAYPOINT_ARRIVED", "veh_A", ageMs = 0, speech = null))).event!!.text)
    }

    @Test
    fun `gap and distance formatting`() {
        assertEquals("앞 990" to "m", DriveFormat.gap(-994).let { it.text to it.unit })
        assertEquals("앞 1.0" to "km", DriveFormat.gap(-995).let { it.text to it.unit })
        assertEquals("뒤 9.9 km", DriveFormat.gap(9_940).full)
        assertEquals("뒤 10 km", DriveFormat.gap(9_960).full)
        assertEquals("뒤 12 km", DriveFormat.gap(12_400).full)
        assertEquals("앞 29 km", DriveFormat.gap(-28_500).full)
        assertEquals("0 m", DriveFormat.gap(0).full)
        assertEquals("뒤 120 km", DriveFormat.gap(120_000).full)
        assertEquals("—", DriveFormat.gap(null).full)
        assertEquals("9.5 km", DriveFormat.distance(9_500))
        assertEquals("144 km", DriveFormat.distance(144_200))
        assertEquals("350 m", DriveFormat.distance(347))
        assertEquals("1시간 05분", DriveFormat.duration(3_900))
        assertEquals("9분", DriveFormat.duration(570))
        assertEquals("9.5 km · 9분", DriveFormat.remain(9_500, 570))
    }

    @Test
    fun `events replayed after a reconnect are judged by the envelope time on the server clock, not by arrival`() {
        val members = listOf(member("veh_ME", "나", dist = 0), member("veh_A", "아빠", dist = 0))
        fun replayed(id: String, type: String, envelopeAgeMs: Long) =
            ReceivedGroupEvent(GroupEvent(id, type, "veh_A", Severity.INFO, "$type 문구", JsonObject(emptyMap())), envelopeTsMs = now - envelopeAgeMs, receivedAtMs = now)
        // 방금 한꺼번에 받았지만 61초·5분 전에 생긴 이벤트 → 소식 줄 없음
        assertNull(map(members, listOf(replayed("a", "HARD_BRAKE", 61_000), replayed("b", "LOW_ENERGY", 300_000))).event)
        // 60초 안의 것은 뜨고, 나이는 봉투 ts 기준(받은 시각 기준이면 0초)
        val ui = FloatingPanelMapper.map(
            session, GroupState("grp_1", "veh_A", members), "veh_ME",
            listOf(replayed("a", "HARD_BRAKE", 61_000), replayed("c", "LOW_ENERGY", 45_000), replayed("d", "MEMBER_JOINED", 50_000)),
            PttUi(), connected = true, nowMs = now, zone = zone, serverNowMs = now,
        )
        assertEquals("발생 시각이 가장 최근인 것", "LOW_ENERGY 문구", ui.event!!.text)
        assertEquals(45, ui.event!!.ageS)
        // 서버 시계가 2초 앞이면 그만큼 더 오래된 것으로 본다
        val skewed = FloatingPanelMapper.recentEvent(listOf(replayed("e", "LOW_ENERGY", 59_000)), "veh_ME", nowMs = now, serverNowMs = now + 2_000)
        assertNull(skewed)
    }
}
