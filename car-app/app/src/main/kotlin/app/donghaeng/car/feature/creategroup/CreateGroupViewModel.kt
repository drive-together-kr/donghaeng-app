package app.donghaeng.car.feature.creategroup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.donghaeng.car.adapter.navi.NaviAdapter
import app.donghaeng.car.core.common.DhLog
import app.donghaeng.car.core.common.TokenClearReason
import app.donghaeng.car.core.common.TokenStore
import app.donghaeng.car.core.common.TokenStoreState
import app.donghaeng.car.core.network.ApiResult
import app.donghaeng.car.domain.group.GroupRepository
import app.donghaeng.car.domain.session.GroupSession
import app.donghaeng.car.domain.session.MqttSessionManager
import app.donghaeng.protocol.EventAudience
import app.donghaeng.protocol.GroupEventType
import app.donghaeng.protocol.Place
import app.donghaeng.protocol.rest.MemberDto
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 데모용 목적지 프리셋 — 내비 목적지를 못 읽을 때(Fake, 안내 중 아님) 고른다. 공공장소 좌표(개인 주소 아님) */
val DESTINATION_PRESETS: List<Place> = listOf(
    Place(37.7719, 128.9479, "경포해변", address = "강원특별자치도 강릉시 창해로 514"),
    Place(35.1152, 129.0403, "부산역", address = "부산광역시 동구 중앙대로 206"),
    Place(35.1595, 126.8526, "광주송정역", address = "광주광역시 광산구 상무대로 201"),
    Place(36.3504, 127.3845, "대전역", address = "대전광역시 동구 중앙로 215"),
    Place(37.5665, 126.9780, "서울시청", address = "서울특별시 중구 세종대로 110"),
)

val MAX_MEMBER_CHOICES: List<Int> = listOf(2, 4, 6, 10)

/** 화면 상태 — 그룹이 있으면 [Active], 없으면 [Form] */
sealed interface CreateGroupState {
    data object Loading : CreateGroupState

    data class Form(
        val name: String,
        val destination: Place?,
        /** 내비에서 읽어 온 목적지면 true (프리셋이면 false) */
        val fromNavi: Boolean,
        val maxMembers: Int,
        val loadingNavi: Boolean = false,
        val creating: Boolean = false,
        /** 페어링이 없어 만들 수 없음 */
        val notPaired: Boolean = false,
        val error: String? = null,
        /** T29 주행 중 — 입력 비활성 */
        val isDriving: Boolean = false,
    ) : CreateGroupState {
        val canCreate: Boolean get() = name.isNotBlank() && destination != null && !creating && !notPaired && !isDriving
    }

    data class Active(
        val session: GroupSession,
        val members: List<MemberDto> = emptyList(),
        /** 초대 만료까지 남은 초. 방장이 아니면 null */
        val inviteRemainingS: Long? = null,
        val reissuing: Boolean = false,
        val busy: Boolean = false,
        val error: String? = null,
        /** 최근 그룹 이벤트 음성 문구(최대 5개, 최신 먼저) — T26 전까지 화면 표시만 */
        val recentSpeech: List<String> = emptyList(),
        /** T29 주행 중 — 종료/나가기 비활성 */
        val isDriving: Boolean = false,
    ) : CreateGroupState {
        val inviteExpired: Boolean get() = inviteRemainingS != null && inviteRemainingS <= 0
    }
}

/**
 * 그룹 생성·대기 화면 (T17, docs/03 §2.1). 정차 중 사용(주행 중 UI 제한은 T29).
 * 이미 그룹이 있으면(방장이든 GROUP_JOINED 로 들어온 멤버든) 바로 [CreateGroupState.Active] 로 간다.
 */
@HiltViewModel
class CreateGroupViewModel @Inject constructor(
    private val groups: GroupRepository,
    private val navi: NaviAdapter,
    private val tokenStore: TokenStore,
    private val sessionManager: MqttSessionManager,
    driving: app.donghaeng.car.core.common.DrivingStateMonitor? = null,
) : ViewModel() {

    private val _state = MutableStateFlow<CreateGroupState>(CreateGroupState.Loading)
    val state: StateFlow<CreateGroupState> = _state.asStateFlow()

    /**
     * 마지막으로 받은 주행 여부(A008). 상태 객체를 새로 만들 때(세션 변화 → Form/Active) 이 값을 넣는다 — 주행 신호는 값이 바뀔 때만 오므로
     * 예전처럼 기본값 false 로 만들면 주행 중에 입력이 풀렸다(규칙 6). Loading 중에 온 replay 값도 여기 남는다.
     */
    @Volatile
    private var drivingNow = false

    // _state 선언 뒤에 둔다 — isDriving() 이 replay 값을 즉시 내면(Main.immediate) 생성 중에 update 가 불려 _state 가 null 이면 크래시(T30 실측)
    init {
        driving?.let { d ->
            viewModelScope.launch {
                d.isDriving().collect { v ->
                    drivingNow = v
                    update<CreateGroupState.Form> { it.copy(isDriving = v) }
                    update<CreateGroupState.Active> { it.copy(isDriving = v) }
                }
            }
        }
    }

    private var pollJob: Job? = null
    private var clock: () -> Long = { System.currentTimeMillis() }
    private var pollIntervalMs = 5_000L
    private var tickMs = 1_000L

    /**
     * RA-6: 멤버 변화 이벤트(합류·나감·방장 바뀜)로 인한 상세 다시 받기 요청 — 한꺼번에 온 이벤트는 [MEMBER_REFRESH_DEBOUNCE_MS] 동안 모아 한 번만.
     * 진행 중인 요청은 취소하지 않는다(세션 정리 도중 끊기지 않게) — 그 사이 온 요청은 끝난 뒤 한 번 더.
     */
    private val memberRefresh = Channel<Unit>(Channel.CONFLATED)

    init {
        viewModelScope.launch { observeSession() }
        viewModelScope.launch { collectEvents() }
        viewModelScope.launch { refreshMembersOnEvents() }
    }

    /** 테스트용: 시계·폴링 주기 교체 */
    internal fun configureForTest(clock: () -> Long, pollIntervalMs: Long, tickMs: Long) {
        this.clock = clock
        this.pollIntervalMs = pollIntervalMs
        this.tickMs = tickMs
    }

    private suspend fun observeSession() {
        groups.session.collect { session ->
            if (session == null) {
                pollJob?.cancel()
                if (_state.value !is CreateGroupState.Form) startForm()
            } else {
                val current = (_state.value as? CreateGroupState.Active)?.takeIf { it.session.groupId == session.groupId }
                _state.value = CreateGroupState.Active(
                    session = session, members = current?.members.orEmpty(), recentSpeech = current?.recentSpeech.orEmpty(), inviteRemainingS = remaining(session),
                    isDriving = drivingNow,
                )
                startPolling(session)
            }
        }
    }

    private suspend fun startForm() {
        val paired = tokenStore.state.value is TokenStoreState.Present || tokenStore.load() != null
        val nickname = (tokenStore.state.value as? TokenStoreState.Present)?.credentials?.nickname
        // "아빠 차와 같이 가기" — 받침에 따라 와/과(GroupNaming). 예전엔 "$nickname 와 같이 가기"(공백·조사 오류)
        _state.value = CreateGroupState.Form(
            name = GroupNaming.defaultName(nickname), destination = null, fromNavi = false, maxMembers = 10, notPaired = !paired, loadingNavi = true,
            isDriving = drivingNow,
        )
        loadNaviDestination()
    }

    /**
     * "내비 목적지 불러오기". 안내 중이 아니면 목적지를 비워 둔다(A123) — 예전엔 시연용 첫 프리셋(경포해변)을 자동으로 골라,
     * 바로 [같이 가기 시작]을 누르면 의도하지 않은 목적지로 그룹이 생기고 멤버에게 그 목적지 안내가 갔다. 이미 고른 목적지는 그대로 둔다.
     */
    fun loadNaviDestination() {
        viewModelScope.launch {
            update<CreateGroupState.Form> { it.copy(loadingNavi = true, error = null) }
            val dest = runCatching { navi.currentDestination() }.onFailure { DhLog.w("navi currentDestination 실패", it) }.getOrNull()
            update<CreateGroupState.Form> { form ->
                if (dest != null) form.copy(destination = dest, fromNavi = true, loadingNavi = false)
                else form.copy(loadingNavi = false)
            }
            DhLog.i("navi destination ${if (dest != null) "loaded" else "none → 사용자가 고른다"}")
        }
    }

    fun setName(name: String) = update<CreateGroupState.Form> { it.copy(name = name.take(30), error = null) }

    fun pickPreset(place: Place) = update<CreateGroupState.Form> { it.copy(destination = place, fromNavi = false, error = null) }

    fun setMaxMembers(n: Int) = update<CreateGroupState.Form> { it.copy(maxMembers = n.coerceIn(2, 10)) }

    fun create() {
        val form = _state.value as? CreateGroupState.Form ?: return
        val destination = form.destination ?: return
        if (!form.canCreate || drivingNow) return
        update<CreateGroupState.Form> { it.copy(creating = true, error = null) }
        viewModelScope.launch {
            when (val result = groups.create(form.name.trim(), destination, form.maxMembers)) {
                is ApiResult.Success -> Unit // 세션 Flow 가 Active 로 바꾼다
                is ApiResult.Failure -> update<CreateGroupState.Form> { it.copy(creating = false, error = failureText(result)) }
            }
        }
    }

    /** 초대 재발급 — 주행 중이면 무시(규칙 6, A123 — 화면 버튼은 E 단계에서 함께 막는다) */
    fun reissueInvite() {
        if (drivingNow) {
            DhLog.w("invite reissue ignored while driving")
            return
        }
        viewModelScope.launch {
            update<CreateGroupState.Active> { it.copy(reissuing = true, error = null) }
            val result = groups.reissueInvite()
            update<CreateGroupState.Active> { it.copy(reissuing = false, error = (result as? ApiResult.Failure)?.let(::failureText)) }
        }
    }

    /** 방장: 그룹 종료 / 멤버: 나가기 */
    fun endOrLeave() {
        val active = _state.value as? CreateGroupState.Active ?: return
        if (drivingNow) return
        viewModelScope.launch {
            update<CreateGroupState.Active> { it.copy(busy = true, error = null) }
            val result = if (active.session.isLeader) groups.end() else groups.leave()
            if (result is ApiResult.Failure) update<CreateGroupState.Active> { it.copy(busy = false, error = failureText(result)) }
        }
    }

    fun refreshMembers() {
        val active = _state.value as? CreateGroupState.Active ?: return
        viewModelScope.launch { fetchMembers(active.session.groupId) }
    }

    private fun startPolling(session: GroupSession) {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            fetchMembers(session.groupId)
            var sinceFetch = 0L
            while (true) {
                delay(tickMs)
                sinceFetch += tickMs
                update<CreateGroupState.Active> { it.copy(inviteRemainingS = remaining(it.session)) }
                if (sinceFetch >= pollIntervalMs) {
                    sinceFetch = 0
                    fetchMembers(session.groupId)
                }
            }
        }
    }

    private suspend fun fetchMembers(groupId: String) {
        when (val result = groups.detail(groupId)) {
            is ApiResult.Success -> {
                val detail = result.value
                val me = sessionManager.myVehicleId
                // A000: 서버는 끝난 그룹에도 200 + status ENDED 를 준다 — 끝났거나 내 차가 멤버가 아니면 유령 세션이다
                if (detail.status == "ENDED" || (me != null && detail.members.none { it.vehicleId == me })) {
                    DhLog.i("group detail status=${detail.status} member=${me != null && detail.members.any { it.vehicleId == me }} → clearing local session")
                    // RA-5: 확인과 삭제 사이 새 그룹이 적용될 수 있다 — 세션 관리자가 잠금 안에서 groupId 를 다시 본다
                    sessionManager.onGroupLeftLocally(groupId)
                    return
                }
                update<CreateGroupState.Active> { it.copy(members = detail.members, error = null) }
            }
            is ApiResult.HttpFailure -> {
                // 그룹이 끝났거나 내가 빠졌으면(404/409) 로컬 세션 정리 → Form 으로
                if (result.status == 404 || result.status == 409) {
                    DhLog.i("group detail ${result.status} → clearing local session")
                    sessionManager.onGroupLeftLocally(groupId)
                } else {
                    update<CreateGroupState.Active> { it.copy(error = failureText(result)) }
                }
            }
            is ApiResult.Failure -> update<CreateGroupState.Active> { it.copy(error = failureText(result)) }
        }
    }

    private suspend fun collectEvents() {
        // RA-6: 화면을 열 때 받은 replay(최대 20개)는 '지난 일' — 최근 소식에는 넣되 멤버 상세는 다시 받지 않는다(첫 상세는 startPolling 이 받는다).
        // 구독 시점의 replay 개수만큼 건너뛴다(그 사이 새 이벤트가 끼면 하나 더 받을 수 있으나 5초 폴링이 어차피 맞춘다)
        var replayed = sessionManager.groupEvents.replayCache.size
        sessionManager.groupEvents.collect { event ->
            // 내 차가 대상이 아닌 이벤트(뒤차의 급제동 — "앞쪽 ○○ 차가 급제동")의 문구는 최근 소식에 넣지 않는다(EventAudience, docs/03 §5.4).
            // A118: 내 차가 출처인 소식("DemoCar 배터리가 9% 남았어요")도 빼 대시보드 '지난 소식'·패널과 같은 기준으로(남의 일처럼 3인칭으로 보였다)
            val me = sessionManager.myVehicleId
            val forMe = EventAudience.isForMe(event, me, sessionManager.groupState.value?.members) && (me == null || event.sourceVehicleId != me)
            val speech = event.speech?.takeIf { forMe }
            update<CreateGroupState.Active> { it.copy(recentSpeech = (listOfNotNull(speech) + it.recentSpeech).take(5)) }
            val fromReplay = replayed > 0
            if (fromReplay) replayed--
            if (!fromReplay && (event.eventType == GroupEventType.MEMBER_JOINED || event.eventType == GroupEventType.MEMBER_LEFT || event.eventType == GroupEventType.LEADER_CHANGED)) {
                memberRefresh.trySend(Unit)
            }
        }
    }

    /** RA-6: 멤버 변화 이벤트 묶음마다 상세 한 번(디바운스). 진행 중 요청은 끝까지 */
    private suspend fun refreshMembersOnEvents() {
        for (unit in memberRefresh) {
            delay(MEMBER_REFRESH_DEBOUNCE_MS)
            memberRefresh.tryReceive()   // 기다리는 사이 온 요청은 이번 한 번에 포함
            (_state.value as? CreateGroupState.Active)?.let { fetchMembers(it.session.groupId) }
        }
    }

    private fun remaining(session: GroupSession): Long? =
        session.invite?.let { ((it.expiresAtMs - clock()) / 1000).coerceAtLeast(0) }

    private inline fun <reified S : CreateGroupState> update(transform: (S) -> CreateGroupState) {
        _state.update { current -> if (current is S) transform(current) else current }
    }

    private fun failureText(failure: ApiResult.Failure): String = when (failure) {
        // T33: Authenticator 가 갱신·재시도까지 했는데 401. 갱신이 거부돼 자격 증명이 지워졌을 때만 재페어링 안내 —
        // 갱신이 네트워크·5xx 로 실패했거나 백오프(Throttled) 중이면 자격 증명은 남아 있으니 잠시 뒤 다시 시도하면 된다
        is ApiResult.HttpFailure.Unauthorized ->
            if (credentialsRevoked()) "차량 연결이 만료됐어요. 폰에서 다시 연결해 주세요" else "연결을 확인하고 잠시 후 다시 시도해 주세요"
        is ApiResult.HttpFailure -> failure.error?.message ?: "요청에 실패했어요 (HTTP ${failure.status})"
        is ApiResult.NetworkFailure -> "서버에 연결할 수 없어요"
        is ApiResult.UnexpectedFailure -> "예상치 못한 오류가 났어요 (${failure.cause.javaClass.simpleName})"
    }

    /** 서버가 끊어 자격 증명이 지워졌다(refresh 거부·TOKEN_REVOKED) */
    private fun credentialsRevoked(): Boolean =
        tokenStore.state.value == TokenStoreState.Empty && TokenClearReason.fromName(tokenStore.lastClear.value?.reason)?.needsRepairFromPhone == true

    override fun onCleared() {
        pollJob?.cancel()
        super.onCleared()
    }

    companion object {
        /** Hilt 없이 테스트에서 디스패처를 바꿔 끼울 때 */
        val DEFAULT_DISPATCHER: CoroutineDispatcher = Dispatchers.Main

        /** 멤버 변화 이벤트를 모으는 시간(RA-6) */
        const val MEMBER_REFRESH_DEBOUNCE_MS = 500L
    }
}
