package app.donghaeng.companion.demo.donghaeng

import app.donghaeng.companion.sdk.DongHaengException
import app.donghaeng.companion.sdk.GroupSnapshot
import app.donghaeng.protocol.GroupEventType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformWhile

/** [GroupObservation.observe] 가 내보내는 것 — 스냅샷, 다시 연결 대기, 끝 */
sealed interface ObserveEvent {
    data class Snapshot(val snapshot: GroupSnapshot) : ObserveEvent

    /** 일시 오류로 끊김 — [delayMs] 뒤 다시 구독한다. [attempt] 는 연속 실패 횟수(스냅샷을 받으면 0 으로) */
    data class Retrying(val attempt: Int, val delayMs: Long, val cause: Throwable) : ObserveEvent

    /** 더 관찰하지 않는다(흐름의 마지막 항목) */
    data class Stopped(val reason: StopReason, val cause: Throwable? = null) : ObserveEvent
}

enum class StopReason {
    /** 그룹 종료 — 스냅샷에 `GROUP_ENDED` 가 보였거나 모듈 흐름이 정상 종료(서버 상태 ENDED, sdk KDoc) */
    ENDED,

    /** 서버가 그룹을 숨김(404 `NOT_FOUND` — 내 차가 나갔거나 내보내짐) */
    NOT_MEMBER,

    /** 계정 인증 만료([DongHaengException.Unauthorized]) — 다시 로그인해야 한다 */
    UNAUTHORIZED,

    /** 다시 시도해도 나아지지 않을 오류(403·초기화 안 됨 등) */
    FAILED,
}

/**
 * 호스트 쪽 그룹 관찰(D021·D022). 동행 모듈의 `observeGroup` 은 오류를 흐름 예외로 끝내는 계약이라(sdk KDoc — 재구독은 호스트 몫),
 * 여기서 **일시 오류(네트워크·5xx·429·응답 해석 실패)는 백오프로 다시 구독**하고 그 사이 [ObserveEvent.Retrying] 을 내보내 화면이
 * '다시 연결 중'을 보이게 한다. 그룹 종료·멤버 아님·인증 만료·그 밖의 오류는 [ObserveEvent.Stopped] 하나를 내보내고 끝난다.
 *
 * 공식 앱에 이식할 때 그대로 가져갈 수 있는 순수 로직이다(안드로이드 의존 없음, 단위 테스트 `GroupObservationTest`).
 */
object GroupObservation {

    /** 첫 재시도 대기. 이후 두 배씩, [MAX_BACKOFF_MS] 까지 */
    const val BASE_BACKOFF_MS = 2_000L
    const val MAX_BACKOFF_MS = 30_000L

    /** 연속 실패 [attempt](1부터) 의 대기 시간: 2 s, 4 s, 8 s, 16 s, 30 s, 30 s … */
    fun backoffMs(attempt: Int): Long {
        val shift = (attempt - 1).coerceIn(0, 16)
        return (BASE_BACKOFF_MS shl shift).coerceAtMost(MAX_BACKOFF_MS)
    }

    /** 다시 구독하면 나아질 수 있는 서버·전송 오류 코드 */
    fun isTransientCode(code: String): Boolean =
        code == "NETWORK" ||
            code == "BAD_RESPONSE" ||
            code == "INTERNAL_ERROR" ||
            code == "HTTP_429" ||
            code == "HTTP_408" ||
            code.startsWith("HTTP_5")

    /** 관찰 오류 분류 — null 이면 일시 오류(다시 구독) */
    fun stopReasonFor(error: Throwable): StopReason? = when (error) {
        is DongHaengException.Unauthorized -> StopReason.UNAUTHORIZED
        is DongHaengException.Api -> when {
            error.code == "NOT_FOUND" || error.code == "GROUP_NOT_FOUND" -> StopReason.NOT_MEMBER
            isTransientCode(error.code) -> null
            else -> StopReason.FAILED
        }
        is DongHaengException -> StopReason.FAILED // NotInitialized·InvalidQr·OtherServer — 다시 해도 같다
        else -> null // 모르는 런타임 오류는 다시 구독(백오프 상한 30 s)
    }

    /**
     * @param source 구독할 때마다 새로 부른다(예: `{ DongHaeng.observeGroup(id) }`). 만드는 중 던져도 같은 분류를 거친다
     * @param backoff 연속 실패 횟수 → 대기(ms). 테스트에서 바꿔 끼운다
     */
    fun observe(
        source: () -> Flow<GroupSnapshot>,
        backoff: (Int) -> Long = ::backoffMs,
    ): Flow<ObserveEvent> = flow {
        var failures = 0
        while (true) {
            var failure: Throwable? = null
            flow { emitAll(source()) }
                // 종료 스냅샷까지 내보내고 멈춘다(모듈이 아직 폴링 중이어도)
                .transformWhile { snap ->
                    emit(snap)
                    !snap.isGroupEnded()
                }
                // 위쪽(모듈) 오류만 잡는다 — 아래쪽(화면) 예외는 그대로 전파(예외 투명성)
                .catch { e -> failure = e }
                .collect { snap ->
                    failures = 0
                    emit(ObserveEvent.Snapshot(snap))
                }
            val error = failure
            if (error == null) {
                emit(ObserveEvent.Stopped(StopReason.ENDED))
                return@flow
            }
            val stop = stopReasonFor(error)
            if (stop != null) {
                emit(ObserveEvent.Stopped(stop, error))
                return@flow
            }
            failures++
            val wait = backoff(failures)
            emit(ObserveEvent.Retrying(failures, wait, error))
            delay(wait)
        }
    }
}

/** 이 스냅샷에 그룹 종료 이벤트가 있는지(종료 뒤에는 이벤트가 더 쌓이지 않아 최근 20건 안에 남는다) */
fun GroupSnapshot.isGroupEnded(): Boolean = events.any { it.eventType == GroupEventType.GROUP_ENDED }
