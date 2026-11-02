package app.donghaeng.companion.demo.donghaeng

import android.util.Log
import app.donghaeng.companion.demo.BuildConfig
import app.donghaeng.companion.demo.DemoApplication
import app.donghaeng.companion.demo.DemoStorage
import app.donghaeng.companion.demo.MemoryDemoStorage
import app.donghaeng.companion.demo.SavedGroup
import app.donghaeng.companion.sdk.DongHaengException
import app.donghaeng.companion.sdk.GroupJoinResult
import app.donghaeng.companion.sdk.GroupMember
import app.donghaeng.companion.sdk.GroupSnapshot
import app.donghaeng.companion.sdk.MemberRole
import app.donghaeng.companion.sdk.MyVehicle
import app.donghaeng.companion.sdk.PairResult
import app.donghaeng.protocol.EventAudience
import app.donghaeng.protocol.GroupEvent
import app.donghaeng.protocol.GroupEventType
import app.donghaeng.protocol.MemberState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 데모 호스트가 동행과 통신하는 방식 — 실서버(SDK) 또는 호스트 쪽 가짜 */
enum class DhMode { SDK, FAKE }

/**
 * 지금 참여 중인 그룹(호스트가 기억하는 최소 정보). 상세 상태는 [DongHaengStore.snapshot].
 *
 * @param myVehicleConfirmed [myVehicleId] 가 사용자가 고른(또는 내 차 목록에서 확인한) 차인지 — 아니면 메시지에 보낸 차로 싣지 않는다
 * @param destinationName 합류 때 초대 미리보기의 목적지(지난 그룹 기록용, D087)
 * @param leaderVehicleId 합류 때의 방장. 이후에는 스냅샷의 값이 우선이다([currentLeaderId], D084)
 */
data class ActiveGroup(
    val groupId: String,
    val name: String,
    val myVehicleId: String,
    val myVehicleNickname: String,
    val members: List<GroupMember>,
    val joinedAtMs: Long,
    val myVehicleConfirmed: Boolean = true,
    val destinationName: String = "",
    val leaderVehicleId: String? = members.firstOrNull { it.role == MemberRole.LEADER }?.vehicleId,
)

/** 지금 방장 — 스냅샷(서버 매 폴링 값)이 있으면 그것, 없으면 합류 때 값 */
fun currentLeaderId(group: ActiveGroup, snapshot: GroupSnapshot?): String? = snapshot?.leaderVehicleId ?: group.leaderVehicleId

/** 그룹 관찰의 연결 상태 — 홈 카드·동행 메인·지도 시트가 그대로 보여 준다(D021·D022·D028) */
enum class GroupLink {
    /** 첫 스냅샷을 기다리는 중 */
    CONNECTING,

    /** 스냅샷을 받고 있다 */
    LIVE,

    /** 일시 오류로 끊겨 다시 연결하는 중(마지막 스냅샷은 오래됐을 수 있다) */
    RECONNECTING,

    /** 그룹이 끝났다 — 메시지 불가, 기록으로 옮길 수 있다 */
    ENDED,

    /** 내 차가 그룹에서 나갔거나 그룹을 볼 수 없다 */
    LEFT,

    /** 계정 인증 만료 — 다시 로그인 */
    AUTH_EXPIRED,

    /** 다시 해도 나아지지 않을 오류 — [다시 시도] */
    FAILED,
}

data class GroupConnection(
    val link: GroupLink = GroupLink.CONNECTING,
    /** 사용자 문구(LIVE 면 null) */
    val message: String? = null,
    /** 마지막으로 스냅샷을 받은 시각(폰 시계) */
    val lastSnapshotAtMs: Long? = null,
) {
    /** 그룹이 더 진행되지 않음 — 메시지 보내기를 막고 [기록으로 옮기기]를 보인다 */
    val finished: Boolean get() = link == GroupLink.ENDED || link == GroupLink.LEFT

    /** [다시 시도]가 뜻이 있는 상태(인증 만료도 — 다시 시도하면 호스트 토큰을 새로 받아 볼 수 있다) */
    val retryable: Boolean get() = link == GroupLink.RECONNECTING || link == GroupLink.FAILED || link == GroupLink.AUTH_EXPIRED
}

/** 알림 벨(F) 항목 — 그룹 이벤트 하나 */
data class DhNotification(
    val id: String,
    val type: GroupEventType?,
    val title: String,
    val body: String,
    val atMs: Long,
    val read: Boolean = false,
)

/** 마이카 > 연동 서비스 > 동행 그룹 주행 설정(E) — 공유 항목 화면 표시용(아직 차량·서버에 반영되지 않음, D078) */
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
 * 동행 관련 앱 상태의 단일 보관소(프로세스 수명 + [storage] 에 진행 중 그룹·참여 설정). 화면들은 이 StateFlow 만 구독하고,
 * 서버·SDK 호출은 [gateway] 로만 나간다. 공식 앱에 이식할 때는 이 클래스가 "동행 모듈을 감싼 호스트 쪽 뷰모델/리포지토리" 자리다.
 *
 * 그룹 관찰은 [GroupObservation] 으로 한다 — 일시 오류는 백오프로 다시 구독하고, 종료·나감·인증 만료는 [connection] 으로 화면에 알린다.
 */
object DongHaengStore {

    /** 관찰·목록 불러오기가 도는 범위(프로세스 수명). 단위 테스트는 가상 시간 디스패처 범위로 바꿔 끼운다 */
    @Volatile
    internal var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _mode = MutableStateFlow(DhMode.SDK)
    val mode: StateFlow<DhMode> = _mode.asStateFlow()

    /** 로그인·시연 모드 전에는 모든 호출이 NotInitialized(D029 — 예전 기본값 FakeGateway 는 복원된 화면에서 가짜 데이터를 실데이터처럼 보였다) */
    @Volatile
    private var gateway: DongHaengGateway = NotConfiguredGateway()

    /** 진행 중 그룹·참여 설정 저장소. [DemoApplication] 이 SharedPreferences 구현을 끼운다 */
    @Volatile
    var storage: DemoStorage = MemoryDemoStorage()

    /** 지금 로그인한 서버 계정(SDK 모드). 진행 중 그룹 저장·복원의 주인 확인용. FAKE 면 null(저장하지 않음) */
    @Volatile
    private var ownerUserId: String? = null

    /** 관찰 재시도 대기(테스트에서 짧게) */
    @Volatile
    internal var backoff: (Int) -> Long = GroupObservation::backoffMs

    /** 현재 시각(테스트에서 고정) */
    @Volatile
    internal var clock: () -> Long = System::currentTimeMillis

    private val _vehicles = MutableStateFlow<List<MyVehicle>>(emptyList())
    val vehicles: StateFlow<List<MyVehicle>> = _vehicles.asStateFlow()

    private val _vehiclesError = MutableStateFlow<String?>(null)
    val vehiclesError: StateFlow<String?> = _vehiclesError.asStateFlow()

    private val _vehiclesLoading = MutableStateFlow(false)
    val vehiclesLoading: StateFlow<Boolean> = _vehiclesLoading.asStateFlow()

    private val _activeGroup = MutableStateFlow<ActiveGroup?>(null)
    val activeGroup: StateFlow<ActiveGroup?> = _activeGroup.asStateFlow()

    private val _snapshot = MutableStateFlow<GroupSnapshot?>(null)
    val snapshot: StateFlow<GroupSnapshot?> = _snapshot.asStateFlow()

    private val _connection = MutableStateFlow(GroupConnection())
    val connection: StateFlow<GroupConnection> = _connection.asStateFlow()

    /** [connection] 의 문구(LIVE 면 null) — 예전 이름 유지 */
    private val _groupError = MutableStateFlow<String?>(null)
    val groupError: StateFlow<String?> = _groupError.asStateFlow()

    private val _notifications = MutableStateFlow<List<DhNotification>>(emptyList())
    val notifications: StateFlow<List<DhNotification>> = _notifications.asStateFlow()

    /** F16 무전 목록(최신부터) — 스냅샷의 `VOICE_MESSAGE` 를 모아 둔다. 오디오 파일은 동행 모듈이 받아 둔다 */
    private val _voices = MutableStateFlow<List<VoiceItem>>(emptyList())
    val voices: StateFlow<List<VoiceItem>> = _voices.asStateFlow()

    private val _sharing = MutableStateFlow(SharingSettings())
    val sharing: StateFlow<SharingSettings> = _sharing.asStateFlow()

    /** 차량별 동행 참여 허용(E) — 이 폰의 합류 시트에서 고를 수 있는지. 비어 있으면 전부 허용 */
    private val _participation = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val participation: StateFlow<Map<String, Boolean>> = _participation.asStateFlow()

    private val _pastGroups = MutableStateFlow<List<PastGroup>>(emptyList())
    val pastGroups: StateFlow<List<PastGroup>> = _pastGroups.asStateFlow()

    /** 앱이 화면에 있는지 — 백그라운드면 새 이벤트를 시스템 알림으로 올린다 */
    @Volatile
    var appInForeground: Boolean = true

    /** 시스템 알림 출구(F). [DemoApplication] 이 Application 컨텍스트로 끼운다(D086 — Activity 를 붙잡지 않게) */
    @Volatile
    var notifier: ((DhNotification) -> Unit)? = null

    private var observeJob: Job? = null
    private val seenEventIds = HashSet<String>()

    /** 지금 그룹의 첫 스냅샷을 아직 처리하지 않음 — 그 스냅샷의 지난 이벤트는 읽은 것으로(시스템 알림 없음, D083) */
    private var firstSnapshotPending = true

    // ---------- 모드 ----------

    /**
     * 로그인 직후(SDK) 또는 "서버 없이 둘러보기"(FAKE)에서 호출.
     * @param ownerUserId SDK 로그인 계정 — 같은 계정의 진행 중 그룹이 저장돼 있으면 관찰을 이어 간다(D024)
     */
    fun configure(mode: DhMode, gateway: DongHaengGateway, ownerUserId: String? = null) {
        this.gateway = gateway
        _mode.value = mode
        reset()
        this.ownerUserId = if (mode == DhMode.SDK) ownerUserId else null
        // 지난 그룹 예시는 시연 모드에만(D092 — 실서버 로그인에 가짜 기록이 보이지 않게)
        _pastGroups.value = if (mode == DhMode.FAKE) FAKE_PAST_GROUPS else emptyList()
        _participation.value = if (mode == DhMode.SDK) storage.loadParticipationOff().associateWith { false } else emptyMap()
        refreshVehicles()
        Log.i(DemoApplication.TAG, "DongHaengStore configured mode=$mode gateway=${gateway::class.simpleName}")
        if (mode == DhMode.SDK) restoreSavedGroup()
    }

    /** 로그아웃·다시 설정 — 메모리 상태만 비운다(저장된 진행 중 그룹은 같은 계정이 다시 로그인하면 이어 간다) */
    fun reset() {
        observeJob?.cancel()
        observeJob = null
        seenEventIds.clear()
        firstSnapshotPending = true
        ownerUserId = null
        _activeGroup.value = null
        _snapshot.value = null
        _connection.value = GroupConnection()
        _groupError.value = null
        _notifications.value = emptyList()
        VoicePlayer.stop("reset")
        _voices.value = emptyList()
        _vehicles.value = emptyList()
        _vehiclesError.value = null
        _vehiclesLoading.value = false
    }

    private fun restoreSavedGroup() {
        val saved = storage.loadActiveGroup() ?: return
        if (saved.ownerUserId != ownerUserId) {
            storage.saveActiveGroup(null) // 다른 계정의 그룹은 되살리지 않는다
            return
        }
        _activeGroup.value = ActiveGroup(
            groupId = saved.groupId,
            name = saved.name,
            myVehicleId = saved.myVehicleId,
            myVehicleNickname = saved.myVehicleNickname,
            members = emptyList(),
            joinedAtMs = saved.joinedAtMs,
            myVehicleConfirmed = saved.myVehicleConfirmed,
            destinationName = saved.destinationName,
            leaderVehicleId = saved.leaderVehicleId,
        )
        Log.i(DemoApplication.TAG, "active group restored")
        startObserving(saved.groupId)
    }

    // ---------- 내 차 ----------

    fun refreshVehicles() {
        val gw = gateway
        _vehiclesLoading.value = true
        scope.launch {
            try {
                val list = gw.listMyVehicles()
                if (gw === gateway) {
                    _vehicles.value = list
                    _vehiclesError.value = null
                }
            } catch (e: DongHaengException) {
                if (gw === gateway) _vehiclesError.value = e.toUserText()
            } catch (e: Exception) {
                if (gw === gateway) _vehiclesError.value = "내 차 목록을 불러오지 못했어요"
            } finally {
                if (gw === gateway) _vehiclesLoading.value = false
            }
        }
    }

    // ---------- A. 합류 ----------

    suspend fun previewInvite(qrPayload: String): InvitePreview? = gateway.previewInvite(qrPayload)

    /**
     * 합류 후 스냅샷 구독을 시작한다. 실패는 예외로.
     * 같은 그룹이면(모듈이 409 ALREADY_MEMBER 를 성공으로 돌려준 경우 포함) 알림·무전·본 이벤트를 그대로 두고 관찰만 다시 시작한다.
     * 다른 그룹이면 이전 그룹은 지난 그룹으로 옮기고(차는 서버가 이전 그룹에서 자동으로 뺀다, D082) 그 그룹의 소식은 지운다(D083).
     *
     * @param destinationName 초대 미리보기의 목적지(지난 그룹 기록용)
     */
    suspend fun join(qrPayload: String, vehicle: MyVehicle?, destinationName: String = ""): GroupJoinResult {
        val result = gateway.joinGroup(qrPayload, vehicle?.vehicleId)
        val myVehicles = _vehicles.value
        val confirmedId = vehicle?.vehicleId
            ?: myVehicles.firstOrNull { v -> v.isPrimary && result.members.any { it.vehicleId == v.vehicleId } }?.vehicleId
            ?: myVehicles.firstOrNull { v -> result.members.any { it.vehicleId == v.vehicleId } }?.vehicleId
        val myId = confirmedId ?: result.members.lastOrNull()?.vehicleId.orEmpty()
        val myNick = vehicle?.nickname
            ?: myVehicles.firstOrNull { it.vehicleId == myId }?.nickname
            ?: result.members.firstOrNull { it.vehicleId == myId }?.nickname.orEmpty()

        val previous = _activeGroup.value
        val sameGroup = previous?.groupId == result.groupId
        if (!sameGroup) {
            previous?.let { archive(it) }
            seenEventIds.clear()
            firstSnapshotPending = true
            _notifications.update { list -> list.filterNot { it.id.startsWith(EVENT_ID_PREFIX) } }
            _snapshot.value = null
            VoicePlayer.stop("switch_group")
            _voices.value = emptyList()
        }
        val group = ActiveGroup(
            groupId = result.groupId,
            name = result.name,
            myVehicleId = myId,
            myVehicleNickname = myNick,
            members = result.members,
            joinedAtMs = if (sameGroup) previous!!.joinedAtMs else clock(),
            myVehicleConfirmed = confirmedId != null,
            destinationName = destinationName.ifBlank { if (sameGroup) previous!!.destinationName else "" },
        )
        _activeGroup.value = group
        save(group)
        startObserving(result.groupId)
        pushNotification(
            DhNotification("joined_${result.groupId}", GroupEventType.MEMBER_JOINED, "동행 그룹 합류", "'${result.name}' 그룹에 ${myNick}(으)로 합류했어요", clock()),
            system = false,
        )
        return result
    }

    /** 끊긴 관찰을 바로 다시 시작한다(화면의 [다시 시도]). 끝난 그룹이면 하지 않는다 */
    fun retryObserving() {
        val group = _activeGroup.value ?: return
        if (_connection.value.finished) return
        startObserving(group.groupId)
    }

    private fun startObserving(groupId: String) {
        observeJob?.cancel()
        _connection.value = GroupConnection(GroupLink.CONNECTING, null, _connection.value.lastSnapshotAtMs)
        val gw = gateway
        observeJob = scope.launch {
            GroupObservation.observe(source = { gw.observeGroup(groupId) }, backoff = backoff).collect { event ->
                if (_activeGroup.value?.groupId != groupId) return@collect
                when (event) {
                    is ObserveEvent.Snapshot -> onSnapshot(event.snapshot)
                    is ObserveEvent.Retrying -> {
                        val message = "연결이 불안정해요 · 다시 연결하는 중"
                        _connection.value = GroupConnection(GroupLink.RECONNECTING, message, _connection.value.lastSnapshotAtMs)
                        _groupError.value = message
                        Log.w(DemoApplication.TAG, "observeGroup retry attempt=${event.attempt} waitMs=${event.delayMs} cause=${event.cause.logCause()}")
                    }
                    is ObserveEvent.Stopped -> onStopped(event)
                }
            }
        }
    }

    private fun onSnapshot(snap: GroupSnapshot) {
        _snapshot.value = snap
        _connection.value = GroupConnection(GroupLink.LIVE, null, clock())
        _groupError.value = null
        updateVoices(snap)
        onEvents(snap.events, snap.members)
    }

    private fun onStopped(event: ObserveEvent.Stopped) {
        val last = _connection.value.lastSnapshotAtMs
        val connection = when (event.reason) {
            StopReason.ENDED -> GroupConnection(GroupLink.ENDED, "그룹이 끝났어요", last)
            StopReason.NOT_MEMBER -> GroupConnection(GroupLink.LEFT, "내 차가 그룹에서 나갔거나 더 볼 수 없는 그룹이에요", last)
            StopReason.UNAUTHORIZED -> GroupConnection(GroupLink.AUTH_EXPIRED, "계정 인증이 만료됐어요. 다시 로그인해 주세요", last)
            StopReason.FAILED -> GroupConnection(
                GroupLink.FAILED,
                (event.cause as? DongHaengException)?.toUserText() ?: "그룹 상태를 받지 못했어요",
                last,
            )
        }
        _connection.value = connection
        _groupError.value = connection.message
        Log.w(DemoApplication.TAG, "observeGroup stopped reason=${event.reason} cause=${event.cause?.logCause()}")
        if (connection.finished) {
            storage.saveActiveGroup(null) // 다시 띄워도 끝난 그룹을 되살리지 않는다
            val ended = VoiceEvents.merge(_voices.value, emptyList(), groupEnded = true)
            if (ended != _voices.value) _voices.value = ended
            VoicePlayer.stop("group_finished")
        }
    }

    /**
     * 스냅샷의 `VOICE_MESSAGE` 를 무전 목록에 합친다(같은 이벤트는 상태만 갱신 — 받는 중 → 재생 가능).
     * 무전은 그룹 전체 대상(audience 없음, docs/03 §5.4)이지만 다른 이벤트와 같은 [EventAudience] 판정을 거친다.
     * 로그 `voice: received …` 는 기기 확인용(voiceId·상태·길이만).
     */
    private fun updateVoices(snap: GroupSnapshot) {
        val group = _activeGroup.value
        val myId = group?.myVehicleId?.takeIf { it.isNotBlank() }
        val incoming = snap.events
            .filter { it.eventType == GroupEventType.VOICE_MESSAGE && EventAudience.isForMe(it, myId, snap.members) }
            .mapNotNull { VoiceEvents.toItem(it, snap.members, group) }
        val ended = snap.isGroupEnded()
        if (incoming.isEmpty() && _voices.value.isEmpty()) return
        val before = _voices.value.associateBy { it.eventId }
        val merged = VoiceEvents.merge(_voices.value, incoming, groupEnded = ended)
        merged.forEach { item ->
            val old = before[item.eventId]
            if (old == null || old.status != item.status) {
                Log.i(DemoApplication.TAG, "voice: received voiceId=${item.voiceId} status=${item.status} durMs=${item.durationMs} mine=${item.mine}")
            }
        }
        if (merged != _voices.value) _voices.value = merged
        // 재생 중인 무전이 만료·그룹 종료로 사라지면 멈춘다
        val playing = VoicePlayer.state.value.voiceId
        if (playing != null && merged.none { it.voiceId == playing && it.status == VoiceAudioStatus.READY }) VoicePlayer.stop("expired")
    }

    /**
     * 이 폰에서 그룹 보기를 끝낸다(지난 그룹으로 옮김). 차는 그룹에 그대로 있다 — 차는 차에서 '나가기'를 누른다.
     * 다시 보려면 같은 그룹 QR 을 다시 스캔하면 된다(모듈이 '이미 멤버'를 성공으로 돌려준다).
     */
    fun leaveLocally() {
        val group = _activeGroup.value ?: return
        observeJob?.cancel()
        observeJob = null
        archive(group)
        _activeGroup.value = null
        _snapshot.value = null
        _connection.value = GroupConnection()
        _groupError.value = null
        storage.saveActiveGroup(null)
        VoicePlayer.stop("leave")
        _voices.value = emptyList()
    }

    /** 지난 그룹 맨 앞에 넣는다(같은 그룹이면 바꿔 넣음). 목적지는 합류 때 미리보기 값, 인원은 마지막 스냅샷 기준(D087) */
    private fun archive(group: ActiveGroup) {
        val memberCount = _snapshot.value?.takeIf { it.groupId == group.groupId }?.members?.size ?: group.members.size
        val entry = PastGroup(group.groupId, group.name, todayText(), memberCount, group.destinationName)
        _pastGroups.update { list -> listOf(entry) + list.filterNot { it.groupId == group.groupId } }
    }

    /** '지난 그룹 기록 → 전체 삭제'(이 폰의 기록만) */
    fun clearPastGroups() {
        _pastGroups.value = emptyList()
    }

    /** 차량 등록 QR 승인(대체 수단) — 스캔 화면에서 */
    suspend fun pairVehicle(qrPayload: String, nickname: String): PairResult = gateway.pairVehicle(qrPayload, nickname)

    // ---------- G. 메시지 ----------

    /**
     * 동승자 메시지. 보낸 차 = 이 폰이 합류시킨 내 차(확인된 경우만 — 아니면 서버 기본, D025).
     * @param idempotencyKey 사용자 동작 한 번의 키 — 응답이 사라져 다시 보낼 때 같은 키를 주면 차에서 두 번 읽히지 않는다(D085)
     */
    suspend fun sendMessage(text: String, idempotencyKey: String) {
        val group = _activeGroup.value ?: throw DongHaengException.Api("NO_GROUP", "참여 중인 그룹이 없어요")
        if (_connection.value.finished) throw DongHaengException.Api("GROUP_ENDED", "이미 끝났거나 내 차가 나간 그룹이에요")
        val from = group.myVehicleId.takeIf { group.myVehicleConfirmed && it.isNotBlank() }
        gateway.sendPassengerMessage(group.groupId, text, from, idempotencyKey)
    }

    // ---------- E. 설정 ----------

    fun updateSharing(transform: (SharingSettings) -> SharingSettings) = _sharing.update(transform)

    /** 이 폰의 합류 시트에서 이 차를 고를 수 있는지(D078 — 설정이 실제로 효과가 있게). SDK 모드는 설치에 저장 */
    fun setParticipation(vehicleId: String, allowed: Boolean) {
        _participation.update { it + (vehicleId to allowed) }
        if (_mode.value == DhMode.SDK) storage.saveParticipationOff(participationOff())
    }

    fun isParticipating(vehicleId: String): Boolean = _participation.value[vehicleId] ?: true

    /** 참여를 꺼 둔 차 ID */
    fun participationOff(): Set<String> = _participation.value.filterValues { !it }.keys

    // ---------- F. 알림 ----------

    val unreadCount: Int get() = _notifications.value.count { !it.read }

    fun markAllRead() = _notifications.update { list -> list.map { it.copy(read = true) } }

    private fun onEvents(events: List<GroupEvent>, members: List<MemberState>) {
        val myId = _activeGroup.value?.myVehicleId?.takeIf { it.isNotBlank() }
        // 내 차가 대상이 아닌 이벤트(내 차보다 뒤에 있는 차의 급제동 — 문구가 "앞쪽 ○○ 차가 급제동")는 알림으로 올리지 않는다.
        // 차량 앱과 같은 규칙(EventAudience, docs/03 §5.4): 서버 audience 우선, 없으면 스냅샷 앞뒤, 모르면 올린다
        val fresh = events.filter { seenEventIds.add(it.eventId) }.filter { EventAudience.isForMe(it, myId, members) }
        // 이 그룹의 첫 스냅샷에 든 지난 이벤트는 알림으로 올리지 않는다(이미 읽은 것으로) — 그룹마다 판정(D083)
        val initial = firstSnapshotPending
        firstSnapshotPending = false
        if (fresh.isEmpty()) return
        val now = clock()
        fresh.forEach { e ->
            val n = e.toNotification(now, read = initial && fresh.size > 1)
            pushNotification(n, system = !initial)
        }
    }

    private fun pushNotification(n: DhNotification, system: Boolean) {
        _notifications.update { listOf(n) + it.filterNot { old -> old.id == n.id } }
        if (system && !appInForeground) notifier?.invoke(n)
    }

    private fun save(group: ActiveGroup) {
        val owner = ownerUserId ?: return // 시연 모드·로그인 전은 저장하지 않는다
        storage.saveActiveGroup(
            SavedGroup(
                ownerUserId = owner,
                groupId = group.groupId,
                name = group.name,
                myVehicleId = group.myVehicleId,
                myVehicleNickname = group.myVehicleNickname,
                myVehicleConfirmed = group.myVehicleConfirmed,
                destinationName = group.destinationName,
                leaderVehicleId = group.leaderVehicleId,
                joinedAtMs = group.joinedAtMs,
            ),
        )
    }

    private fun todayText(): String {
        val c = java.util.Calendar.getInstance()
        return "%d. %d. %d.".format(c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH))
    }

    /** 로그용 원인 — 예외 클래스와(동행 오류면) 코드만. 메시지 원문은 남기지 않는다(규칙 8) */
    private fun Throwable.logCause(): String =
        if (this is DongHaengException.Api) "Api($code)" else javaClass.simpleName

    private const val EVENT_ID_PREFIX = "evt"

    private val FAKE_PAST_GROUPS = listOf(
        PastGroup("grp_past_1", "가을 단풍 드라이브", "2026. 10. 18.", 3, "내장산국립공원"),
        PastGroup("grp_past_2", "주말 캠핑", "2026. 10. 4.", 2, "가평 자라섬"),
    )
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
        GroupEventType.VOICE_MESSAGE -> "무전"
        else -> "동행 알림"
    }
    val body = if (type == GroupEventType.VOICE_MESSAGE) {
        // "아빠 차 무전 · 0:07" — 재생은 알림 목록·동행 메인의 [재생] 버튼(폰은 자동 재생하지 않는다, VoicePlayer)
        val durationMs = (payload["durationMs"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
        listOfNotNull(speech ?: "그룹 무전", durationMs?.let(::durationLabel)).joinToString(" · ")
    } else {
        speech ?: eventLabel(this.type)
    }
    // 표시 시각 = 서버가 이벤트를 만든 시각(eventId 의 ULID), 모르면 받은 시각(D083)
    return DhNotification(id = eventId, type = type, title = title, body = body, atMs = EventTimes.atMs(eventId, now), read = read)
}

/** 동행 예외 → 사용자 문구(공식 앱은 자기 디자인 가이드로 매핑한다) */
fun DongHaengException.toUserText(publicBaseUrl: String = BuildConfig.DH_PUBLIC_BASE_URL): String = when (this) {
    is DongHaengException.NotInitialized -> "먼저 로그인해 주세요"
    is DongHaengException.Unauthorized -> "계정 인증이 만료됐어요. 다시 로그인해 주세요"
    is DongHaengException.InvalidQr -> reason
    // 어느 서버끼리 어긋났는지 보여 준다(D077 — 차와 폰 APK 를 다른 서버 주소로 빌드한 경우)
    is DongHaengException.OtherServer -> "다른 서버의 QR이에요 ($host). 이 앱의 서버: ${publicBaseUrl.trimEnd('/')}"
    is DongHaengException.Api -> when (code) {
        // 응답이 사라졌을 수 있다 — 같은 메시지로 다시 보내면 서버가 중복을 거른다(Idempotency-Key, D085)
        "NETWORK" -> "서버에 연결할 수 없어요. 다시 시도해 주세요"
        else -> message ?: "요청에 실패했어요 [$code]"
    }
}
