package app.donghaeng.companion.demo.donghaeng

import android.util.Log
import app.donghaeng.companion.demo.DemoApplication
import app.donghaeng.companion.sdk.DongHaengException
import app.donghaeng.companion.sdk.GroupJoinResult
import app.donghaeng.companion.sdk.GroupMember
import app.donghaeng.companion.sdk.GroupSnapshot
import app.donghaeng.companion.sdk.MyVehicle
import app.donghaeng.companion.sdk.PairResult
import app.donghaeng.protocol.GroupEvent
import app.donghaeng.protocol.GroupEventType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 데모 호스트가 동행과 통신하는 방식 — 실서버(SDK) 또는 호스트 쪽 가짜 */
enum class DhMode { SDK, FAKE }

/** 지금 참여 중인 그룹(호스트가 기억하는 최소 정보). 상세 상태는 [DongHaengStore.snapshot] */
data class ActiveGroup(
    val groupId: String,
    val name: String,
    val myVehicleId: String,
    val myVehicleNickname: String,
    val members: List<GroupMember>,
    val joinedAtMs: Long,
)

/** 알림 벨(F) 항목 — 그룹 이벤트 하나 */
data class DhNotification(
    val id: String,
    val type: GroupEventType?,
    val title: String,
    val body: String,
    val atMs: Long,
    val read: Boolean = false,
)

/** 마이카 > 연동 서비스 > 동행 그룹 주행 설정(E) */
data class SharingSettings(
    val location: Boolean = true,
    val energy: Boolean = true,
    val hardBrake: Boolean = true,
)

/** 지난 그룹 기록(E·C) */
data class PastGroup(
    val groupId: String,
    val name: String,
    val dateText: String,
    val memberCount: Int,
    val destinationName: String,
)

/**
 * 동행 관련 앱 상태의 단일 보관소(프로세스 수명). 화면들은 이 StateFlow 만 구독하고, 서버·SDK 호출은 [gateway] 로만 나간다.
 * 공식 앱에 이식할 때는 이 클래스가 "동행 모듈을 감싼 호스트 쪽 뷰모델/리포지토리" 자리다.
 */
object DongHaengStore {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _mode = MutableStateFlow(DhMode.SDK)
    val mode: StateFlow<DhMode> = _mode.asStateFlow()

    @Volatile
    private var gateway: DongHaengGateway = FakeGateway()

    private val _vehicles = MutableStateFlow<List<MyVehicle>>(emptyList())
    val vehicles: StateFlow<List<MyVehicle>> = _vehicles.asStateFlow()

    private val _vehiclesError = MutableStateFlow<String?>(null)
    val vehiclesError: StateFlow<String?> = _vehiclesError.asStateFlow()

    private val _activeGroup = MutableStateFlow<ActiveGroup?>(null)
    val activeGroup: StateFlow<ActiveGroup?> = _activeGroup.asStateFlow()

    private val _snapshot = MutableStateFlow<GroupSnapshot?>(null)
    val snapshot: StateFlow<GroupSnapshot?> = _snapshot.asStateFlow()

    private val _groupError = MutableStateFlow<String?>(null)
    val groupError: StateFlow<String?> = _groupError.asStateFlow()

    private val _notifications = MutableStateFlow<List<DhNotification>>(emptyList())
    val notifications: StateFlow<List<DhNotification>> = _notifications.asStateFlow()

    private val _sharing = MutableStateFlow(SharingSettings())
    val sharing: StateFlow<SharingSettings> = _sharing.asStateFlow()

    /** 차량별 동행 참여 허용(E). 비어 있으면 전부 허용 */
    private val _participation = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val participation: StateFlow<Map<String, Boolean>> = _participation.asStateFlow()

    private val _pastGroups = MutableStateFlow(
        listOf(
            PastGroup("grp_past_1", "가을 단풍 드라이브", "2026. 10. 18.", 3, "내장산국립공원"),
            PastGroup("grp_past_2", "주말 캠핑", "2026. 10. 4.", 2, "가평 자라섬"),
        ),
    )
    val pastGroups: StateFlow<List<PastGroup>> = _pastGroups.asStateFlow()

    /** 앱이 화면에 있는지 — 백그라운드면 새 이벤트를 시스템 알림으로 올린다 */
    @Volatile
    var appInForeground: Boolean = true

    /** 시스템 알림 출구(F). MainActivity/Application 이 끼운다 */
    @Volatile
    var notifier: ((DhNotification) -> Unit)? = null

    private var observeJob: Job? = null
    private val seenEventIds = HashSet<String>()

    // ---------- 모드 ----------

    /** 로그인 직후(SDK) 또는 "서버 없이 둘러보기"(FAKE)에서 호출 */
    fun configure(mode: DhMode, gateway: DongHaengGateway) {
        this.gateway = gateway
        _mode.value = mode
        reset()
        refreshVehicles()
        Log.i(DemoApplication.TAG, "DongHaengStore configured mode=$mode gateway=${gateway::class.simpleName}")
    }

    fun reset() {
        observeJob?.cancel()
        observeJob = null
        seenEventIds.clear()
        _activeGroup.value = null
        _snapshot.value = null
        _groupError.value = null
        _notifications.value = emptyList()
        _vehicles.value = emptyList()
        _vehiclesError.value = null
    }

    // ---------- 내 차 ----------

    fun refreshVehicles() {
        scope.launch {
            try {
                _vehicles.value = gateway.listMyVehicles()
                _vehiclesError.value = null
            } catch (e: DongHaengException) {
                _vehiclesError.value = e.toUserText()
            } catch (e: Exception) {
                _vehiclesError.value = "내 차 목록을 불러오지 못했어요"
            }
        }
    }

    // ---------- A. 합류 ----------

    suspend fun previewInvite(qrPayload: String): InvitePreview? = gateway.previewInvite(qrPayload)

    /** 합류 후 스냅샷 구독을 시작한다. 실패는 예외로 */
    suspend fun join(qrPayload: String, vehicle: MyVehicle?): GroupJoinResult {
        val result = gateway.joinGroup(qrPayload, vehicle?.vehicleId)
        val mine = vehicle ?: _vehicles.value.firstOrNull { it.isPrimary } ?: _vehicles.value.firstOrNull()
        val myId = mine?.vehicleId ?: result.members.lastOrNull()?.vehicleId.orEmpty()
        val myNick = mine?.nickname ?: result.members.firstOrNull { it.vehicleId == myId }?.nickname.orEmpty()
        _activeGroup.value = ActiveGroup(result.groupId, result.name, myId, myNick, result.members, System.currentTimeMillis())
        _snapshot.value = null
        _groupError.value = null
        seenEventIds.clear()
        startObserving(result.groupId)
        pushNotification(DhNotification("joined_${result.groupId}", GroupEventType.MEMBER_JOINED, "동행 그룹 합류", "'${result.name}' 그룹에 ${myNick}(으)로 합류했어요", System.currentTimeMillis()), system = false)
        return result
    }

    private fun startObserving(groupId: String) {
        observeJob?.cancel()
        observeJob = scope.launch {
            gateway.observeGroup(groupId)
                .catch { e ->
                    _groupError.value = (e as? DongHaengException)?.toUserText() ?: "그룹 상태를 받지 못했어요"
                    Log.w(DemoApplication.TAG, "observeGroup failed cause=${e.javaClass.simpleName}")
                }
                .collect { snap ->
                    _snapshot.value = snap
                    _groupError.value = null
                    onEvents(snap.events)
                }
        }
    }

    /** 이 폰에서 그룹 보기를 끝낸다(종료된 그룹 정리). 차량은 차에서 나가기를 누른다 */
    fun leaveLocally() {
        val group = _activeGroup.value ?: return
        observeJob?.cancel()
        observeJob = null
        _pastGroups.update { list ->
            listOf(PastGroup(group.groupId, group.name, todayText(), group.members.size, _snapshot.value?.let { "" } ?: "")) + list
        }
        _activeGroup.value = null
        _snapshot.value = null
    }

    /** 차량 등록 QR 승인(대체 수단) — 스캔 화면에서 */
    suspend fun pairVehicle(qrPayload: String, nickname: String): PairResult = gateway.pairVehicle(qrPayload, nickname)

    // ---------- G. 메시지 ----------

    suspend fun sendMessage(text: String) {
        val group = _activeGroup.value ?: throw DongHaengException.Api("NO_GROUP", "참여 중인 그룹이 없어요")
        gateway.sendPassengerMessage(group.groupId, text)
    }

    // ---------- E. 설정 ----------

    fun updateSharing(transform: (SharingSettings) -> SharingSettings) = _sharing.update(transform)

    fun setParticipation(vehicleId: String, allowed: Boolean) = _participation.update { it + (vehicleId to allowed) }

    fun isParticipating(vehicleId: String): Boolean = _participation.value[vehicleId] ?: true

    // ---------- F. 알림 ----------

    val unreadCount: Int get() = _notifications.value.count { !it.read }

    fun markAllRead() = _notifications.update { list -> list.map { it.copy(read = true) } }

    private fun onEvents(events: List<GroupEvent>) {
        val fresh = events.filter { seenEventIds.add(it.eventId) }
        if (fresh.isEmpty()) return
        val now = System.currentTimeMillis()
        // 처음 받은 스냅샷의 과거 이벤트는 알림으로 올리지 않는다(이미 읽은 것으로)
        val initial = _notifications.value.none { it.id.startsWith("evt") }
        fresh.forEach { e ->
            val n = e.toNotification(now, read = initial && fresh.size > 1)
            pushNotification(n, system = !initial)
        }
    }

    private fun pushNotification(n: DhNotification, system: Boolean) {
        _notifications.update { listOf(n) + it.filterNot { old -> old.id == n.id } }
        if (system && !appInForeground) notifier?.invoke(n)
    }

    private fun todayText(): String {
        val c = java.util.Calendar.getInstance()
        return "%d. %d. %d.".format(c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH))
    }
}

private fun GroupEvent.toNotification(now: Long, read: Boolean): DhNotification {
    val type = eventType
    val title = when (type) {
        GroupEventType.MEMBER_JOINED -> "멤버 합류"
        GroupEventType.MEMBER_LEFT -> "멤버 이탈"
        GroupEventType.LEADER_CHANGED -> "선두 변경"
        GroupEventType.LOW_ENERGY -> "연료·배터리 부족"
        GroupEventType.WAYPOINT_ARRIVED -> "휴게소 도착"
        GroupEventType.DESTINATION_ARRIVED -> "목적지 도착"
        GroupEventType.HARD_BRAKE -> "급제동"
        GroupEventType.PASSENGER_MESSAGE -> "동승자 메시지"
        GroupEventType.GROUP_ENDED -> "그룹 종료"
        GroupEventType.REST_STOP_PROPOSAL -> "휴게소 제안"
        GroupEventType.DEVIATION -> "경로 이탈"
        GroupEventType.MEMBER_OFFLINE -> "연결 끊김"
        else -> "동행 알림"
    }
    return DhNotification(id = eventId, type = type, title = title, body = speech ?: eventLabel(this.type), atMs = now, read = read)
}

/** 동행 예외 → 사용자 문구(공식 앱은 자기 디자인 가이드로 매핑한다) */
fun DongHaengException.toUserText(): String = when (this) {
    is DongHaengException.NotInitialized -> "먼저 로그인해 주세요"
    is DongHaengException.Unauthorized -> "계정 인증이 만료됐어요. 다시 로그인해 주세요"
    is DongHaengException.InvalidQr -> reason
    is DongHaengException.OtherServer -> "다른 서버의 QR이에요 ($host)"
    is DongHaengException.Api -> message ?: "요청에 실패했어요 [$code]"
}
