package app.donghaeng.companion.demo.donghaeng

import app.donghaeng.companion.demo.MemoryDemoStorage
import app.donghaeng.companion.sdk.DongHaengException
import app.donghaeng.companion.sdk.GroupJoinResult
import app.donghaeng.companion.sdk.GroupMember
import app.donghaeng.companion.sdk.GroupSnapshot
import app.donghaeng.companion.sdk.MemberRole
import app.donghaeng.companion.sdk.MyVehicle
import app.donghaeng.companion.sdk.PairResult
import app.donghaeng.protocol.GroupEvent
import app.donghaeng.protocol.GroupEventType
import app.donghaeng.protocol.MemberState
import app.donghaeng.protocol.MemberStatus
import app.donghaeng.protocol.Severity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 호스트 상태 보관소 — 관찰 재시도·종료·나감(D021·D022·D028), 진행 중 그룹 복원(D024), 보낸 차·멱등 키(D025·D085),
 * 그룹별 첫 스냅샷(D083), 지난 그룹 목적지(D087·D082), 시연 기록·참여 설정(D092·D078).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DongHaengStoreTest {

    private val now = 1_793_000_000_000L
    private val storage = MemoryDemoStorage()
    private lateinit var storeScope: CoroutineScope

    private val carA = MyVehicle("veh_a", "아빠 차", "KIA", "EV9", isPrimary = true)
    private val carB = MyVehicle("veh_b", "둘째 차", "KIA", "K5", isPrimary = false)

    /** 호출을 기록하고 정한 대로 답하는 게이트웨이 */
    private class ScriptedGateway : DongHaengGateway {
        var vehicles = listOf<MyVehicle>()
        var observeCalls = 0
        var observe: (groupId: String, call: Int) -> Flow<GroupSnapshot> = { _, _ -> flow { awaitCancellation() } }
        val sent = mutableListOf<Triple<String, String?, String>>()
        override suspend fun listMyVehicles(): List<MyVehicle> = vehicles
        override suspend fun previewInvite(qrPayload: String): InvitePreview? = null
        override suspend fun joinGroup(qrPayload: String, vehicleId: String?): GroupJoinResult {
            val groupId = qrPayload.substringAfterLast("/").replace("inv_", "grp_")
            return GroupJoinResult(
                groupId,
                "그룹 $groupId",
                listOf(GroupMember("veh_a", "아빠 차", "#2E7DFF", MemberRole.LEADER), GroupMember("veh_b", "둘째 차", "#FF6B2E", MemberRole.MEMBER)),
            )
        }
        override suspend fun sendPassengerMessage(groupId: String, text: String, fromVehicleId: String?, idempotencyKey: String) {
            sent += Triple(text, fromVehicleId, idempotencyKey)
        }
        override suspend fun pairVehicle(qrPayload: String, nickname: String): PairResult = PairResult("veh_new", nickname)
        override fun observeGroup(groupId: String): Flow<GroupSnapshot> = observe(groupId, ++observeCalls)
    }

    private fun member(id: String) = MemberState(
        vehicleId = id, nickname = id, color = "#2E7DFF", online = true, lat = null, lon = null, heading = null, speedKph = null,
        ageMs = null, eta = null, energy = null, distanceToLeaderM = null, status = MemberStatus.DRIVING,
    )

    private fun event(id: String, type: GroupEventType) = GroupEvent(id, type.name, "veh_a", Severity.INFO, "소식 $id", JsonObject(emptyMap()))

    private fun snap(groupId: String, vararg events: GroupEvent, leader: String? = "veh_a") =
        GroupSnapshot(groupId, listOf(member("veh_a"), member("veh_b")), events.toList(), leaderVehicleId = leader)

    private fun once(vararg snaps: GroupSnapshot): Flow<GroupSnapshot> = flow {
        snaps.forEach { emit(it) }
        awaitCancellation()
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher()) // 보관소 기본 범위(Main)가 실제 안드로이드 메인을 찾지 않게
        DongHaengStore.reset()
        DongHaengStore.storage = storage
        DongHaengStore.backoff = { 1_000L }
        DongHaengStore.clock = { now }
        DongHaengStore.appInForeground = true
        DongHaengStore.notifier = null
    }

    @After
    fun tearDown() {
        // 실패해도 Main 을 되돌린다 — 남으면 다른 테스트의 runTest 가 이 가상 시계를 이어 쓴다
        try {
            DongHaengStore.reset()
            if (::storeScope.isInitialized) storeScope.cancel()
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun TestScope.useTestScope() {
        storeScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        DongHaengStore.scope = storeScope
    }

    private fun TestScope.configured(gw: ScriptedGateway, owner: String? = "usr_1", vehicles: List<MyVehicle> = listOf(carA, carB)) {
        gw.vehicles = vehicles
        DongHaengStore.configure(DhMode.SDK, gw, ownerUserId = owner)
        runCurrent()
    }

    // ---------- D021·D022 ----------

    @Test
    fun `D021·D022 - 네트워크 오류 한 번에 관찰이 끝나지 않고, 다시 연결 중을 보이다가 회복한다`() = runTest {
        useTestScope()
        val gw = ScriptedGateway()
        gw.observe = { id, call -> if (call == 1) flow { throw DongHaengException.Api("NETWORK", "서버에 연결할 수 없어요") } else once(snap(id)) }
        configured(gw)

        DongHaengStore.join("http://x/j/inv_1", carB)
        runCurrent()
        assertEquals(GroupLink.RECONNECTING, DongHaengStore.connection.value.link)
        assertNotNull(DongHaengStore.connection.value.message)

        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(GroupLink.LIVE, DongHaengStore.connection.value.link)
        assertNotNull(DongHaengStore.snapshot.value)
        assertNull(DongHaengStore.groupError.value)
        assertEquals(2, gw.observeCalls)
    }

    @Test
    fun `다시 시도 버튼은 실패로 멈춘 관찰을 바로 다시 시작한다`() = runTest {
        useTestScope()
        val gw = ScriptedGateway()
        gw.observe = { id, call -> if (call == 1) flow { throw DongHaengException.Api("FORBIDDEN", "x") } else once(snap(id)) }
        configured(gw)
        DongHaengStore.join("http://x/j/inv_1", carB)
        runCurrent()
        assertEquals(GroupLink.FAILED, DongHaengStore.connection.value.link)

        DongHaengStore.retryObserving()
        runCurrent()
        assertEquals(GroupLink.LIVE, DongHaengStore.connection.value.link)
    }

    // ---------- D028 ----------

    @Test
    fun `D028 - 그룹 종료가 보이면 끝남으로 바꾸고 관찰을 멈추며 메시지를 막는다`() = runTest {
        useTestScope()
        val gw = ScriptedGateway()
        gw.observe = { id, _ -> once(snap(id, event("evt_1", GroupEventType.MEMBER_JOINED)), snap(id, event("evt_1", GroupEventType.MEMBER_JOINED), event("evt_2", GroupEventType.GROUP_ENDED))) }
        configured(gw)
        DongHaengStore.join("http://x/j/inv_1", carB)
        runCurrent()

        assertEquals(GroupLink.ENDED, DongHaengStore.connection.value.link)
        assertTrue(DongHaengStore.connection.value.finished)
        assertNull("다시 띄워도 끝난 그룹을 되살리지 않는다", storage.loadActiveGroup())
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals("더 관찰하지 않는다", 1, gw.observeCalls)
        val error = runCatching { DongHaengStore.sendMessage("배고파요", "key_00000001") }.exceptionOrNull()
        assertEquals("GROUP_ENDED", (error as DongHaengException.Api).code)
        assertTrue(gw.sent.isEmpty())
    }

    @Test
    fun `D028 - 내 차가 나가 404 면 나감으로 바꾸고 다시 시도하지 않는다`() = runTest {
        useTestScope()
        val gw = ScriptedGateway()
        gw.observe = { _, _ -> flow { throw DongHaengException.Api("NOT_FOUND", "그룹을 찾을 수 없어요.") } }
        configured(gw)
        DongHaengStore.join("http://x/j/inv_1", carB)
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(GroupLink.LEFT, DongHaengStore.connection.value.link)
        assertEquals(1, gw.observeCalls)
    }

    @Test
    fun `인증 만료는 다시 로그인 안내, 진행 중 그룹은 남겨 둔다(같은 계정 재로그인 때 이어 가기)`() = runTest {
        useTestScope()
        val gw = ScriptedGateway()
        gw.observe = { _, _ -> flow { throw DongHaengException.Unauthorized() } }
        configured(gw)
        DongHaengStore.join("http://x/j/inv_1", carB)
        runCurrent()

        assertEquals(GroupLink.AUTH_EXPIRED, DongHaengStore.connection.value.link)
        assertNotNull(storage.loadActiveGroup())
    }

    // ---------- D024 ----------

    @Test
    fun `D024 - 진행 중 그룹을 저장해 같은 계정으로 다시 로그인하면 관찰을 이어 간다`() = runTest {
        useTestScope()
        val gw = ScriptedGateway()
        gw.observe = { id, _ -> once(snap(id)) }
        configured(gw)
        DongHaengStore.join("http://x/j/inv_1", carB, destinationName = "경포해변")
        runCurrent()
        assertEquals("grp_1", storage.loadActiveGroup()?.groupId)

        DongHaengStore.reset() // 프로세스 재생성·로그아웃(메모리만 비움)
        assertNull(DongHaengStore.activeGroup.value)
        val gw2 = ScriptedGateway()
        gw2.observe = { id, _ -> once(snap(id)) }
        configured(gw2, owner = "usr_1")

        val restored = DongHaengStore.activeGroup.value
        assertEquals("grp_1", restored?.groupId)
        assertEquals("veh_b", restored?.myVehicleId)
        assertEquals("경포해변", restored?.destinationName)
        assertEquals(1, gw2.observeCalls)
        assertEquals(GroupLink.LIVE, DongHaengStore.connection.value.link)
    }

    @Test
    fun `D024 - 다른 계정으로 로그인하면 저장된 그룹을 되살리지 않고 지운다`() = runTest {
        useTestScope()
        val gw = ScriptedGateway()
        configured(gw)
        DongHaengStore.join("http://x/j/inv_1", carB)
        runCurrent()
        DongHaengStore.reset()

        configured(ScriptedGateway(), owner = "usr_other")
        assertNull(DongHaengStore.activeGroup.value)
        assertNull(storage.loadActiveGroup())
    }

    @Test
    fun `D024 - 같은 그룹 재합류(모듈이 409 를 성공으로)는 소식·본 이벤트를 그대로 둔다`() = runTest {
        useTestScope()
        val gw = ScriptedGateway()
        gw.observe = { id, _ -> once(snap(id, event("evt_1", GroupEventType.MEMBER_JOINED), event("evt_2", GroupEventType.LOW_ENERGY))) }
        configured(gw)
        DongHaengStore.join("http://x/j/inv_1", carB)
        runCurrent()
        val before = DongHaengStore.notifications.value.filter { it.id.startsWith("evt") }.map { it.id }

        DongHaengStore.join("http://x/j/inv_1", carB)
        runCurrent()

        assertEquals(before, DongHaengStore.notifications.value.filter { it.id.startsWith("evt") }.map { it.id })
        assertTrue(DongHaengStore.pastGroups.value.none { it.groupId == "grp_1" })
    }

    // ---------- D025·D085 ----------

    @Test
    fun `D025·D085 - 메시지에 합류시킨 내 차와 사용자 동작의 키를 싣는다`() = runTest {
        useTestScope()
        val gw = ScriptedGateway()
        configured(gw)
        DongHaengStore.join("http://x/j/inv_1", carB)
        runCurrent()

        DongHaengStore.sendMessage("배고파요", "key_00000001")

        assertEquals(Triple("배고파요", "veh_b", "key_00000001"), gw.sent.single())
    }

    @Test
    fun `D025 - 내 차를 확인하지 못했으면 추측한 차를 싣지 않는다(서버 기본에 맡김)`() = runTest {
        useTestScope()
        val gw = ScriptedGateway()
        configured(gw, vehicles = emptyList())
        DongHaengStore.join("http://x/j/inv_1", vehicle = null)
        runCurrent()

        DongHaengStore.sendMessage("배고파요", "key_00000001")

        assertNull(gw.sent.single().second)
    }

    // ---------- D083 ----------

    @Test
    fun `D083 - 두 번째 그룹의 첫 스냅샷 지난 소식은 읽은 것으로, 시스템 알림 없이, 이전 그룹 소식은 지운다`() = runTest {
        useTestScope()
        val notified = mutableListOf<String>()
        DongHaengStore.notifier = { notified += it.id }
        val gw = ScriptedGateway()
        gw.observe = { id, _ ->
            if (id == "grp_1") {
                once(snap(id, event("evt_a1", GroupEventType.MEMBER_JOINED), event("evt_a2", GroupEventType.LOW_ENERGY)))
            } else {
                val old = arrayOf(event("evt_b1", GroupEventType.MEMBER_JOINED), event("evt_b2", GroupEventType.LOW_ENERGY), event("evt_b3", GroupEventType.WAYPOINT_ARRIVED))
                once(snap(id, *old), snap(id, *old, event("evt_b4", GroupEventType.PASSENGER_MESSAGE)))
            }
        }
        configured(gw)
        DongHaengStore.join("http://x/j/inv_1", carB)
        runCurrent()
        DongHaengStore.appInForeground = false

        DongHaengStore.join("http://x/j/inv_2", carB)
        runCurrent()

        val evt = DongHaengStore.notifications.value.filter { it.id.startsWith("evt") }
        assertTrue("그룹 1 소식은 지운다", evt.none { it.id.startsWith("evt_a") })
        assertTrue("지난 소식은 읽음", evt.filter { it.id in setOf("evt_b1", "evt_b2", "evt_b3") }.all { it.read })
        assertFalse("새 소식은 안 읽음", evt.first { it.id == "evt_b4" }.read)
        assertEquals("시스템 알림은 새 소식만", listOf("evt_b4"), notified)
        assertEquals("이전 그룹은 지난 그룹으로(D082)", "grp_1", DongHaengStore.pastGroups.value.first().groupId)
    }

    // ---------- D087·D092·D078 ----------

    @Test
    fun `D087 - 그룹 보기 끝내기는 목적지·마지막 인원으로 지난 그룹에 남기고 저장을 지운다`() = runTest {
        useTestScope()
        val gw = ScriptedGateway()
        gw.observe = { id, _ -> once(snap(id)) }
        configured(gw)
        DongHaengStore.join("http://x/j/inv_1", carB, destinationName = "경포해변")
        runCurrent()

        DongHaengStore.leaveLocally()

        val past = DongHaengStore.pastGroups.value.first()
        assertEquals("grp_1", past.groupId)
        assertEquals("경포해변", past.destinationName)
        assertEquals(2, past.memberCount)
        assertNull(DongHaengStore.activeGroup.value)
        assertNull(storage.loadActiveGroup())
    }

    @Test
    fun `D092 - 실서버 로그인에는 가짜 지난 그룹이 없고, 시연 모드에만 예시, 전체 삭제는 실제로 지운다`() = runTest {
        useTestScope()
        configured(ScriptedGateway())
        assertTrue(DongHaengStore.pastGroups.value.isEmpty())

        DongHaengStore.configure(DhMode.FAKE, ScriptedGateway())
        runCurrent()
        assertEquals(2, DongHaengStore.pastGroups.value.size)
        DongHaengStore.clearPastGroups()
        assertTrue(DongHaengStore.pastGroups.value.isEmpty())
    }

    @Test
    fun `D078 - 참여를 끈 차는 저장되고 다시 로그인해도 꺼져 있다`() = runTest {
        useTestScope()
        configured(ScriptedGateway())
        DongHaengStore.setParticipation("veh_a", false)
        assertEquals(setOf("veh_a"), storage.loadParticipationOff())

        DongHaengStore.reset()
        configured(ScriptedGateway())
        assertFalse(DongHaengStore.isParticipating("veh_a"))
        assertEquals(setOf("veh_a"), DongHaengStore.participationOff())
    }

    // ---------- D084 ----------

    @Test
    fun `D084 - 지금 방장은 스냅샷 값(위임·승계), 없으면 합류 때 값`() = runTest {
        useTestScope()
        val gw = ScriptedGateway()
        gw.observe = { id, _ -> once(snap(id, leader = "veh_b")) }
        configured(gw)
        DongHaengStore.join("http://x/j/inv_1", carB)
        val group = DongHaengStore.activeGroup.value!!
        assertEquals("합류 때", "veh_a", currentLeaderId(group, null))
        runCurrent()
        assertEquals("veh_b", currentLeaderId(group, DongHaengStore.snapshot.value))
    }
}
