package app.donghaeng.car.feature.drive

import app.donghaeng.car.domain.session.GroupSession
import app.donghaeng.car.feature.groupdrive.ConnState
import app.donghaeng.car.feature.groupdrive.GroupDriveUiState
import app.donghaeng.car.feature.groupdrive.MemberUi
import app.donghaeng.protocol.GroupTopics
import app.donghaeng.protocol.MemberStatus
import app.donghaeng.protocol.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * 정차 대시보드 화면 상태(UI 트랙 2026-11): 같은 자리 "바로 옆"·"모두 함께"(4-2), 그룹 도착에서 끊긴·도착한 차 제외, 도착 예상 빈 상태(4-3),
 * 실시간 연결이 끊긴 채 오래된 값(E038), 내 차 잔량 줄(5-3), 이벤트 종류별 아이콘(4-4).
 */
class DashboardStatesTest {

    private val zone: ZoneId = ZoneOffset.ofHours(9)
    private val now = LocalDateTime.of(2026, 10, 28, 13, 0).atZone(zone).toInstant().toEpochMilli()
    private val session = GroupSession("grp_1", "강릉 가족 여행", "veh_A", Place(37.7719, 128.9479, "경포해변"), GroupTopics("s", "e"), joinedAtMs = now)

    private fun at(h: Int, m: Int): Long = LocalDateTime.of(2026, 10, 28, h, m).atZone(zone).toInstant().toEpochMilli()

    private fun m(
        vid: String, nick: String, dist: Int?, me: Boolean = false, leader: Boolean = false, destTs: Long? = null,
        online: Boolean = true, status: MemberStatus = MemberStatus.DRIVING, soc: Int? = null, fuel: Int? = null,
    ) = MemberUi(vid, nick, 0xFF2E7DFFL, leader, me, online, status, 37.2, 127.3, 90f, 80f, dist, null, null, 1, destTs, null, null, fuel, soc)

    private fun state(members: List<MemberUi>, connection: ConnState = ConnState.CONNECTED, snapshotAgeS: Long? = 1) = GroupDriveUiState(
        session = session, members = members, me = members.firstOrNull { it.isMe }, leader = members.firstOrNull { it.isLeader },
        connection = connection, snapshotAgeS = snapshotAgeS, nowMs = now,
    )

    @Test
    fun `cars at the same spot read as right beside me and the whole group as together`() {
        val ui = DriveDashboardMapper.map(state(listOf(m("veh_A", "아빠 차", 0, leader = true), m("veh_ME", "내 차", 0, me = true), m("veh_B", "둘째 차", 30))), zone)!!
        assertEquals("모두 함께", ui.formation.formationLengthText)
        assertEquals("바로 옆", ui.formation.cars.first { it.name == "아빠 차" }.gapToMeText)
        assertEquals("바로 옆", ui.formation.cars.first { it.name == "둘째 차" }.gapToMeText)
        // 50 m 이상 떨어지면 다시 숫자
        val spread = DriveDashboardMapper.map(state(listOf(m("veh_A", "아빠 차", 0, leader = true), m("veh_ME", "내 차", 400, me = true))), zone)!!
        assertEquals("대열 길이 400 m", spread.formation.formationLengthText)
    }

    @Test
    fun `group arrival leaves out offline and arrived cars so their old eta does not decide it`() {
        val members = listOf(
            m("veh_A", "아빠 차", 0, leader = true, destTs = at(14, 28)),
            m("veh_ME", "내 차", 500, me = true, destTs = at(14, 30)),
            m("veh_B", "끊긴 차", 900, destTs = at(15, 40), online = false, status = MemberStatus.OFFLINE),
            m("veh_C", "도착한 차", 0, destTs = at(15, 50), status = MemberStatus.ARRIVED),
        )
        val a = DriveDashboardMapper.map(state(members), zone)!!.arrival
        assertEquals("14:30", a.groupArrivalText)
        assertEquals("마지막: 내 차", a.lastCarText)
        assertNull(a.noEtaPlace)
    }

    @Test
    fun `without any eta the arrival card shows the empty state with the destination name`() {
        val a = DriveDashboardMapper.map(state(listOf(m("veh_A", "아빠 차", 0, leader = true), m("veh_ME", "내 차", 300, me = true))), zone)!!.arrival
        assertEquals("경포해변", a.noEtaPlace)
        assertEquals(DriveFormat.DASH, a.groupArrivalText)
        // 내 ETA 만 있어도 빈 상태가 아니다
        val mine = DriveDashboardMapper.map(state(listOf(m("veh_A", "아빠 차", 0, leader = true), m("veh_ME", "내 차", 300, me = true, destTs = at(14, 0)))), zone)!!.arrival
        assertNull(mine.noEtaPlace)
    }

    @Test
    fun `while disconnected an old group state is marked stale, a fresh one or a connected one is not`() {
        val members = listOf(m("veh_A", "아빠 차", 0, leader = true), m("veh_ME", "내 차", 300, me = true))
        val stale = DriveDashboardMapper.map(state(members, ConnState.RECONNECTING, snapshotAgeS = 125), zone)!!
        assertEquals("2분 전", stale.staleText)
        assertFalse(stale.connected)
        assertFalse(stale.offline)
        val offline = DriveDashboardMapper.map(state(members, ConnState.OFFLINE, snapshotAgeS = 20), zone)!!
        assertEquals("20초 전", offline.staleText)
        assertTrue(offline.offline)
        assertNull("끊긴 지 얼마 안 됨", DriveDashboardMapper.map(state(members, ConnState.RECONNECTING, snapshotAgeS = 10), zone)!!.staleText)
        assertNull("연결돼 있으면 오래돼도 표시 안 함", DriveDashboardMapper.map(state(members, ConnState.CONNECTED, snapshotAgeS = 300), zone)!!.staleText)
    }

    @Test
    fun `my car line shows ev battery first, then fuel`() {
        val ev = DriveDashboardMapper.map(state(listOf(m("veh_ME", "내 차", 0, me = true, leader = true, soc = 82, fuel = 40))), zone)!!.myDrive
        assertEquals(82, ev.myEnergyPct)
        assertTrue(ev.myEnergyEv)
        val ice = DriveDashboardMapper.map(state(listOf(m("veh_ME", "내 차", 0, me = true, leader = true, fuel = 40))), zone)!!.myDrive
        assertEquals(40, ice.myEnergyPct)
        assertFalse(ice.myEnergyEv)
    }

    @Test
    fun `event icons follow the event kind and stop or resume read as neutral, not done`() {
        assertEquals(EventIconKind.PAUSE, EventIcons.kindOf("STOPPED", DriveFormat.eventTone("STOPPED")))
        assertEquals(Tone.NEUTRAL, DriveFormat.eventTone("STOPPED"))
        assertEquals(Tone.NEUTRAL, DriveFormat.eventTone("RESUMED"))
        assertEquals(EventIconKind.RESUME, EventIcons.kindOf("RESUMED", Tone.NEUTRAL))
        assertEquals(EventIconKind.ROUTE, EventIcons.kindOf("ROUTE_CHANGED", Tone.INFO))
        assertEquals(EventIconKind.FLAG, EventIcons.kindOf("DESTINATION_ARRIVED", Tone.INFO))
        assertEquals(EventIconKind.PERSON_ADD, EventIcons.kindOf("MEMBER_JOINED", Tone.INFO))
        assertEquals(EventIconKind.CHAT, EventIcons.kindOf("PASSENGER_MESSAGE", Tone.INFO))
        assertEquals(EventIconKind.MIC, EventIcons.kindOf("VOICE_MESSAGE", Tone.INFO))
        assertEquals(EventIconKind.WARNING, EventIcons.kindOf("HARD_BRAKE", Tone.DANGER))
        assertEquals(EventIconKind.INFO, EventIcons.kindOf("SOMETHING_NEW", Tone.INFO))
        assertEquals(EventIconKind.WARNING, EventIcons.kindOf(null, Tone.CAUTION))
    }

    @Test
    fun `panel clock without an eta is a blank clock, not a dash`() {
        assertEquals("--:--", DriveFormat.panelClock(null, zone))
        assertEquals("14:28", DriveFormat.panelClock(at(14, 28), zone))
    }
}
