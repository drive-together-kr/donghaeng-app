package app.donghaeng.companion.demo.donghaeng

import android.util.Log
import app.donghaeng.companion.demo.DemoApplication
import app.donghaeng.companion.sdk.DongHaeng
import app.donghaeng.companion.sdk.DongHaengException
import app.donghaeng.companion.sdk.GroupJoinResult
import app.donghaeng.companion.sdk.GroupMember
import app.donghaeng.companion.sdk.GroupSnapshot
import app.donghaeng.companion.sdk.MemberRole
import app.donghaeng.companion.sdk.MyVehicle
import app.donghaeng.companion.sdk.PairResult
import app.donghaeng.protocol.DhJson
import app.donghaeng.protocol.Energy
import app.donghaeng.protocol.Eta
import app.donghaeng.protocol.GroupEvent
import app.donghaeng.protocol.GroupEventType
import app.donghaeng.protocol.MemberState
import app.donghaeng.protocol.MemberStatus
import app.donghaeng.protocol.Severity
import app.donghaeng.protocol.VoiceMessagePayload
import app.donghaeng.protocol.rest.InvitePreviewResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * ────────────────────────────────────────────────────────────────────────────
 *  공식 앱에 붙이는 부분(동행 삽입 지점 A~G)이 서버·SDK 와 만나는 유일한 창구.
 *  실제 공식 앱은 [SdkGateway] 만 쓰면 된다. [FakeGateway] 는 서버 없이 화면을 시연하기 위한 호스트 쪽 가짜다
 *  (companion/sdk 의 Fake 는 단위 테스트 전용 internal 이라 호스트에서 쓸 수 없고, 공개 API 6개는 그대로 둔다).
 * ────────────────────────────────────────────────────────────────────────────
 */

/** 그룹 초대 QR 을 찍었을 때 합류 전에 보여 줄 정보 (`GET {base}/j/{token}` + `Accept: application/json`, docs/03 §2.2) */
data class InvitePreview(
    val name: String,
    val leaderNickname: String,
    val destinationName: String,
    val memberCount: Int,
    val maxMembers: Int,
)

interface DongHaengGateway {
    /** 계정에 연결된 내 차 (`GET /me/vehicles`) */
    suspend fun listMyVehicles(): List<MyVehicle>

    /** 초대 미리보기. 동행 QR 이 아니거나 만료됐으면 null */
    suspend fun previewInvite(qrPayload: String): InvitePreview?

    /** 합류 (`POST /groups/join`). [vehicleId] 생략 시 대표 차량 */
    suspend fun joinGroup(qrPayload: String, vehicleId: String?): GroupJoinResult

    /** 동승자 메시지 (`POST /groups/{id}/messages`) */
    suspend fun sendPassengerMessage(groupId: String, text: String)

    /** 차량 등록 QR 승인 (`POST /device/approve`, 대체 수단) */
    suspend fun pairVehicle(qrPayload: String, nickname: String): PairResult

    /** 그룹 스냅샷 스트림 */
    fun observeGroup(groupId: String): Flow<GroupSnapshot>
}

/** 실제 구현 — companion/sdk 공개 API 6개 + 초대 미리보기(QR URL 자체를 JSON 으로 조회) */
class SdkGateway(
    private val publicBaseUrl: String,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build(),
) : DongHaengGateway {

    override suspend fun listMyVehicles(): List<MyVehicle> = DongHaeng.listMyVehicles()

    /**
     * QR 내용이 곧 서버 URL(`{base}/j/{token}`)이므로 그 URL 을 `Accept: application/json` 으로 읽는다(인증 없음 — 토큰 자체가 비밀).
     * 서버 주소가 설정과 다르거나 경로가 그룹 QR 이 아니면 null (joinGroup 이 정확한 오류를 낸다).
     */
    override suspend fun previewInvite(qrPayload: String): InvitePreview? = withContext(Dispatchers.IO) {
        val url = qrPayload.trim()
        if (!url.startsWith(publicBaseUrl.trimEnd('/') + "/j/")) return@withContext null
        val request = Request.Builder().url(url).header("Accept", "application/json").get().build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(DemoApplication.TAG, "invite preview status=${response.code}")
                    return@withContext null
                }
                val body = response.body?.string().orEmpty()
                val dto = DhJson.json.decodeFromString(InvitePreviewResponse.serializer(), body)
                InvitePreview(dto.name, dto.leaderNickname, dto.destination.name, dto.memberCount, dto.maxMembers)
            }
        } catch (e: Exception) {
            Log.w(DemoApplication.TAG, "invite preview failed cause=${e.javaClass.simpleName}")
            null
        }
    }

    override suspend fun joinGroup(qrPayload: String, vehicleId: String?): GroupJoinResult = DongHaeng.joinGroup(qrPayload, vehicleId)

    override suspend fun sendPassengerMessage(groupId: String, text: String) = DongHaeng.sendPassengerMessage(groupId, text)

    override suspend fun pairVehicle(qrPayload: String, nickname: String): PairResult = DongHaeng.pairVehicle(qrPayload, nickname)

    override fun observeGroup(groupId: String): Flow<GroupSnapshot> = DongHaeng.observeGroup(groupId)
}

/**
 * 호스트 쪽 가짜 — 서버·SDK 없이 홈 카드(B)·지도 마커(D)·알림(F)·메시지(G)가 움직이게 한다.
 * 서울시청 부근에서 출발해 동쪽으로 움직이는 3대(선두 아빠 차, 내 차, 삼촌 차)와 시간표대로 나오는 이벤트를 만든다.
 */
class FakeGateway(private val tickMs: Long = 2_000L) : DongHaengGateway {

    private val vehicles = listOf(
        MyVehicle("veh_demo_sorento", "쏘렌토 HEV(시연)", "KIA", "SORENTO", isPrimary = true),
        MyVehicle("veh_demo_ev9", "가족 EV9(시연)", "KIA", "EV9", isPrimary = false),
    )

    private class Group(val id: String, val name: String, val members: List<GroupMember>, val startedAt: Long) {
        val events = ArrayList<GroupEvent>()
        var eventSeq = 0
        val sentMessages = ArrayList<Pair<Long, String>>()
    }

    private val groups = LinkedHashMap<String, Group>()

    override suspend fun listMyVehicles(): List<MyVehicle> {
        delay(150)
        return vehicles
    }

    override suspend fun previewInvite(qrPayload: String): InvitePreview? {
        delay(300)
        return InvitePreview(name = GROUP_NAME, leaderNickname = "아빠 차", destinationName = DESTINATION, memberCount = 2, maxMembers = 10)
    }

    override suspend fun joinGroup(qrPayload: String, vehicleId: String?): GroupJoinResult {
        delay(500)
        val mine = vehicles.firstOrNull { it.vehicleId == vehicleId } ?: vehicles.first { it.isPrimary }
        val id = "grp_demo_" + Integer.toHexString(qrPayload.hashCode())
        val existing = groups[id]
        if (existing != null) return GroupJoinResult(existing.id, existing.name, existing.members)
        val members = listOf(
            GroupMember(LEADER, "아빠 차", "#2E7DFF", MemberRole.LEADER),
            GroupMember(mine.vehicleId, mine.nickname, "#FF6B2E", MemberRole.MEMBER),
            GroupMember(FOLLOWER, "삼촌 차", "#2EC27E", MemberRole.MEMBER),
        )
        val group = Group(id, GROUP_NAME, members, System.currentTimeMillis())
        group.events += group.event(
            GroupEventType.MEMBER_JOINED, mine.vehicleId, Severity.INFO,
            "${mine.nickname}가 그룹에 들어왔어요.",
            buildJsonObject { put("vehicleId", mine.vehicleId); put("nickname", mine.nickname) },
        )
        groups[id] = group
        return GroupJoinResult(id, group.name, members)
    }

    override suspend fun sendPassengerMessage(groupId: String, text: String) {
        delay(300)
        val group = groups[groupId] ?: throw DongHaengException.Api("NOT_FOUND", "그룹을 찾을 수 없어요")
        val me = group.members.first { it.vehicleId != LEADER && it.vehicleId != FOLLOWER }
        group.sentMessages += System.currentTimeMillis() to text
        group.events += group.event(
            GroupEventType.PASSENGER_MESSAGE, me.vehicleId, Severity.INFO,
            "${me.nickname}에서 온 메시지. $text",
            buildJsonObject { put("text", text) },
        )
    }

    override suspend fun pairVehicle(qrPayload: String, nickname: String): PairResult {
        delay(400)
        return PairResult("veh_demo_${vehicles.size + 1}", nickname.ifBlank { "내 차" })
    }

    override fun observeGroup(groupId: String): Flow<GroupSnapshot> = flow {
        val group = groups[groupId] ?: throw DongHaengException.Api("NOT_FOUND", "그룹을 찾을 수 없어요")
        var tick = 0
        while (true) {
            val elapsedS = ((System.currentTimeMillis() - group.startedAt) / 1000).toInt()
            scheduleEvents(group, elapsedS)
            scheduleVoice(group, elapsedS)
            emit(GroupSnapshot(group.id, group.members.mapIndexed { i, m -> memberState(m, i, tick, elapsedS) }, group.events.toList()))
            tick++
            delay(tickMs)
        }
    }

    // ---------- 가짜 데이터 생성 ----------

    /** 이벤트 시간표(초): 20s 삼촌 차 저잔량, 45s 아빠 차 휴게소 도착, 90s 아빠 차 목적지 도착 */
    private fun scheduleEvents(group: Group, elapsedS: Int) {
        fun once(key: GroupEventType, at: Int, build: () -> GroupEvent) {
            if (elapsedS >= at && group.events.none { it.type == key.name }) group.events += build()
        }
        once(GroupEventType.LOW_ENERGY, 20) {
            group.event(GroupEventType.LOW_ENERGY, FOLLOWER, Severity.WARN, "삼촌 차 연료가 14% 남았어요.", buildJsonObject { put("kind", "FUEL"); put("pct", 14) })
        }
        once(GroupEventType.WAYPOINT_ARRIVED, 45) {
            group.event(GroupEventType.WAYPOINT_ARRIVED, LEADER, Severity.INFO, "아빠 차가 덕평휴게소에 도착했어요.", buildJsonObject { put("name", "덕평휴게소") })
        }
        once(GroupEventType.DESTINATION_ARRIVED, 90) {
            group.event(GroupEventType.DESTINATION_ARRIVED, LEADER, Severity.INFO, "아빠 차가 먼저 도착했어요.", buildJsonObject { put("name", DESTINATION) })
        }
    }

    /**
     * 30s 아빠 차 무전(F16). 실제 SDK 가 하는 일(오디오를 받아 두고 payload 에 `localAudioStatus`·`localAudioPath` 를 붙이기)을 흉내 낸다 —
     * 녹음 대신 신호음 WAV([FakeVoiceTone])를 앱 캐시(`java.io.tmpdir`)에 만든다. 호스트 화면 코드는 실서버와 같은 경로로 처리한다.
     */
    private suspend fun scheduleVoice(group: Group, elapsedS: Int) {
        if (elapsedS < 30 || group.events.any { it.type == GroupEventType.VOICE_MESSAGE.name }) return
        val tone = withContext(Dispatchers.IO) {
            runCatching { FakeVoiceTone.write(File(System.getProperty("java.io.tmpdir") ?: ".", "donghaeng-demo-voice")) }.getOrNull()
        }
        val now = System.currentTimeMillis()
        val base = DhJson.payloadOf(
            VoiceMessagePayload(
                voiceId = "vm_demo_${group.id.takeLast(4)}_1",
                durationMs = tone?.durationMs ?: 1_500,
                mime = "audio/wav",
                sizeBytes = tone?.sizeBytes ?: 0,
                expiresAtMs = now + VoiceEvents.RETENTION_MS,
            ),
        )
        val payload = JsonObject(
            base + buildMap {
                put(VoiceEvents.KEY_STATUS, JsonPrimitive(if (tone != null) VoiceAudioStatus.READY.name else VoiceAudioStatus.FAILED.name))
                tone?.let { put(VoiceEvents.KEY_PATH, JsonPrimitive(it.file.absolutePath)) }
            },
        )
        group.events += group.event(GroupEventType.VOICE_MESSAGE, LEADER, Severity.INFO, "아빠 차 무전", payload)
    }

    /** 서울시청(37.5665, 126.9780)에서 동쪽으로 틱마다 ~45m 이동. 선두가 앞, 내 차·삼촌 차가 뒤따른다 */
    private fun memberState(m: GroupMember, index: Int, tick: Int, elapsedS: Int): MemberState {
        val offset = when (index) { 0 -> 0.0; 1 -> -0.0055; else -> -0.0105 }
        val progress = tick * 0.00045
        val lat = BASE_LAT + sin(tick * 0.08 + index) * 0.0003
        val lon = BASE_LON + progress + offset
        val speed = 76f + (index * 7) + (sin(tick * 0.3 + index) * 6).toFloat()
        val arrived = index == 0 && elapsedS >= 90
        val remainS = max(0, 6600 - elapsedS * 40 - index * 240)
        val remainM = (remainS * 25.2).roundToInt()
        val fuel = when (index) { 0 -> 62 - elapsedS / 30; 1 -> 48 - elapsedS / 40; else -> max(9, 14 - elapsedS / 60) }
        return MemberState(
            vehicleId = m.vehicleId,
            nickname = m.nickname,
            color = m.color,
            online = true,
            lat = lat,
            lon = lon,
            heading = 90f + (cos(tick * 0.08 + index) * 8).toFloat(),
            speedKph = if (arrived) 0f else speed,
            ageMs = 800L + index * 150,
            eta = Eta(destTs = System.currentTimeMillis() + remainS * 1000L, remainM = remainM, remainS = remainS),
            energy = if (index == 1) Energy(socPct = 71 - elapsedS / 50) else Energy(fuelPct = fuel),
            distanceToLeaderM = (index * 480 + (sin(tick * 0.2) * 40).toInt()).coerceAtLeast(0),
            status = if (arrived) MemberStatus.ARRIVED else MemberStatus.DRIVING,
        )
    }

    private fun Group.event(type: GroupEventType, source: String?, severity: Severity, speech: String?, payload: kotlinx.serialization.json.JsonObject) =
        GroupEvent(eventId = "evt_demo_${id.takeLast(4)}_${++eventSeq}", type = type.name, sourceVehicleId = source, severity = severity, speech = speech, payload = payload)

    companion object {
        const val GROUP_NAME = "추석 귀성길"
        const val DESTINATION = "경포해변"
        const val LEADER = "veh_demo_leader"
        const val FOLLOWER = "veh_demo_follower"
        const val BASE_LAT = 37.5665
        const val BASE_LON = 126.9780
    }
}
