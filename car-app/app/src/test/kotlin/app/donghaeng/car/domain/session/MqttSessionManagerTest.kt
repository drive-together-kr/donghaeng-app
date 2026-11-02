package app.donghaeng.car.domain.session

import app.donghaeng.car.core.common.DhLog
import app.donghaeng.car.core.common.InMemoryTokenStore
import app.donghaeng.car.core.common.StoredCredentials
import app.donghaeng.car.core.common.TokenClearReason
import app.donghaeng.car.core.common.TokenStoreState
import app.donghaeng.car.core.network.mqtt.InMemoryMqttGateway
import app.donghaeng.car.core.network.mqtt.MqttGateway
import app.donghaeng.car.core.network.mqtt.MqttState
import app.donghaeng.car.testing.FakeIdentityApi
import app.donghaeng.car.testing.fakeJwt
import app.donghaeng.car.testing.testRefresher
import app.donghaeng.car.core.common.TokenStore
import app.donghaeng.car.core.network.mqtt.ConnectDecision
import app.donghaeng.car.core.network.mqtt.MqttConnectPolicy
import app.donghaeng.protocol.CmdType
import app.donghaeng.protocol.GroupEnded
import app.donghaeng.protocol.GroupEvent
import app.donghaeng.protocol.GroupJoined
import app.donghaeng.protocol.GroupState
import app.donghaeng.protocol.GroupTopics
import app.donghaeng.protocol.MemberState
import app.donghaeng.protocol.MemberStatus
import app.donghaeng.protocol.MessageTypes
import app.donghaeng.protocol.Place
import app.donghaeng.protocol.Severity
import app.donghaeng.protocol.TokenRevoked
import app.donghaeng.protocol.Topics
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T16: 페어링 ↔ 연결 ↔ cmd 처리 (브로커 없이 InMemory 게이트웨이로). T33: Unavailable·삭제 사유·인증 거부 → 갱신 */
@OptIn(ExperimentalCoroutinesApi::class)
class MqttSessionManagerTest {

    private val creds = StoredCredentials(
        vehicleAccessToken = "jwt-1", refreshToken = "r", vehicleId = "veh_B", nickname = "엄마 차", ownerId = "usr_B",
        mqttUri = "tcp://10.0.2.2:1883", mqttClientId = "veh_B", mqttUsername = "veh_B",
    )
    private val joined = GroupJoined("grp_1", "추석 귀성길", "veh_A", Place(35.1, 126.8, "광주"), GroupTopics("dh/v1/group/grp_1/state", "dh/v1/group/grp_1/event"))

    private fun manager(tokens: InMemoryTokenStore, gateway: InMemoryMqttGateway, sessions: InMemoryGroupSessionStore, api: FakeIdentityApi = FakeIdentityApi()) =
        MqttSessionManager(tokens, gateway, sessions, testRefresher(tokens, api))

    @Test
    fun `paired credentials connect and subscribe own cmd, unpairing disconnects and clears the session`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val tokens = InMemoryTokenStore()
        val gateway = InMemoryMqttGateway()
        val sessions = InMemoryGroupSessionStore()
        val manager = manager(tokens, gateway, sessions)
        manager.start(dispatcher)
        runCurrent()
        assertTrue(gateway.connectedWith.isEmpty())

        tokens.save(creds)
        runCurrent()
        assertEquals(listOf(creds), gateway.connectedWith)
        assertEquals(setOf(Topics.vehicleCmd("veh_B")), gateway.subscriptions)

        tokens.clear(TokenClearReason.USER_SETTINGS_DISCONNECT)
        runCurrent()
        assertEquals(1, gateway.disconnectCount)
        assertTrue("옛 cmd 토픽은 구독 목록에서 빠진다", gateway.subscriptions.isEmpty())
    }

    @Test
    fun `GROUP_JOINED saves the session and subscribes group topics, GROUP_ENDED clears them, group messages are decoded`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val tokens = InMemoryTokenStore(creds)
        val gateway = InMemoryMqttGateway()
        val sessions = InMemoryGroupSessionStore()
        val manager = manager(tokens, gateway, sessions)
        manager.start(dispatcher)
        runCurrent()

        gateway.emitInbound(Topics.vehicleCmd("veh_B"), CmdType.GROUP_JOINED.name, "srv_1", joined, GroupJoined.serializer())
        runCurrent()
        val session = sessions.session.value
        assertEquals("grp_1", session?.groupId)
        assertEquals("veh_A", session?.leaderVehicleId)
        assertEquals(setOf(Topics.vehicleCmd("veh_B"), "dh/v1/group/grp_1/state", "dh/v1/group/grp_1/event"), gateway.subscriptions)
        assertEquals("GROUP_JOINED", manager.lastCommand.value?.type)

        // 그룹 이벤트 수신 → groupEvents
        val event = GroupEvent("evt_1", "MEMBER_JOINED", "veh_C", Severity.INFO, "삼촌 차가 그룹에 들어왔어요.", buildJsonObject { put("vehicleId", "veh_C") })
        gateway.emitInbound("dh/v1/group/grp_1/event", MessageTypes.EVENT, "srv_1", event, GroupEvent.serializer(), seq = 2)
        runCurrent()
        assertEquals(event, manager.groupEvents.first())

        // 남의 cmd 토픽·모르는 타입은 무시
        gateway.emitInbound(Topics.vehicleCmd("veh_X"), CmdType.GROUP_ENDED.name, "srv_1", GroupEnded("grp_1", "LEADER_ENDED"), GroupEnded.serializer(), seq = 3)
        runCurrent()
        assertEquals("grp_1", sessions.session.value?.groupId)

        gateway.emitInbound(Topics.vehicleCmd("veh_B"), CmdType.GROUP_ENDED.name, "srv_1", GroupEnded("grp_1", "LEADER_ENDED"), GroupEnded.serializer(), seq = 4)
        runCurrent()
        assertNull(sessions.session.value)
        assertEquals(setOf(Topics.vehicleCmd("veh_B")), gateway.subscriptions)
        assertEquals("GROUP_ENDED", manager.lastCommand.value?.type)
        assertEquals(listOf(SessionClearReason.GROUP_ENDED), sessions.clearReasons)
    }

    @Test
    fun `my own MEMBER_LEFT clears the session, another car leaving does not (T30)`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val tokens = InMemoryTokenStore(creds)
        val gateway = InMemoryMqttGateway()
        val sessions = InMemoryGroupSessionStore()
        val manager = manager(tokens, gateway, sessions)
        manager.start(dispatcher)
        runCurrent()
        gateway.emitInbound(Topics.vehicleCmd("veh_B"), CmdType.GROUP_JOINED.name, "srv_1", joined, GroupJoined.serializer())
        runCurrent()
        assertEquals("grp_1", sessions.session.value?.groupId)

        val otherLeft = GroupEvent("evt_l1", "MEMBER_LEFT", "veh_C", Severity.INFO, "삼촌 차가 그룹에서 나갔어요.", buildJsonObject { put("reason", "LEFT") })
        gateway.emitInbound("dh/v1/group/grp_1/event", MessageTypes.EVENT, "srv_1", otherLeft, GroupEvent.serializer(), seq = 2)
        runCurrent()
        assertEquals("other car leaving keeps my session", "grp_1", sessions.session.value?.groupId)

        val meLeft = GroupEvent("evt_l2", "MEMBER_LEFT", "veh_B", Severity.INFO, null, buildJsonObject { put("reason", "LEFT") })
        gateway.emitInbound("dh/v1/group/grp_1/event", MessageTypes.EVENT, "srv_1", meLeft, GroupEvent.serializer(), seq = 3)
        runCurrent()
        assertNull(sessions.session.value)
        assertEquals(setOf(Topics.vehicleCmd("veh_B")), gateway.subscriptions)
        assertEquals(listOf(SessionClearReason.MEMBER_LEFT_ME), sessions.clearReasons)
    }

    @Test
    fun `a renamed nickname in the group state is saved once and the gateway keeps the same connection`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val tokens = CountingTokenStore(InMemoryTokenStore(creds))
        val gateway = InMemoryMqttGateway()
        val sessions = InMemoryGroupSessionStore(GroupSession.from(joined, 1L))
        val manager = MqttSessionManager(tokens, gateway, sessions, testRefresher(tokens))
        manager.start(dispatcher)
        runCurrent()
        assertEquals(listOf(creds), gateway.connectedWith)

        fun state(myNick: String) = GroupState(
            "grp_1", "veh_A",
            listOf(
                MemberState("veh_A", "아빠 차", "#2E7DFF", true, null, null, null, null, null, null, null, 0, MemberStatus.DRIVING),
                MemberState("veh_B", myNick, "#FF6B2E", true, null, null, null, null, null, null, null, 100, MemberStatus.DRIVING),
            ),
        )
        // 폰에서 "엄마 차" → "둘째 차" 로 바꿈 → 다음 스냅샷에서 저장값이 따라온다
        gateway.emitInbound("dh/v1/group/grp_1/state", MessageTypes.STATE, "srv_1", state("둘째 차"), GroupState.serializer(), seq = 2)
        runCurrent()
        // A055: 읽고-덮어쓰기(save) 대신 저장소 잠금 안에서 닉네임만 바꾼다
        assertEquals(0, tokens.saves)
        assertEquals(1, tokens.nicknameUpdates)
        assertEquals("둘째 차", (tokens.state.value as TokenStoreState.Present).credentials.nickname)
        assertEquals("토큰·차는 그대로", creds.copy(nickname = "둘째 차"), tokens.load())

        // 저장값이 바뀌면 MqttSessionManager 가 새 자격 증명으로 connect 를 다시 부른다 — 닉네임만 다르므로
        // 실제 게이트웨이(HiveMqttGateway)의 판단은 KEEP(연결 유지)이어야 한다. 판단 함수 자체는 MqttConnectPolicyTest.
        assertEquals(2, gateway.connectedWith.size)
        val (previous, latest) = gateway.connectedWith
        assertEquals("둘째 차", latest.nickname)
        assertEquals(ConnectDecision.KEEP, MqttConnectPolicy.decide(connected = true, current = previous, next = latest))

        // 같은 닉네임 스냅샷은 저장하지 않는다(2초마다 디스크 쓰기·connect 재호출 없음)
        gateway.emitInbound("dh/v1/group/grp_1/state", MessageTypes.STATE, "srv_1", state("둘째 차"), GroupState.serializer(), seq = 3)
        runCurrent()
        assertEquals(1, tokens.nicknameUpdates)
        assertEquals(2, gateway.connectedWith.size)
    }

    /** save·닉네임 변경 횟수를 센다 — 같은 값을 다시 저장해도 StateFlow 는 바뀌지 않아 상태만 봐서는 구별이 안 된다 */
    private class CountingTokenStore(private val inner: InMemoryTokenStore) : TokenStore by inner {
        var saves = 0
            private set
        var nicknameUpdates = 0
            private set

        override suspend fun save(credentials: StoredCredentials) {
            saves++
            inner.save(credentials)
        }

        override suspend fun updateNickname(vehicleId: String, nickname: String): StoredCredentials? =
            inner.updateNickname(vehicleId, nickname)?.also { nicknameUpdates++ }
    }

    @Test
    fun `A055 - a nickname update keeps tokens rotated meanwhile and never revives cleared credentials`() = runTest {
        val store = InMemoryTokenStore(creds)
        store.load()
        // 닉네임 동기화가 읽은 뒤(옛 토큰) 갱신기가 토큰을 회전했다 → 닉네임만 바뀌고 새 토큰은 그대로
        store.updateTokens(expectedRefreshToken = "r", accessToken = "jwt-2", refreshToken = "r2")
        val updated = store.updateNickname("veh_B", "둘째 차")
        assertEquals("jwt-2", updated?.vehicleAccessToken)
        assertEquals("r2", updated?.refreshToken)
        assertEquals("둘째 차", updated?.nickname)
        // 다른 차·같은 닉네임은 바꾸지 않는다
        assertNull(store.updateNickname("veh_X", "남의 차"))
        assertNull(store.updateNickname("veh_B", "둘째 차"))
        // 지운 뒤에는 되살리지 않는다
        store.clear(TokenClearReason.REFRESH_REJECTED)
        assertNull(store.updateNickname("veh_B", "셋째 차"))
        assertEquals(TokenStoreState.Empty, store.state.value)
    }

    // ---------------------------------------------------------------- A000/A001 reconcile · A004 방장 · A010 replay

    /** 서버 소속 응답을 테스트가 정한다 */
    private class FakeMembership(var result: ServerMembership = ServerMembership.None, var check: GroupCheck = GroupCheck.UNKNOWN) : GroupMembershipSource {
        var calls = 0
        var checks = 0
        /** non-null 이면 응답 전에 이 동작을 한다(요청 중 세션이 바뀌는 경합 흉내) */
        var beforeAnswer: (suspend () -> Unit)? = null
        override suspend fun myActiveGroup(): ServerMembership {
            calls++
            beforeAnswer?.invoke()
            return result
        }
        override suspend fun checkGroup(groupId: String, myVehicleId: String): GroupCheck { checks++; return check }
    }

    private fun managerWith(tokens: InMemoryTokenStore, gateway: InMemoryMqttGateway, sessions: InMemoryGroupSessionStore, membership: GroupMembershipSource) =
        MqttSessionManager(tokens, gateway, sessions, testRefresher(tokens), membership)

    @Test
    fun `A001 - a group joined while the car was off (cmd lost) is adopted on connect`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val tokens = InMemoryTokenStore(creds)
        val gateway = InMemoryMqttGateway()
        val sessions = InMemoryGroupSessionStore()
        val membership = FakeMembership(ServerMembership.Active(joined))
        val manager = managerWith(tokens, gateway, sessions, membership)
        manager.start(dispatcher)
        runCurrent()
        assertEquals("접속하면 서버에 소속을 묻는다", 1, membership.calls)
        assertEquals("grp_1", sessions.session.value?.groupId)
        assertTrue(gateway.subscriptions.containsAll(joined.topics.let { listOf(it.state, it.event) }))
        assertTrue("멤버로 합류", sessions.session.value?.isLeader == false)
        assertEquals("joined", manager.reconcileState.value.lastResult)
    }

    @Test
    fun `A000 - a ghost session of a group that ended while offline is cleared on reconnect, a network failure keeps it`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val tokens = InMemoryTokenStore(creds)
        val gateway = InMemoryMqttGateway()
        val sessions = InMemoryGroupSessionStore(GroupSession.from(joined, 1L))
        val membership = FakeMembership(ServerMembership.Unknown("network IOException"))
        val manager = managerWith(tokens, gateway, sessions, membership)
        manager.start(dispatcher)
        runCurrent()
        assertEquals("네트워크 실패면 지우지 않는다", "grp_1", sessions.session.value?.groupId)

        membership.result = ServerMembership.None
        gateway.simulateReconnect(atMs = 2L)
        runCurrent()
        assertNull(sessions.session.value)
        assertEquals(listOf(SessionClearReason.RECONCILED), sessions.clearReasons)
        assertEquals(setOf(Topics.vehicleCmd("veh_B")), gateway.subscriptions)
    }

    @Test
    fun `A000 - an old server without the contract path falls back to the group detail check`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val tokens = InMemoryTokenStore(creds)
        val gateway = InMemoryMqttGateway()
        val sessions = InMemoryGroupSessionStore(GroupSession.from(joined, 1L))
        val membership = FakeMembership(ServerMembership.Unsupported, GroupCheck.ACTIVE_MEMBER)
        val manager = managerWith(tokens, gateway, sessions, membership)
        manager.start(dispatcher)
        runCurrent()
        assertEquals(1, membership.checks)
        assertEquals("멤버면 유지", "grp_1", sessions.session.value?.groupId)
        membership.check = GroupCheck.GONE
        gateway.simulateReconnect(atMs = 3L)
        runCurrent()
        assertNull(sessions.session.value)
        assertEquals(listOf(SessionClearReason.RECONCILED), sessions.clearReasons)
    }

    @Test
    fun `A000 - a reconcile answer is dropped when the session changed while the request was in flight`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val tokens = InMemoryTokenStore(creds)
        val gateway = InMemoryMqttGateway()
        val sessions = InMemoryGroupSessionStore()
        // 서버는 '그룹 없음'이라 답하는데, 응답이 오기 전에 방장으로 새 그룹을 만들었다
        val membership = FakeMembership(ServerMembership.None)
        val manager = managerWith(tokens, gateway, sessions, membership)
        membership.beforeAnswer = { manager.onGroupCreated(GroupSession.from(joined, 5L, "veh_B").copy(groupId = "grp_new")) }
        manager.start(dispatcher)
        runCurrent()
        assertEquals("새로 만든 그룹은 지우지 않는다", "grp_new", sessions.session.value?.groupId)
        assertEquals("skipped(changed)", manager.reconcileState.value.lastResult)
    }

    @Test
    fun `A001 - a re-sent GROUP_JOINED for the same group keeps the leader's session (invite and join time)`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val tokens = InMemoryTokenStore(creds)
        val gateway = InMemoryMqttGateway()
        val invite = GroupInvite("inv", "http://x/j/inv", 9_999L)
        val leaderSession = GroupSession.from(joined.copy(leaderVehicleId = "veh_B"), 1L, "veh_B").copy(invite = invite)
        val sessions = InMemoryGroupSessionStore(leaderSession)
        val manager = manager(tokens, gateway, sessions)
        manager.start(dispatcher)
        runCurrent()
        gateway.emitInbound(Topics.vehicleCmd("veh_B"), CmdType.GROUP_JOINED.name, "srv_1", joined.copy(leaderVehicleId = "veh_B"), GroupJoined.serializer())
        runCurrent()
        assertEquals("같은 그룹 재수신 → 그대로", leaderSession, sessions.session.value)
    }

    @Test
    fun `A004 - LEADER_CHANGED and the snapshot leader update isLeader, demotion drops the invite`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val tokens = InMemoryTokenStore(creds)
        val gateway = InMemoryMqttGateway()
        val sessions = InMemoryGroupSessionStore(GroupSession.from(joined, 1L))   // 방장 veh_A, 나는 veh_B
        val manager = manager(tokens, gateway, sessions)
        manager.start(dispatcher)
        runCurrent()
        assertTrue(sessions.session.value?.isLeader == false)
        // 방장 A 가 빠져 B(나)가 승계
        val changed = GroupEvent("evt_lc", "LEADER_CHANGED", null, Severity.INFO, "이제 엄마 차가 선두예요.", buildJsonObject { put("leaderVehicleId", "veh_B"); put("previousLeaderVehicleId", "veh_A") })
        gateway.emitInbound("dh/v1/group/grp_1/event", MessageTypes.EVENT, "srv_1", changed, GroupEvent.serializer(), seq = 2)
        runCurrent()
        assertEquals(true, sessions.session.value?.isLeader)
        assertEquals("veh_B", sessions.session.value?.leaderVehicleId)

        // 이벤트를 놓쳐도 스냅샷이 확정한다 — 다른 차(C)에게 위임되면 강등, 초대 삭제
        sessions.save(sessions.session.value!!.copy(invite = GroupInvite("inv", "u", 1L)))
        val st = GroupState("grp_1", "veh_C", listOf(MemberState("veh_B", "엄마 차", "#FF6B2E", true, null, null, null, null, null, null, null, 0, MemberStatus.DRIVING)))
        gateway.emitInbound("dh/v1/group/grp_1/state", MessageTypes.STATE, "srv_1", st, GroupState.serializer(), seq = 3)
        runCurrent()
        assertEquals(false, sessions.session.value?.isLeader)
        assertEquals("veh_C", sessions.session.value?.leaderVehicleId)
        assertNull(sessions.session.value?.invite)
    }

    @Test
    fun `A000 - a GROUP_ENDED group event clears the session even if the cmd is lost`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val tokens = InMemoryTokenStore(creds)
        val gateway = InMemoryMqttGateway()
        val sessions = InMemoryGroupSessionStore(GroupSession.from(joined, 1L))
        val manager = manager(tokens, gateway, sessions)
        manager.start(dispatcher)
        runCurrent()
        val ended = GroupEvent("evt_end", "GROUP_ENDED", null, Severity.INFO, "그룹이 끝났어요.", buildJsonObject { put("reason", "LEADER_ENDED") })
        gateway.emitInbound("dh/v1/group/grp_1/event", MessageTypes.EVENT, "srv_1", ended, GroupEvent.serializer(), seq = 2)
        runCurrent()
        assertNull(sessions.session.value)
        assertEquals(listOf(SessionClearReason.GROUP_ENDED), sessions.clearReasons)
    }

    @Test
    fun `A010 - after a group ends a new subscriber gets no replayed events from the old group`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val tokens = InMemoryTokenStore(creds)
        val gateway = InMemoryMqttGateway()
        val sessions = InMemoryGroupSessionStore(GroupSession.from(joined, 1L))
        val manager = manager(tokens, gateway, sessions)
        manager.start(dispatcher)
        runCurrent()
        val brake = GroupEvent("evt_b", "HARD_BRAKE", "veh_A", Severity.ALERT, "앞쪽 아빠 차가 급제동했어요.", buildJsonObject { })
        gateway.emitInbound("dh/v1/group/grp_1/event", MessageTypes.EVENT, "srv_1", brake, GroupEvent.serializer(), seq = 2)
        runCurrent()
        assertEquals(listOf(brake), manager.groupEvents.replayCache)
        gateway.emitInbound(Topics.vehicleCmd("veh_B"), CmdType.GROUP_ENDED.name, "srv_1", GroupEnded("grp_1", "LEADER_ENDED"), GroupEnded.serializer(), seq = 3)
        runCurrent()
        assertTrue("이전 그룹 소식은 새 그룹 화면에 섞이지 않는다", manager.groupEvents.replayCache.isEmpty())
    }

    /** 구독 해제·끊기 순서를 기록한다 */
    private class OrderGateway(private val inner: InMemoryMqttGateway) : MqttGateway by inner {
        val calls = mutableListOf<String>()
        override suspend fun unsubscribe(topics: Collection<String>) { calls += "unsub:" + topics.joinToString(","); inner.unsubscribe(topics) }
        override suspend fun disconnect() { calls += "disconnect"; inner.disconnect() }
    }

    @Test
    fun `A070 - unpairing unsubscribes the group topics before disconnecting (not left in the broker session)`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val tokens = InMemoryTokenStore(creds)
        val gateway = OrderGateway(InMemoryMqttGateway())
        val sessions = InMemoryGroupSessionStore(GroupSession.from(joined, 1L))
        val manager = MqttSessionManager(tokens, gateway, sessions, testRefresher(tokens))
        manager.start(dispatcher)
        runCurrent()
        tokens.clear(TokenClearReason.USER_SETTINGS_DISCONNECT)
        runCurrent()
        val groupUnsub = gateway.calls.indexOfFirst { it.startsWith("unsub:") && it.contains("grp_1") }
        val disconnect = gateway.calls.indexOf("disconnect")
        assertTrue("${gateway.calls}", groupUnsub in 0 until disconnect)
        assertNull(sessions.session.value)
    }

    @Test
    fun `A018 - repeated subscription denials of a group topic trigger a reconcile`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val tokens = InMemoryTokenStore(creds)
        val gateway = InMemoryMqttGateway()
        val sessions = InMemoryGroupSessionStore(GroupSession.from(joined, 1L))
        val membership = FakeMembership(ServerMembership.Active(joined))
        val manager = managerWith(tokens, gateway, sessions, membership)
        manager.start(dispatcher)
        runCurrent()
        val before = membership.calls
        gateway.emitSubscriptionDenied("dh/v1/group/grp_1/state")
        runCurrent()
        assertEquals(before + 1, membership.calls)
        gateway.emitSubscriptionDenied("dh/v1/vehicle/veh_B/other")
        runCurrent()
        assertEquals("그룹 토픽이 아니면 무시", before + 1, membership.calls)
    }

    @Test
    fun `nickname sync ignores other cars, a blank or id-like server value and other vehicles' credentials`() {
        val present = TokenStoreState.Present(creds)
        fun st(vararg m: Pair<String, String>) = GroupState("grp_1", "veh_A", m.map { (vid, nick) -> MemberState(vid, nick, "#2E7DFF", true, null, null, null, null, null, null, null, null, MemberStatus.DRIVING) })
        assertEquals("둘째 차", MqttSessionManager.nicknameFromState(st("veh_B" to " 둘째 차 "), "veh_B", present)?.nickname)
        assertNull("다른 차만 바뀜", MqttSessionManager.nicknameFromState(st("veh_A" to "삼촌 차", "veh_B" to "엄마 차"), "veh_B", present))
        assertNull("스냅샷에 내 차 없음", MqttSessionManager.nicknameFromState(st("veh_A" to "삼촌 차"), "veh_B", present))
        assertNull("빈 값", MqttSessionManager.nicknameFromState(st("veh_B" to "  "), "veh_B", present))
        assertNull("서버가 별명 대신 id 를 줌", MqttSessionManager.nicknameFromState(st("veh_B" to "veh_B"), "veh_B", present))
        assertNull("저장된 차가 아님", MqttSessionManager.nicknameFromState(st("veh_C" to "둘째 차"), "veh_C", present))
        assertNull("페어링 없음", MqttSessionManager.nicknameFromState(st("veh_B" to "둘째 차"), "veh_B", TokenStoreState.Empty))
    }

    @Test
    fun `restart with a stored session resubscribes group topics, TOKEN_REVOKED clears credentials with its reason`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val tokens = InMemoryTokenStore(creds)
        val gateway = InMemoryMqttGateway()
        val sessions = InMemoryGroupSessionStore(GroupSession.from(joined, 1L))
        val manager = manager(tokens, gateway, sessions)
        manager.start(dispatcher)
        runCurrent()
        assertEquals(setOf(Topics.vehicleCmd("veh_B"), "dh/v1/group/grp_1/state", "dh/v1/group/grp_1/event"), gateway.subscriptions)

        gateway.emitInbound(Topics.vehicleCmd("veh_B"), CmdType.TOKEN_REVOKED.name, "srv_1", TokenRevoked, TokenRevoked.serializer(), seq = 9)
        runCurrent()
        assertNull(tokens.load())
        assertEquals(1, gateway.disconnectCount)
        assertNull(sessions.session.value)
        assertEquals(listOf(TokenClearReason.TOKEN_REVOKED_CMD), tokens.clearReasons)
        assertEquals("TOKEN_REVOKED_CMD", tokens.lastClear.value?.reason)
        assertEquals("Empty 는 UNPAIRED 사유로 그룹 세션까지 지운다", listOf(SessionClearReason.UNPAIRED), sessions.clearReasons)
    }

    @Test
    fun `T33 - token store Unavailable keeps the group session, does not connect, and retries every 30 s`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val tokens = InMemoryTokenStore(creds).apply { unavailableReason = "SecurityException<AEADBadTagException" }
        val gateway = InMemoryMqttGateway()
        val sessions = InMemoryGroupSessionStore(GroupSession.from(joined, 1L))
        val manager = manager(tokens, gateway, sessions)
        manager.start(dispatcher)
        runCurrent()
        assertTrue(tokens.state.value is TokenStoreState.Unavailable)
        assertEquals("세션 유지", "grp_1", sessions.session.value?.groupId)
        assertTrue("연결하지 않는다", gateway.connectedWith.isEmpty())
        assertTrue(sessions.clearReasons.isEmpty())
        val loadsAtStart = tokens.loadCalls

        advanceTimeBy(MqttSessionManager.STORE_RETRY_INTERVAL_MS + 1)
        runCurrent()
        assertEquals(loadsAtStart + 1, tokens.loadCalls)
        assertEquals(1, manager.storeRetry.value.attempts)

        // 저장소가 살아나면 다음 재시도에서 Present → 세션 그대로 접속
        tokens.unavailableReason = null
        advanceTimeBy(MqttSessionManager.STORE_RETRY_INTERVAL_MS + 1)
        runCurrent()
        assertTrue(tokens.state.value is TokenStoreState.Present)
        assertEquals(listOf(creds), gateway.connectedWith)
        assertEquals(setOf(Topics.vehicleCmd("veh_B"), "dh/v1/group/grp_1/state", "dh/v1/group/grp_1/event"), gateway.subscriptions)
        assertEquals("grp_1", sessions.session.value?.groupId)
        assertEquals(0, manager.storeRetry.value.attempts)
    }

    @Test
    fun `T33 - Unavailable retries stop after 10 attempts, home retry starts over`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val tokens = InMemoryTokenStore(creds).apply { unavailableReason = "KeyStoreException" }
        val gateway = InMemoryMqttGateway()
        val sessions = InMemoryGroupSessionStore(GroupSession.from(joined, 1L))
        val manager = manager(tokens, gateway, sessions)
        manager.start(dispatcher)
        runCurrent()
        val loadsAtStart = tokens.loadCalls
        advanceTimeBy(MqttSessionManager.STORE_RETRY_INTERVAL_MS * (MqttSessionManager.STORE_RETRY_MAX + 3))
        runCurrent()
        assertEquals(loadsAtStart + MqttSessionManager.STORE_RETRY_MAX, tokens.loadCalls)
        assertTrue(manager.storeRetry.value.exhausted)
        assertEquals("grp_1", sessions.session.value?.groupId)

        tokens.unavailableReason = null
        manager.retryTokenStore()
        runCurrent()
        assertTrue(tokens.state.value is TokenStoreState.Present)
        assertEquals(listOf(creds), gateway.connectedWith)
        assertTrue(!manager.storeRetry.value.exhausted)
    }

    @Test
    fun `T33 - MQTT auth rejection triggers a token refresh and reconnects with the rotated token`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val tokens = InMemoryTokenStore(creds)
        val gateway = InMemoryMqttGateway()
        val sessions = InMemoryGroupSessionStore()
        val api = FakeIdentityApi().apply { refreshHandler = { FakeIdentityApi.ok("jwt-2", "r2") } }
        val manager = manager(tokens, gateway, sessions, api)
        manager.start(dispatcher)
        runCurrent()
        assertEquals(listOf(creds), gateway.connectedWith)

        gateway.emitAuthFailure(accessToken = "jwt-1", reasonCode = "BAD_USER_NAME_OR_PASSWORD")
        runCurrent()
        assertEquals("refresh 는 저장된 refresh 토큰으로 한 번", listOf("r"), api.refreshCalls.map { it.refreshToken })
        val rotated = creds.copy(vehicleAccessToken = "jwt-2", refreshToken = "r2")
        assertEquals(TokenStoreState.Present(rotated), tokens.state.value)
        assertEquals("새 토큰으로 다시 connect", rotated, gateway.connectedWith.last())
        assertEquals(1, gateway.status.value.authFailures)
    }

    @Test
    fun `T33 - refresh rejected (401) after MQTT auth failure clears credentials as REFRESH_REJECTED`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val tokens = InMemoryTokenStore(creds)
        val gateway = InMemoryMqttGateway()
        val sessions = InMemoryGroupSessionStore(GroupSession.from(joined, 1L))
        val api = FakeIdentityApi().apply { refreshHandler = { FakeIdentityApi.unauthorized() } }
        val manager = manager(tokens, gateway, sessions, api)
        manager.start(dispatcher)
        runCurrent()

        gateway.emitAuthFailure(accessToken = "jwt-1")
        runCurrent()
        assertEquals(TokenStoreState.Empty, tokens.state.value)
        assertEquals(listOf(TokenClearReason.REFRESH_REJECTED), tokens.clearReasons)
        assertEquals(listOf(SessionClearReason.UNPAIRED), sessions.clearReasons)
        assertEquals(1, gateway.disconnectCount)
    }

    @Test
    fun `T33 - TOKEN_REVOKED published before the current token was issued is ignored (stale persistent-session message)`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val iat = 1_700_000_100L
        val fresh = creds.copy(vehicleAccessToken = fakeJwt(expSec = iat + 86_400, iatSec = iat))
        val tokens = InMemoryTokenStore(fresh)
        val gateway = InMemoryMqttGateway()
        val sessions = InMemoryGroupSessionStore()
        val manager = manager(tokens, gateway, sessions)
        manager.start(dispatcher)
        runCurrent()

        // 봉투 ts(서버 시계) 가 토큰 iat 와 같은 초 이전 → 재페어링 전 것 → 무시
        gateway.emitInbound(Topics.vehicleCmd("veh_B"), CmdType.TOKEN_REVOKED.name, "srv_1", TokenRevoked, TokenRevoked.serializer(), seq = 1, ts = (iat - 5) * 1000)
        gateway.emitInbound(Topics.vehicleCmd("veh_B"), CmdType.TOKEN_REVOKED.name, "srv_1", TokenRevoked, TokenRevoked.serializer(), seq = 2, ts = iat * 1000 + 400)
        runCurrent()
        assertTrue(tokens.state.value is TokenStoreState.Present)
        assertTrue(tokens.clearReasons.isEmpty())

        // 발급 뒤에 발행된 폐기 → 처리
        gateway.emitInbound(Topics.vehicleCmd("veh_B"), CmdType.TOKEN_REVOKED.name, "srv_1", TokenRevoked, TokenRevoked.serializer(), seq = 3, ts = (iat + 60) * 1000)
        runCurrent()
        assertEquals(TokenStoreState.Empty, tokens.state.value)
        assertEquals(listOf(TokenClearReason.TOKEN_REVOKED_CMD), tokens.clearReasons)
        assertNotNull(tokens.lastClear.value)
    }

    @Test
    fun `T33 리뷰 - 토큰 상태 처리가 실패해도 로그에는 상태 종류만, 자격 증명 원문은 남지 않는다`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val secret = creds.copy(vehicleAccessToken = "eyJSECRETACCESS.part.sig", refreshToken = "SECRETREFRESHVALUE", nickname = "비밀차", ownerId = "usr_SECRETOWNERX")
        val tokens = InMemoryTokenStore(secret)
        val inner = InMemoryMqttGateway()
        // 접속 단계에서 예외(HiveMQ 빌더·topicFilter 오류 등) → MqttSessionManager.guarded 가 E 로그를 남긴다
        val failing = object : MqttGateway by inner {
            override suspend fun connect(credentials: StoredCredentials) = throw IllegalStateException("builder failed")
        }
        val manager = MqttSessionManager(tokens, failing, InMemoryGroupSessionStore(), testRefresher(tokens))
        manager.start(dispatcher)
        runCurrent()

        val lines = DhLog.recent(DhLog.RING_CAPACITY)
        val failure = lines.lastOrNull { it.contains("token state") && it.contains("failed") }
        assertNotNull("실패 줄이 남는다", failure)
        assertTrue(failure, failure!!.contains("token state Present failed"))
        listOf("SECRETACCESS", "SECRETREFRESHVALUE", "비밀차", "SECRETOWNERX").forEach { raw ->
            assertTrue("원문 '$raw' 이 로그에 남았다", lines.none { it.contains(raw) })
        }
    }

    @Test
    fun `T33 리뷰 - 인증 거부 뒤 새 토큰으로 다시 붙으면 MQTT 발 회전 백오프가 처음부터`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val tokens = InMemoryTokenStore(creds)
        val gateway = InMemoryMqttGateway()
        val api = FakeIdentityApi().apply { refreshHandler = { FakeIdentityApi.ok("jwt-2", "r2") } }
        val refresher = testRefresher(tokens, api)
        val manager = MqttSessionManager(tokens, gateway, InMemoryGroupSessionStore(), refresher)
        manager.start(dispatcher)
        runCurrent()

        gateway.emitAuthFailure(accessToken = "jwt-1")   // RECONNECTING
        runCurrent()
        assertEquals(1, api.refreshCalls.size)
        // 갱신 → 저장소 Present(새 토큰) → connect → CONNECTED → TokenRefresher.onMqttConnected()
        assertEquals(MqttState.CONNECTED, gateway.status.value.state)
        assertEquals(0, refresher.state.value.mqttRotationStreak)
        assertNull(refresher.state.value.mqttNextRotationAtMs)
        assertEquals("OK MQTT_AUTH", refresher.state.value.lastResult)
    }
}
