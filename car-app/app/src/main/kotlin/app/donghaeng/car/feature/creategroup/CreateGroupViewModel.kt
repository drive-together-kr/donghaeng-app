package app.donghaeng.car.feature.creategroup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.donghaeng.car.adapter.navi.NaviAdapter
import app.donghaeng.car.core.common.DhLog
import app.donghaeng.car.core.common.TokenStore
import app.donghaeng.car.core.common.TokenStoreState
import app.donghaeng.car.core.network.ApiResult
import app.donghaeng.car.domain.group.GroupRepository
import app.donghaeng.car.domain.session.GroupSession
import app.donghaeng.car.domain.session.MqttSessionManager
import app.donghaeng.protocol.GroupEventType
import app.donghaeng.protocol.Place
import app.donghaeng.protocol.rest.MemberDto
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

    // _state 선언 뒤에 둔다 — isDriving() 이 replay 값을 즉시 내면(Main.immediate) 생성 중에 update 가 불려 _state 가 null 이면 크래시(T30 실측)
    init {
        driving?.let { d -> viewModelScope.launch { d.isDriving().collect { v -> update<CreateGroupState.Form> { it.copy(isDriving = v) }; update<CreateGroupState.Active> { it.copy(isDriving = v) } } } }
    }

    private var pollJob: Job? = null
    private var clock: () -> Long = { System.currentTimeMillis() }
    private var pollIntervalMs = 5_000L
    private var tickMs = 1_000L

    init {
        viewModelScope.launch { observeSession() }
        viewModelScope.launch { collectEvents() }
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
                val current = _state.value as? CreateGroupState.Active
                _state.value = CreateGroupState.Active(session = session, members = current?.members.orEmpty(), recentSpeech = current?.recentSpeech.orEmpty(), inviteRemainingS = remaining(session))
                startPolling(session)
            }
        }
    }

    private suspend fun startForm() {
        val paired = tokenStore.state.value is TokenStoreState.Present || tokenStore.load() != null
        val nickname = (tokenStore.state.value as? TokenStoreState.Present)?.credentials?.nickname
        val defaultName = if (nickname.isNullOrBlank()) "같이 가기" else "$nickname 와 같이 가기"
        _state.value = CreateGroupState.Form(name = defaultName, destination = null, fromNavi = false, maxMembers = 10, notPaired = !paired, loadingNavi = true)
        loadNaviDestination()
    }

    /** "내비 목적지 불러오기" — 안내 중이 아니면 null → 프리셋 첫 항목을 추천 */
    fun loadNaviDestination() {
        viewModelScope.launch {
            update<CreateGroupState.Form> { it.copy(loadingNavi = true, error = null) }
            val dest = runCatching { navi.currentDestination() }.onFailure { DhLog.w("navi currentDestination 실패", it) }.getOrNull()
            update<CreateGroupState.Form> { form ->
                if (dest != null) form.copy(destination = dest, fromNavi = true, loadingNavi = false)
                else form.copy(destination = form.destination ?: DESTINATION_PRESETS.first(), fromNavi = false, loadingNavi = false)
            }
            DhLog.i("navi destination ${if (dest != null) "loaded" else "none → preset"}")
        }
    }

    fun setName(name: String) = update<CreateGroupState.Form> { it.copy(name = name.take(30), error = null) }

    fun pickPreset(place: Place) = update<CreateGroupState.Form> { it.copy(destination = place, fromNavi = false, error = null) }

    fun setMaxMembers(n: Int) = update<CreateGroupState.Form> { it.copy(maxMembers = n.coerceIn(2, 10)) }

    fun create() {
        val form = _state.value as? CreateGroupState.Form ?: return
        val destination = form.destination ?: return
        if (!form.canCreate) return
        update<CreateGroupState.Form> { it.copy(creating = true, error = null) }
        viewModelScope.launch {
            when (val result = groups.create(form.name.trim(), destination, form.maxMembers)) {
                is ApiResult.Success -> Unit // 세션 Flow 가 Active 로 바꾼다
                is ApiResult.Failure -> update<CreateGroupState.Form> { it.copy(creating = false, error = failureText(result)) }
            }
        }
    }

    fun reissueInvite() {
        viewModelScope.launch {
            update<CreateGroupState.Active> { it.copy(reissuing = true, error = null) }
            val result = groups.reissueInvite()
            update<CreateGroupState.Active> { it.copy(reissuing = false, error = (result as? ApiResult.Failure)?.let(::failureText)) }
        }
    }

    /** 방장: 그룹 종료 / 멤버: 나가기 */
    fun endOrLeave() {
        val active = _state.value as? CreateGroupState.Active ?: return
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
            is ApiResult.Success -> update<CreateGroupState.Active> { it.copy(members = result.value.members, error = null) }
            is ApiResult.HttpFailure -> {
                // 그룹이 끝났거나 내가 빠졌으면(404/409) 로컬 세션 정리 → Form 으로
                if (result.status == 404 || result.status == 409) {
                    DhLog.i("group detail ${result.status} → clearing local session")
                    sessionManager.onGroupLeftLocally()
                } else {
                    update<CreateGroupState.Active> { it.copy(error = failureText(result)) }
                }
            }
            is ApiResult.Failure -> update<CreateGroupState.Active> { it.copy(error = failureText(result)) }
        }
    }

    private suspend fun collectEvents() {
        sessionManager.groupEvents.collect { event ->
            val speech = event.speech
            update<CreateGroupState.Active> { it.copy(recentSpeech = (listOfNotNull(speech) + it.recentSpeech).take(5)) }
            if (event.eventType == GroupEventType.MEMBER_JOINED || event.eventType == GroupEventType.MEMBER_LEFT || event.eventType == GroupEventType.LEADER_CHANGED) {
                (_state.value as? CreateGroupState.Active)?.let { fetchMembers(it.session.groupId) }
            }
        }
    }

    private fun remaining(session: GroupSession): Long? =
        session.invite?.let { ((it.expiresAtMs - clock()) / 1000).coerceAtLeast(0) }

    private inline fun <reified S : CreateGroupState> update(transform: (S) -> CreateGroupState) {
        _state.update { current -> if (current is S) transform(current) else current }
    }

    private fun failureText(failure: ApiResult.Failure): String = when (failure) {
        // T33: Authenticator 가 갱신·재시도까지 했는데 401 → refresh 도 거부된 상태
        is ApiResult.HttpFailure.Unauthorized -> "차량 연결이 만료됐어요. 폰에서 다시 연결해 주세요"
        is ApiResult.HttpFailure ->failure.error?.message ?: "요청에 실패했어요 (HTTP ${failure.status})"
        is ApiResult.NetworkFailure -> "서버에 연결할 수 없어요"
        is ApiResult.UnexpectedFailure -> "예상치 못한 오류가 났어요 (${failure.cause.javaClass.simpleName})"
    }

    override fun onCleared() {
        pollJob?.cancel()
        super.onCleared()
    }

    companion object {
        /** Hilt 없이 테스트에서 디스패처를 바꿔 끼울 때 */
        val DEFAULT_DISPATCHER: CoroutineDispatcher = Dispatchers.Main
    }
}
