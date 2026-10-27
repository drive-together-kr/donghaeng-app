package app.donghaeng.car.feature.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.donghaeng.car.core.common.DhLog
import app.donghaeng.car.core.network.ApiResult
import app.donghaeng.car.domain.pairing.PairingRepository
import app.donghaeng.car.domain.pairing.VehicleIdUnavailableException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 오류 원인 — 문구는 strings.xml 에서 고른다. */
enum class PairingErrorReason { NETWORK, SERVER, VEHICLE_ID_UNAVAILABLE, TOO_MANY_CODES }

/**
 * 페어링 화면 상태 (docs/08 T12).
 * ```
 * Idle → Checking ─ 저장된 토큰 있음 → Connected
 *                 └ 없음 → RequestingCode → CodeIssued ─ interval 마다 폴링 ─ 200 → Connected
 *                                               ↑          428 계속 / 429 interval+2 / 410·404·로컬 만료 → 새 코드(최대 3회)
 *                                               └── Error(retryable) ← 네트워크·서버 오류 · 코드 3회 초과
 * ```
 * deviceCode 는 UI 상태에 넣지 않는다(화면·로그에 나올 이유가 없다).
 */
sealed interface PairingState {
    data object Idle : PairingState
    data object Checking : PairingState
    data object RequestingCode : PairingState
    data class CodeIssued(
        val userCode: String,
        val verifyUri: String,
        val expiresAtMillis: Long,
        val intervalSec: Int,
        /** 이번 화면에서 몇 번째 코드인지(1부터, 최대 [PairingViewModel.MAX_CODE_REQUESTS]) */
        val attempt: Int,
    ) : PairingState

    data class Connected(val vehicleId: String, val nickname: String) : PairingState
    data class Error(val reason: PairingErrorReason, val retryable: Boolean, val detail: String? = null) : PairingState
}

/** 한 번만 소비되는 이벤트 — 방금 페어링이 끝났으니 홈으로 돌아가라. */
sealed interface PairingEvent {
    data object Paired : PairingEvent
}

@HiltViewModel
class PairingViewModel internal constructor(
    private val repository: PairingRepository,
    /** 로컬 만료 판정용 시계(ms). 테스트는 가상 시간을 넣는다. */
    private val clock: () -> Long,
    autoStart: Boolean,
) : ViewModel() {

    @Inject
    constructor(repository: PairingRepository) : this(repository, { System.currentTimeMillis() }, autoStart = true)

    private val _state = MutableStateFlow<PairingState>(PairingState.Idle)
    val state: StateFlow<PairingState> = _state.asStateFlow()

    private val _events = Channel<PairingEvent>(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: Flow<PairingEvent> = _events.receiveAsFlow()

    private var job: Job? = null

    init {
        if (autoStart) start()
    }

    /** Idle 에서만 시작한다(화면 재진입 시 중복 실행 방지). */
    fun start() {
        if (_state.value != PairingState.Idle) return
        job = viewModelScope.launch { checkStoredThenPair() }
    }

    /** 오류 화면의 "다시 시도" — 코드 발급 횟수를 처음부터 센다. */
    fun retry() {
        job?.cancel()
        job = viewModelScope.launch { requestCodeAndPoll(firstAttempt = 1) }
    }

    /** 이미 연결된 상태에서 "연결 해제 후 다시 연결" — 저장 토큰을 지우고 새 코드를 받는다(반복 시연용). */
    fun disconnectAndRepair() {
        job?.cancel()
        job = viewModelScope.launch {
            repository.disconnect()
            requestCodeAndPoll(firstAttempt = 1)
        }
    }

    private suspend fun checkStoredThenPair() {
        setState(PairingState.Checking)
        val stored = repository.storedCredentials()
        if (stored != null) {
            setState(PairingState.Connected(stored.vehicleId, stored.nickname))
            return
        }
        requestCodeAndPoll(firstAttempt = 1)
    }

    private suspend fun requestCodeAndPoll(firstAttempt: Int) {
        var attempt = firstAttempt
        while (true) {
            if (attempt > MAX_CODE_REQUESTS) {
                setState(PairingState.Error(PairingErrorReason.TOO_MANY_CODES, retryable = true, detail = "codes=${attempt - 1}"))
                return
            }
            setState(PairingState.RequestingCode)
            val issued = when (val r = repository.requestCode()) {
                is ApiResult.Success -> r.value
                is ApiResult.Failure -> {
                    setState(r.toErrorState())
                    return
                }
            }
            val expiresAt = clock() + issued.expiresIn * 1000L
            var intervalSec = issued.interval.coerceAtLeast(MIN_INTERVAL_SEC)
            setState(PairingState.CodeIssued(issued.userCode, issued.verifyUri, expiresAt, intervalSec, attempt), extra = "expiresIn=${issued.expiresIn} interval=$intervalSec attempt=$attempt")

            // 폴링 루프 — 끝나는 경우: 연결(return) / 오류(return) / 코드 갱신(break → 다음 attempt)
            while (true) {
                delay(intervalSec * 1000L)
                if (clock() >= expiresAt) {
                    DhLog.i("pairing poll=LOCAL_EXPIRED attempt=$attempt → 새 코드 요청")
                    break
                }
                when (val r = repository.pollToken(issued.deviceCode)) {
                    is ApiResult.Success -> {
                        val creds = repository.saveCredentials(r.value)
                        setState(PairingState.Connected(creds.vehicleId, creds.nickname), extra = "vehicleId=${creds.vehicleId}")
                        _events.trySend(PairingEvent.Paired)
                        return
                    }
                    is ApiResult.HttpFailure.Pending -> Unit                                   // 428: 계속
                    is ApiResult.HttpFailure.SlowDown -> {                                     // 429: 간격 +2초(누적)
                        intervalSec += SLOW_DOWN_STEP_SEC
                        DhLog.d("pairing poll=SLOW_DOWN interval=$intervalSec")
                        val current = _state.value
                        if (current is PairingState.CodeIssued) _state.value = current.copy(intervalSec = intervalSec)
                    }
                    is ApiResult.HttpFailure.Expired, is ApiResult.HttpFailure.NotFound -> {    // 410 / 404: 새 코드
                        DhLog.i("pairing poll=${r.summary} attempt=$attempt → 새 코드 요청")
                        break
                    }
                    is ApiResult.Failure -> {
                        setState(r.toErrorState())
                        return
                    }
                }
            }
            attempt++
        }
    }

    private fun ApiResult.Failure.toErrorState(): PairingState.Error = when (this) {
        is ApiResult.NetworkFailure -> PairingState.Error(PairingErrorReason.NETWORK, retryable = true, detail = summary)
        is ApiResult.UnexpectedFailure ->
            if (cause is VehicleIdUnavailableException) PairingState.Error(PairingErrorReason.VEHICLE_ID_UNAVAILABLE, retryable = true, detail = summary)
            else PairingState.Error(PairingErrorReason.SERVER, retryable = true, detail = summary)
        is ApiResult.HttpFailure -> PairingState.Error(PairingErrorReason.SERVER, retryable = true, detail = summary)
    }

    /** 상태 전이만 로그에 남긴다 — userCode·deviceCode·토큰은 절대 넣지 않는다(규칙 8). */
    private fun setState(next: PairingState, extra: String = "") {
        _state.value = next
        val name = when (next) {
            PairingState.Idle -> "IDLE"
            PairingState.Checking -> "CHECKING"
            PairingState.RequestingCode -> "REQUESTING_CODE"
            is PairingState.CodeIssued -> "CODE_ISSUED"
            is PairingState.Connected -> "CONNECTED"
            is PairingState.Error -> "ERROR reason=${next.reason} retryable=${next.retryable} detail=${next.detail ?: "-"}"
        }
        DhLog.i("pairing state=$name${if (extra.isEmpty()) "" else " $extra"}")
    }

    companion object {
        /** 코드가 만료되어 다시 받는 횟수 상한(이번 시도 묶음 안에서) */
        const val MAX_CODE_REQUESTS = 3
        const val SLOW_DOWN_STEP_SEC = 2
        const val MIN_INTERVAL_SEC = 1
    }
}
