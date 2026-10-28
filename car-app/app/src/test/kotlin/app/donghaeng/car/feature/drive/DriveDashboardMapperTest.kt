package app.donghaeng.car.feature.drive

import app.donghaeng.car.domain.session.GroupSession
import app.donghaeng.car.domain.session.ReceivedGroupEvent
import app.donghaeng.car.feature.groupdrive.ConnState
import app.donghaeng.car.feature.groupdrive.GroupDriveUiState
import app.donghaeng.car.feature.groupdrive.MemberUi
import app.donghaeng.car.surface.SurfaceMode
import app.donghaeng.protocol.GroupEvent
import app.donghaeng.protocol.GroupTopics
import app.donghaeng.protocol.MemberStatus
import app.donghaeng.protocol.Place
import app.donghaeng.protocol.Severity
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** 정차 대시보드 매퍼 — 대열 오프셋·그룹 도착·경유지 유무·연속 주행·이벤트 */
class DriveDashboardMapperTest {

    private val zone: ZoneId = ZoneOffset.ofHours(9)
    private val now = LocalDateTime.of(2026, 10, 28, 13, 0).atZone(zone).toInstant().toEpochMilli()
    private val session = GroupSession("grp_1", "강릉 가족 여행", "veh_A", Place(37.7719, 128.9479, "경포해변"), GroupTopics("s", "e"), joinedAtMs = now - (1 * 3_600 + 42 * 60) * 1_000L)

    private fun at(h: Int, m: Int): Long = LocalDateTime.of(2026, 10, 28, h, m).atZone(zone).toInstant().toEpochMilli()

    private fun m(
        vid: String, nick: String, dist: Int?, me: Boolean = false, leader: Boolean = false, destTs: Long? = null, remainM: Int? = null, remainS: Int? = null,
        fuel: Int? = null, online: Boolean = true, lat: Double? = 37.2, lon: Double? = 127.3,
    ) = MemberUi(vid, nick, 0xFF2E7DFFL, leader, me, online, MemberStatus.DRIVING, lat, lon, 90f, 80f, dist, null, null, 1, destTs, remainM, remainS, fuel, null)

    private val members = listOf(
        m("veh_A", "아빠 차", dist = 0, leader = true, destTs = at(14, 28)),
        m("veh_ME", "내 차", dist = 1_200, me = true, destTs = at(14, 30), remainM = 9_500, remainS = 570),
        m("veh_B", "삼촌 차", dist = 3_300, destTs = at(14, 31)),
        m("veh_C", "둘째 차", dist = 6_900, destTs = at(14, 35), fuel = 12),
        m("veh_D", "막내 차", dist = null, online = false),
    )

    private fun state(
        nextStop: Place? = null, driving: Boolean = false, events: List<ReceivedGroupEvent> = emptyList(), mode: SurfaceMode = SurfaceMode.PANEL,
        joined: GroupSession = session, drivenMs: Long? = (1 * 3_600 + 42 * 60) * 1_000L,
    ) = GroupDriveUiState(
        session = joined, members = members, me = members[1], leader = members[0], connection = ConnState.CONNECTED, isDriving = driving, nowMs = now,
        nextStop = nextStop, speedLimitKph = 100, surfaceMode = mode, recentEvents = events, latencyMs = 12, continuousDriveMs = drivenMs,
    )

    private fun event(type: String, ageMs: Long, speech: String? = "$type 문구") =
        ReceivedGroupEvent(GroupEvent("e_$ageMs", type, "veh_A", Severity.INFO, speech, JsonObject(emptyMap())), envelopeTsMs = now - ageMs, receivedAtMs = now - ageMs)

    @Test
    fun `no session means no dashboard`() {
        assertNull(DriveDashboardMapper.map(GroupDriveUiState(), zone))
    }

    @Test
    fun `formation offsets are measured from the rearmost car and gaps are relative to me`() {
        val ui = DriveDashboardMapper.map(state(), zone)!!
        val f = ui.formation
        assertEquals("위치 모르는 차는 막대에서 뺀다", listOf("둘째 차", "삼촌 차", "내 차", "아빠 차"), f.cars.map { it.name })
        assertEquals(listOf(0f, 3.6f, 5.7f, 6.9f), f.cars.map { it.offsetKm })
        assertEquals("대열 길이 6.9 km", f.formationLengthText)
        assertEquals("경포해변 9.5 km", f.destinationText)
        val byName = f.cars.associateBy { it.name }
        assertNull(byName["내 차"]!!.gapToMeText)
        assertTrue(byName["내 차"]!!.isMe)
        assertEquals("앞 1.2 km", byName["아빠 차"]!!.gapToMeText)
        assertEquals("뒤 2.1 km", byName["삼촌 차"]!!.gapToMeText)
        assertEquals("뒤 5.7 km", byName["둘째 차"]!!.gapToMeText)
        assertTrue("둘째 ↔ 삼촌 3.6 km > 3 km", byName["둘째 차"]!!.gapWarn)
        assertFalse(byName["삼촌 차"]!!.gapWarn)
        assertTrue(byName["둘째 차"]!!.lowEnergy)
        assertTrue(byName["아빠 차"]!!.isLeader)
        assertNull("경유지 없으면 막대 끝 = 선두 + 2 km", f.nextStopName)
        assertNull(f.nextStopOffsetKm)
    }

    @Test
    fun `group arrival is the latest member eta with that car, my arrival is mine`() {
        val a = DriveDashboardMapper.map(state(), zone)!!.arrival
        assertEquals("14:35", a.groupArrivalText)
        assertEquals("마지막: 둘째 차", a.lastCarText)
        assertEquals("14:30", a.myArrivalText)
        assertEquals("9.5 km · 9분", a.myRemainText)
        // 내가 가장 늦으면 닉네임 대신 "내 차" — 닉네임을 "내 차" 가 아닌 값으로 둬야 isMe 분기가 실제로 검증된다
        val meLast = members.map { if (it.isMe) it.copy(nickname = "DemoCarA", etaDestTs = at(14, 50)) else it }
        val b = DriveDashboardMapper.map(state().copy(members = meLast, me = meLast[1]), zone)!!.arrival
        assertEquals("14:50", b.groupArrivalText)
        assertEquals("마지막: 내 차", b.lastCarText)
    }

    @Test
    fun `next stop card and rest suggestion appear only with a waypoint`() {
        val without = DriveDashboardMapper.map(state(), zone)!!
        assertNull(without.nextStop)
        assertNull(without.myDrive.restSuggestionText)

        val stop = Place(37.30, 127.30, "덕평휴게소")
        val with = DriveDashboardMapper.map(state(nextStop = stop), zone)!!
        assertNotNull(with.nextStop)
        val card = with.nextStop!!
        assertEquals("덕평휴게소", card.name)
        assertEquals("11 km", card.distanceText)   // 37.2,127.3 → 37.3,127.3 ≈ 11.1 km
        assertNull(card.reachable)
        assertEquals(DriveFormat.DASH, card.reachableText)
        assertTrue(card.perCar.isEmpty())
        assertEquals("다음 휴식 추천: 덕평휴게소", with.myDrive.restSuggestionText)
        assertEquals("덕평휴게소", with.formation.nextStopName)
        assertEquals(5.7f + 11.12f, with.formation.nextStopOffsetKm!!, 0.05f)
    }

    @Test
    fun `my drive line - speed limit, continuous driving from the drive timer with a warning after two hours`() {
        val ui = DriveDashboardMapper.map(state(), zone)!!
        assertEquals(100, ui.myDrive.speedLimitKph)
        assertEquals("연속 주행 1시간 42분", ui.myDrive.drivingTimeText)
        assertFalse(ui.myDrive.drivingTimeWarn)
        assertNull(ui.myDrive.nextTurnText)
        val long = DriveDashboardMapper.map(state(drivenMs = 2 * 3_600_000L), zone)!!
        assertTrue(long.myDrive.drivingTimeWarn)
        assertEquals("연속 주행 2시간 00분", long.myDrive.drivingTimeText)
        // 합류 시각과 무관: 밤새 이어진 그룹이라도 쉬고 있으면(타이머 0) 경고 없음(2026-10-28 실측 "18시간 27분" 회귀)
        val rested = DriveDashboardMapper.map(state(joined = session.copy(joinedAtMs = now - 18 * 3_600_000L), drivenMs = 0L), zone)!!
        assertEquals("연속 주행 0분", rested.myDrive.drivingTimeText)
        assertFalse(rested.myDrive.drivingTimeWarn)
        assertEquals("연속 주행 " + DriveFormat.DASH, DriveDashboardMapper.map(state(drivenMs = null), zone)!!.myDrive.drivingTimeText)
    }

    @Test
    fun `event band only within 10 s, recent list keeps five, parked and overlay notice flags`() {
        val parked = DriveDashboardMapper.map(state(events = listOf(event("LOW_ENERGY", 11_000))), zone)!!
        assertNull(parked.event)
        assertTrue(parked.parked)
        assertFalse(parked.overlayNotice)
        assertEquals(12, parked.latencyMs)
        assertTrue(parked.connected)
        assertTrue(parked.presets.isEmpty())

        val evs = listOf(event("HARD_BRAKE", 3_000), event("LOW_ENERGY", 9_000)) + (1..6).map { event("MEMBER_JOINED", 60_000L * it) }
        val driving = DriveDashboardMapper.map(state(driving = true, events = evs, mode = SurfaceMode.CARDS_ONLY), zone)!!
        assertEquals("HARD_BRAKE 문구", driving.event!!.text)
        assertEquals(Tone.DANGER, driving.event!!.tone)
        assertEquals(3, driving.event!!.ageS)
        assertFalse(driving.parked)
        assertTrue(driving.overlayNotice)
        assertEquals(5, driving.recentEvents.size)
        assertEquals("3초 전", driving.recentEvents.first().whenText)
        assertEquals("3분 전", driving.recentEvents.last().whenText)   // 3초·9초·1분·2분·3분 전
    }

    private fun received(id: String, type: String, source: String?, envelopeAgeMs: Long, receivedAgeMs: Long = envelopeAgeMs) =
        ReceivedGroupEvent(GroupEvent(id, type, source, Severity.INFO, "$type 문구", JsonObject(emptyMap())), envelopeTsMs = now - envelopeAgeMs, receivedAtMs = now - receivedAgeMs)

    @Test
    fun `my own car events are left out of the band and the recent list like the panel and cards`() {
        val evs = listOf(
            received("mine", "HARD_BRAKE", "veh_ME", 1_000),
            received("other", "LOW_ENERGY", "veh_A", 5_000),
            received("server", "MEMBER_OFFLINE", null, 70_000),
        )
        val ui = DriveDashboardMapper.map(state(events = evs), zone)!!
        assertEquals("내 차 급제동은 띠에 없다", "LOW_ENERGY 문구", ui.event!!.text)
        assertEquals(listOf("LOW_ENERGY 문구", "MEMBER_OFFLINE 문구"), ui.recentEvents.map { it.text })
        // me 가 아직 스냅샷에 없어도 자격 증명의 내 차 id 로 거른다
        val noSnapshot = DriveDashboardMapper.map(state(events = evs).copy(me = null, myVehicleId = "veh_ME"), zone)!!
        assertEquals(2, noSnapshot.recentEvents.size)
    }

    @Test
    fun `events the broker redelivers after a reconnect do not show as just now and their age uses the envelope on the server clock`() {
        // 모두 방금(0초 전) 받았지만 실제로는 3분·61초·20초 전에 생김. 서버 시계는 기기보다 1초 앞
        val evs = listOf(
            received("a", "HARD_BRAKE", "veh_A", envelopeAgeMs = 180_000, receivedAgeMs = 0),
            received("b", "LOW_ENERGY", "veh_B", envelopeAgeMs = 61_000, receivedAgeMs = 0),
            received("c", "MEMBER_JOINED", "veh_C", envelopeAgeMs = 20_000, receivedAgeMs = 0),
        )
        val ui = DriveDashboardMapper.map(state(events = evs).copy(serverNowMs = now + 1_000), zone)!!
        assertEquals("60초 안의 것만 띠 후보", "MEMBER_JOINED 문구", ui.event!!.text)
        assertEquals(21, ui.event!!.ageS)
        assertEquals(listOf("21초 전", "1분 전", "3분 전"), ui.recentEvents.map { it.whenText })
        assertEquals("발생 시각 순", listOf("MEMBER_JOINED 문구", "LOW_ENERGY 문구", "HARD_BRAKE 문구"), ui.recentEvents.map { it.text })

        val stale = DriveDashboardMapper.map(state(events = evs.take(2)), zone)!!
        assertNull("다 오래되면 띠 없음", stale.event)
        assertEquals(2, stale.recentEvents.size)
    }

    @Test
    fun `bottom latency uses the T31 text with event and state when present`() {
        val ui = DriveDashboardMapper.map(state().copy(latencyText = "이벤트 12 ms · 상태 30 ms"), zone)!!
        assertEquals("이벤트 12 ms · 상태 30 ms", ui.latencyText)
        assertEquals(12, ui.latencyMs)
        assertNull(DriveDashboardMapper.map(state(), zone)!!.latencyText)
    }
}
