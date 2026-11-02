package app.donghaeng.companion.sdk.internal

import app.donghaeng.companion.sdk.DongHaengException
import app.donghaeng.companion.sdk.GroupJoinResult
import app.donghaeng.companion.sdk.GroupMember
import app.donghaeng.companion.sdk.GroupSnapshot
import app.donghaeng.companion.sdk.MemberRole
import app.donghaeng.companion.sdk.MyVehicle
import app.donghaeng.companion.sdk.PairResult
import app.donghaeng.companion.sdk.TokenProvider
import app.donghaeng.protocol.DhJson
import app.donghaeng.protocol.Energy
import app.donghaeng.protocol.EnergyKind
import app.donghaeng.protocol.Eta
import app.donghaeng.protocol.GroupEvent
import app.donghaeng.protocol.GroupEventType
import app.donghaeng.protocol.LowEnergy
import app.donghaeng.protocol.MemberState
import app.donghaeng.protocol.MemberStatus
import app.donghaeng.protocol.Severity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.atan2
import kotlin.math.max

/**
 * 서버 없이 동작하는 메모리 구현 — **단위 테스트 전용**. [app.donghaeng.companion.sdk.DongHaeng.initWithApi] 로만 끼울 수 있고
 * 공개 [app.donghaeng.companion.sdk.DongHaeng.init] 은 어느 제공자든 [HttpDongHaengApi] 를 쓴다(docs/07 §4.2, T13).
 *
 * - 내 차 2대로 시작하고 [approvePairing] 때마다 한 대 늘어난다
 * - [joinGroup] 은 선두차·내 차·후속차 3대짜리 그룹을 만든다(같은 초대 토큰이면 같은 그룹)
 * - [observeGroup] 은 약 2초마다(Fake 전용 주기 — 서버 전송은 15초 폴링 → 푸시) 멤버가 짧은 경로를 따라 움직이는 스냅샷을 내보내고,
 *   10틱마다 후속차 `LOW_ENERGY` 를 넣는다. 이벤트가 추가되면(동승자 메시지 등) 다음 틱을 기다리지 않고 즉시 다시 내보낸다
 * - 토큰은 검사만 한다: [TokenProvider] 가 빈 문자열을 주면 [DongHaengException.Unauthorized]
 */
internal class FakeDongHaengApi(
    private val tokenProvider: TokenProvider,
    private val tickMs: Long = TICK_MS,
) : DongHaengApi {

    private val lock = Any()
    private val vehicles = mutableListOf(
        MyVehicle("veh_dev_01", "내 그랜저", "HYUNDAI", "GRANDEUR", isPrimary = true),
        MyVehicle("veh_dev_02", "가족 EV9", "KIA", "EV9", isPrimary = false),
    )
    private val groups = LinkedHashMap<String, FakeGroup>()
    private var eventSeq = 0

    private class FakeGroup(
        val id: String,
        val name: String,
        val members: List<GroupMember>,
    ) {
        val events = ArrayDeque<GroupEvent>()

        /** 처리한 동승자 메시지 Idempotency-Key */
        val messageKeys = HashSet<String>()

        /** 이벤트가 바뀔 때마다 1 증가 — observeGroup 이 즉시 재발행하는 신호 */
        val version = MutableStateFlow(0)

        fun toJoinResult(): GroupJoinResult = GroupJoinResult(id, name, members)
    }

    // ---------- DongHaengApi ----------

    override suspend fun listMyVehicles(): List<MyVehicle> {
        requireToken()
        return synchronized(lock) { vehicles.toList() }
    }

    override suspend fun joinGroup(inviteToken: String, vehicleId: String?): GroupJoinResult {
        requireToken()
        synchronized(lock) {
            val mine = if (vehicleId != null) {
                vehicles.firstOrNull { it.vehicleId == vehicleId }
                    ?: throw DongHaengException.Api("VEHICLE_NOT_FOUND", "계정에 없는 차량입니다")
            } else {
                vehicles.firstOrNull { it.isPrimary } ?: vehicles.firstOrNull()
                    ?: throw DongHaengException.Api("NO_PAIRED_VEHICLE", "계정에 연결된 차량이 없어요")
            }
            val groupId = "grp_fake_" + String.format("%08x", inviteToken.hashCode())
            groups[groupId]?.let { return it.toJoinResult() }

            val members = listOf(
                GroupMember(LEADER_ID, "아빠 차", "#2E7DFF", MemberRole.LEADER),
                GroupMember(mine.vehicleId, mine.nickname, "#FF7A2E", MemberRole.MEMBER),
                GroupMember(FOLLOWER_ID, "삼촌 차", "#2ECC71", MemberRole.MEMBER),
            )
            val group = FakeGroup(groupId, "추석 귀성길", members)
            group.append(
                newEvent(
                    type = GroupEventType.MEMBER_JOINED,
                    source = mine.vehicleId,
                    severity = Severity.INFO,
                    speech = "${mine.nickname}이(가) 합류했어요.",
                    payload = buildJsonObject {
                        put("vehicleId", mine.vehicleId)
                        put("nickname", mine.nickname)
                    },
                ),
            )
            groups[groupId] = group
            return group.toJoinResult()
        }
    }

    override suspend fun approvePairing(userCode: String, nickname: String): PairResult {
        requireToken()
        if (userCode.isBlank()) throw DongHaengException.Api("CODE_NOT_FOUND", "차 화면의 코드를 확인해 주세요")
        return synchronized(lock) {
            val id = String.format("veh_dev_%02d", vehicles.size + 1)
            val name = nickname.ifBlank { "내 차" }
            vehicles += MyVehicle(id, name, "GENESIS", "GV70", isPrimary = false)
            PairResult(id, name)
        }
    }

    override suspend fun sendPassengerMessage(groupId: String, text: String, fromVehicleId: String?, idempotencyKey: String) {
        requireToken()
        if (text.isBlank()) throw DongHaengException.Api("INVALID_MESSAGE", "메시지를 입력해 주세요")
        synchronized(lock) {
            val group = groups[groupId] ?: throw DongHaengException.Api("GROUP_NOT_FOUND", "참여 중인 그룹이 아니에요")
            // 서버 Idempotency-Key 흉내: 같은 키는 한 번만 처리
            if (!group.messageKeys.add(idempotencyKey)) return
            val mine = fromVehicleId?.let { id ->
                group.members.firstOrNull { it.vehicleId == id }
                    ?: throw DongHaengException.Api("NO_PAIRED_VEHICLE", "이 그룹에 있는 내 차가 아니에요")
            } ?: group.members.firstOrNull { it.role == MemberRole.MEMBER } ?: group.members.first()
            group.append(
                newEvent(
                    type = GroupEventType.PASSENGER_MESSAGE,
                    source = mine.vehicleId,
                    severity = Severity.INFO,
                    speech = "동승자 메시지: $text",
                    payload = buildJsonObject {
                        put("text", text)
                        put("fromVehicleId", mine.vehicleId)
                    },
                ),
            )
        }
    }

    override fun observeGroup(groupId: String): Flow<GroupSnapshot> = flow {
        val group = synchronized(lock) { groups[groupId] }
            ?: throw DongHaengException.Api("GROUP_NOT_FOUND", "참여 중인 그룹이 아니에요")
        var tick = 0
        var seenVersion = group.version.value
        var lastLowEnergyTick = -1
        while (true) {
            // 같은 틱에서 이벤트 때문에 다시 발행될 때 LOW_ENERGY 가 또 붙으면(→ version 증가 → 즉시 재발행) 무한 루프가 되므로
            // 틱당 한 번만 넣고, 우리가 넣은 이벤트는 이미 본 것으로 표시한다
            if (tick > 0 && tick % LOW_ENERGY_EVERY == 0 && tick != lastLowEnergyTick) {
                appendLowEnergy(group, tick)
                lastLowEnergyTick = tick
                seenVersion = group.version.value
            }
            emit(snapshot(group, tick))
            // 다음 틱 또는 이벤트 변경(동승자 메시지 등) 중 먼저 오는 쪽까지 대기
            val changed = withTimeoutOrNull(tickMs) { group.version.first { it != seenVersion } }
            if (changed != null) seenVersion = changed else tick++
        }
    }

    // ---------- 내부 ----------

    private suspend fun requireToken() {
        if (tokenProvider.accessToken().isBlank()) throw DongHaengException.Unauthorized()
    }

    private fun FakeGroup.append(event: GroupEvent) {
        events.addLast(event)
        while (events.size > MAX_EVENTS) events.removeFirst()
        version.value = version.value + 1
    }

    private fun newEvent(
        type: GroupEventType,
        source: String?,
        severity: Severity,
        speech: String?,
        payload: JsonObject,
    ): GroupEvent = GroupEvent(
        eventId = String.format("evt_fake_%04d", ++eventSeq),
        type = type.name,
        sourceVehicleId = source,
        severity = severity,
        speech = speech,
        payload = payload,
    )

    private fun appendLowEnergy(group: FakeGroup, tick: Int) {
        val pct = followerFuelPct(tick)
        synchronized(lock) {
            group.append(
                newEvent(
                    type = GroupEventType.LOW_ENERGY,
                    source = FOLLOWER_ID,
                    severity = Severity.WARN,
                    speech = "삼촌 차 연료가 ${pct}% 남았어요.",
                    payload = DhJson.payloadOf(LowEnergy(EnergyKind.FUEL, pct)),
                ),
            )
        }
    }

    private fun snapshot(group: FakeGroup, tick: Int): GroupSnapshot {
        val now = System.currentTimeMillis()
        val leaderIdx = tick % PATH.size
        val members = group.members.mapIndexed { order, member ->
            // 선두차가 앞서고 뒤차들이 2칸씩 뒤따른다(경로 끝에서 처음으로 돌아감)
            val idx = Math.floorMod(leaderIdx - order * 2, PATH.size)
            val (lat, lon) = PATH[idx]
            val gap = if (member.role == MemberRole.LEADER) 0 else max(0, leaderIdx - idx) * 350
            val remainS = (PATH.size - 1 - idx) * 90 + order * 40
            MemberState(
                vehicleId = member.vehicleId,
                nickname = member.nickname,
                color = member.color,
                online = true,
                lat = lat,
                lon = lon,
                heading = headingAt(idx),
                speedKph = (92 + (tick * 7 + order * 13) % 15).toFloat(),
                ageMs = 400L + (tick * 311 + order * 97) % 900,
                eta = Eta(destTs = now + remainS * 1000L, remainM = remainS * 27, remainS = remainS),
                energy = energyFor(order, tick),
                distanceToLeaderM = gap,
                status = MemberStatus.DRIVING,
            )
        }
        val events = synchronized(lock) { group.events.toList() }
        return GroupSnapshot(group.id, members, events, leaderVehicleId = group.members.firstOrNull { it.role == MemberRole.LEADER }?.vehicleId)
    }

    private fun energyFor(order: Int, tick: Int): Energy = when (order) {
        0 -> Energy(socPct = max(20, 62 - tick / 10))          // 선두차: 전기차
        1 -> Energy(fuelPct = max(20, 48 - tick / 12))         // 내 차: 연료
        else -> Energy(fuelPct = followerFuelPct(tick))        // 후속차: 연료 부족
    }

    private fun followerFuelPct(tick: Int): Int = max(6, 18 - tick / 6)

    private fun headingAt(idx: Int): Float {
        val (lat1, lon1) = PATH[idx]
        val (lat2, lon2) = PATH[(idx + 1) % PATH.size]
        val deg = Math.toDegrees(atan2(lon2 - lon1, lat2 - lat1))
        return (((deg % 360) + 360) % 360).toFloat()
    }

    private companion object {
        const val TICK_MS = 2_000L
        const val LOW_ENERGY_EVERY = 10
        const val MAX_EVENTS = 50
        const val LEADER_ID = "veh_fake_lead"
        const val FOLLOWER_ID = "veh_fake_tail"

        /** 짧은 남행 경로(천안 부근 고속도로를 흉내 낸 가상 좌표). 실제 지도 매칭은 하지 않는다 */
        val PATH: List<Pair<Double, Double>> = listOf(
            36.8650 to 127.1480,
            36.8560 to 127.1470,
            36.8470 to 127.1455,
            36.8380 to 127.1440,
            36.8290 to 127.1430,
            36.8200 to 127.1425,
            36.8110 to 127.1430,
            36.8020 to 127.1440,
            36.7930 to 127.1450,
            36.7840 to 127.1465,
            36.7750 to 127.1480,
            36.7660 to 127.1495,
            36.7570 to 127.1505,
            36.7480 to 127.1510,
            36.7390 to 127.1510,
            36.7300 to 127.1505,
        )
    }
}
