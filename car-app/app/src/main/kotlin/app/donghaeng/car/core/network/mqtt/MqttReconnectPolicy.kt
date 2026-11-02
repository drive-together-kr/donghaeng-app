package app.donghaeng.car.core.network.mqtt

/**
 * 재연결 대기 시간 결정 (A-RT1, 2026-11-02 실측: 브로커(EMQX) 재시작 5 s 인데 차가 다시 붙기까지 138~273 s).
 * HiveMQ 타입 없이 순수 Kotlin — [HiveMqttGateway.onDisconnected] 가 부르고 단위 테스트가 고정한다.
 *
 * 실측 원인: 브로커가 막 다시 뜬 동안 ① 연결 거부·WSS 502(Caddy) 가 '전송이 막힘'으로 세어져 8883↔wss 를 오가며 백오프가 30 s 까지 자랐고,
 * ② EMQX 가 JWKS 를 아직 못 받아(갱신 주기 30 s) 멀쩡한 토큰을 NOT_AUTHORIZED 로 거부 → '토큰 문제'로 보고 15 s 씩 기다리며 토큰을 회전(회전 백오프 1→2→4분)했다.
 *
 * **재시작 창**: 붙어 있던 연결이 끊긴 뒤 [RESTART_WINDOW_MS](2분) 동안은 브로커 재시작으로 본다.
 * - 일반 실패: 백오프를 [RESTART_BACKOFF_MAX_MS](5 s)로 자른다.
 * - 인증 거부: 지금 토큰이 만료까지 [TOKEN_FRESH_MARGIN_MS](5분) 넘게 남았으면 토큰 문제가 아니다 → 갱신하지 않고 3 → 5 → 10 s 로 다시 시도한다.
 * - 전송 폴백: 끊긴 뒤 [NO_SWITCH_MS](60 s) 동안 **'브로커 앞단은 답했지만 브로커가 아직 준비 안 됨'**(연결 거부·TLS/WebSocket 뒤 CONNACK 없이 닫힘·WSS 5xx)
 *   실패는 '전송이 막힘'으로 세지 않는다(방금까지 그 전송으로 붙어 있었다). 연결 시간 초과·TLS 핸드셰이크 실패 등은 창 안에서도 센다(RA-8 —
 *   주행 중 8883 을 막는 망으로 바뀌면 예전처럼 실패 2회 뒤 WSS 로).
 * 창이 지나면(또는 처음부터 못 붙은 경우) 예전 규칙 그대로 — 인증 거부는 토큰 갱신을 기다리고(최소 15 s), 실패 2회면 전송을 바꾼다.
 */
object MqttReconnectPolicy {
    const val RESTART_WINDOW_MS = 120_000L
    const val RESTART_BACKOFF_MAX_MS = 5_000L
    const val NO_SWITCH_MS = 60_000L
    const val TOKEN_FRESH_MARGIN_MS = 5 * 60_000L

    /** 재시작 창 안 인증 거부 대기(마지막 값 반복) */
    val RESTART_AUTH_WAITS_MS: List<Long> = listOf(3_000L, 5_000L, 10_000L)

    /** 인증 거부 뒤 새 토큰을 기다리는 최대 시간(창 밖) — 갱신이 실패하면 이 뒤에 가진 토큰으로 다시 시도한다 */
    const val AUTH_WAIT_MS = 15_000L

    /** 전송을 바꾼 직후 다음 시도까지 */
    const val SWITCH_DELAY_MS = 1_000L

    data class Decision(
        val waitMs: Long,
        /** true = 인증 거부를 토큰 문제로 보고 갱신을 부른다([MqttGateway.authFailures]) */
        val refreshToken: Boolean,
    )

    /** 붙어 있다가 [lostAtMs] 에 끊긴 뒤 아직 재시작 창 안인가 */
    fun inRestartWindow(now: Long, lostAtMs: Long?): Boolean = lostAtMs != null && now - lostAtMs in 0..RESTART_WINDOW_MS

    /**
     * 이번 실패를 전송 폴백 판단에 셀 것인가 — 끊긴 직후 [NO_SWITCH_MS] 동안의 [brokerNotReady] 실패(브로커 재시작 신호)만 세지 않는다.
     * 시간 초과·TLS 실패처럼 '이 전송이 막혔을 수 있는' 실패는 창 안에서도 센다(RA-8).
     */
    fun countsForTransport(now: Long, lostAtMs: Long?, brokerNotReady: Boolean): Boolean =
        lostAtMs == null || now - lostAtMs !in 0..NO_SWITCH_MS || !brokerNotReady

    /**
     * @param attempts       HiveMQ 재연결 시도 수(백오프 단계)
     * @param authRejected   CONNACK 인증 거부인가
     * @param rejectedLatest 거부된 토큰이 지금 가진 최신 토큰인가(옛 토큰 거부면 백오프 뒤 새 토큰으로 바로)
     * @param tokenExpiresAtMs 지금 토큰 만료(epoch ms). 모르면 null(신선하다고 보지 않는다)
     * @param authRejectsInWindow 이번 재시작 창에서 이미 받은 인증 거부 수
     * @param quickSwitch    한 바퀴 안의 전송 전환 — 곧바로 시도
     * @param backoff        기본 백오프(1 → 30 s ±20%)
     */
    fun decide(
        now: Long,
        lostAtMs: Long?,
        attempts: Int,
        authRejected: Boolean,
        rejectedLatest: Boolean,
        tokenExpiresAtMs: Long?,
        authRejectsInWindow: Int,
        quickSwitch: Boolean,
        backoff: (Int) -> Long,
    ): Decision {
        val window = inRestartWindow(now, lostAtMs)
        if (authRejected && rejectedLatest) {
            val tokenFresh = tokenExpiresAtMs != null && tokenExpiresAtMs - now > TOKEN_FRESH_MARGIN_MS
            if (window && tokenFresh) {
                val i = authRejectsInWindow.coerceIn(0, RESTART_AUTH_WAITS_MS.size - 1)
                return Decision(RESTART_AUTH_WAITS_MS[i], refreshToken = false)
            }
            return Decision(maxOf(backoff(attempts), AUTH_WAIT_MS), refreshToken = true)
        }
        if (quickSwitch) return Decision(SWITCH_DELAY_MS, refreshToken = false)
        val base = backoff(attempts)
        return Decision(if (window) minOf(base, RESTART_BACKOFF_MAX_MS) else base, refreshToken = false)
    }
}

/** 끊김 하나(연결이 끊겼거나 연결 시도가 실패함) — [ReconnectTracker.onDisconnected] 의 입력. HiveMQ 원인은 게이트웨이가 분류해 넣는다 */
data class DisconnectEvent(
    val now: Long,
    /** 실패 분류(전송 폴백용) — `mqttFailureKind(cause)` */
    val failure: AttemptFailure,
    /** 브로커 앞단은 답했지만 브로커가 아직 준비 안 됨(연결 거부·CONNACK 없이 닫힘·WSS 5xx) — `mqttBrokerNotReady(cause)`(RA-8) */
    val brokerNotReady: Boolean,
    /** CONNACK 인증 거부 사유 코드, 아니면 null */
    val authCode: String?,
    /** 이번 시도에 실은 토큰 */
    val usedToken: String?,
    /** 지금 가진 최신 토큰 */
    val latestToken: String,
    val tokenExpiresAtMs: Long?,
    /** HiveMQ 재연결 시도 수(백오프 단계) */
    val attempts: Int,
)

/** [ReconnectTracker.onDisconnected] 의 결과 — 게이트웨이는 이것만 적용한다(전송 바꾸기·gate 타이머·authFailures 알림·로그) */
data class DisconnectPlan(
    /** 붙어 있던 연결이 끊겼다(시도 실패가 아니다) */
    val wasUp: Boolean,
    /** 다음 시도의 전송을 바꾼다 */
    val switch: MqttTransportFallback.Switch?,
    /** 다음 시도까지 기다릴 시간 */
    val waitMs: Long,
    /** 인증 거부를 토큰 문제로 보고 갱신을 부른다([MqttGateway.authFailures]) — 새 토큰이 오면 곧바로 다시 */
    val refreshForAuth: Boolean,
    /** 거부된 게 지금 가진 최신 토큰이다 */
    val rejectedLatest: Boolean,
    /** 브로커 재시작 창 안이다(로그 꼬리) */
    val restartWindow: Boolean,
)

/**
 * 접속(클라이언트) 하나의 재연결 판단 상태 (A-RT1·RA-7) — 붙어 있나·끊긴 시각·창 안 인증 거부 수·토큰 대기 여부를 갖고,
 * 끊김마다 [onDisconnected] 가 재시작 창·인증 거부 대기·전송 폴백 집계를 **한곳에서** 정한다. HiveMQ 타입 없이 순수 Kotlin(단위 테스트가 고정).
 * [HiveMqttGateway] 는 연결되면 [onConnected], 끊기면 [onDisconnected] 를 부르고 돌려받은 [DisconnectPlan] 만 적용한다.
 * 스레드: HiveMQ 이벤트 루프·게이트웨이 타이머에서 불리므로 동기화한다.
 */
class ReconnectTracker(private val fallback: MqttTransportFallback) {

    /** 지금 붙어 있나 — 끊김이 '연결이 끊김'인지 '연결 시도 실패'인지 가른다(T32) */
    @Volatile
    var up: Boolean = false
        private set

    /** 붙어 있다가 끊긴 시각(브로커 재시작 창). 다시 붙으면 null */
    @Volatile
    var lostAtMs: Long? = null
        private set

    /** 이번 재시작 창에서 토큰 갱신 없이 넘긴 인증 거부 수 */
    @Volatile
    var authRejectsInWindow: Int = 0
        private set

    /** 인증 거부 뒤 새 토큰을 기다리는 중(토큰이 오면 게이트웨이가 gate 를 바로 연다) */
    @Volatile
    var awaitingAuth: Boolean = false
        private set

    @Synchronized
    fun onConnected() {
        up = true
        awaitingAuth = false
        lostAtMs = null
        authRejectsInWindow = 0
    }

    /** 연결이 끊겼거나 시도가 실패했다 — 다음 시도 계획. [attempted] = 이번에 실제로 건 전송 */
    @Synchronized
    fun onDisconnected(e: DisconnectEvent, attempted: BrokerEndpoint, backoff: (Int) -> Long): DisconnectPlan {
        val wasUp = up
        up = false
        if (wasUp) {
            // A-RT1: 붙어 있던 연결이 끊겼다 — 여기서부터 '브로커 재시작 창'
            lostAtMs = e.now
            authRejectsInWindow = 0
        }
        // T32 전송 폴백: 붙어 있다가 끊긴 건 실패가 아니다. 끊긴 직후의 '브로커 준비 안 됨'(재시작·Caddy 502)은 전송이 막힌 게 아니다(RA-8: 그 밖은 센다)
        val switch = when {
            wasUp -> {
                fallback.onConnectionLost()
                null
            }
            !MqttReconnectPolicy.countsForTransport(e.now, lostAtMs, e.brokerNotReady) -> null
            else -> fallback.onAttemptFailed(e.failure, attempted)
        }
        // 거부된 게 '지금 가진 최신 토큰' 일 때만 갱신을 기다린다. 옛 토큰이 거부된 거면 새 토큰으로 바로(백오프 뒤) 다시 시도
        val rejectedLatest = e.authCode != null && (e.usedToken == null || e.usedToken == e.latestToken)
        val decision = MqttReconnectPolicy.decide(
            now = e.now, lostAtMs = lostAtMs, attempts = e.attempts, authRejected = e.authCode != null, rejectedLatest = rejectedLatest,
            tokenExpiresAtMs = e.tokenExpiresAtMs, authRejectsInWindow = authRejectsInWindow, quickSwitch = switch?.quick == true, backoff = backoff,
        )
        val refreshForAuth = rejectedLatest && decision.refreshToken
        if (rejectedLatest && !decision.refreshToken) authRejectsInWindow++
        awaitingAuth = refreshForAuth
        return DisconnectPlan(
            wasUp = wasUp, switch = switch, waitMs = decision.waitMs, refreshForAuth = refreshForAuth, rejectedLatest = rejectedLatest,
            restartWindow = MqttReconnectPolicy.inRestartWindow(e.now, lostAtMs),
        )
    }
}

/**
 * 구독 재시도 간격 (A018) — SUBACK 거부(EMQX 인가 훅이 아직 안 됨 → no_match=deny 0x87)·실패 뒤 2 → 4 → 8 → 16 → 30 s.
 * 서버 ServerMqttClient 의 공유 구독 재시도와 같은 모양. [NOTIFY_AFTER] 번째 거부에서 세션 관리자에 알린다(소속 다시 맞추기).
 */
object SubscribeRetryPolicy {
    const val NOTIFY_AFTER = 3

    fun delayMs(attempt: Int): Long = (2_000L shl attempt.coerceIn(0, 4)).coerceAtMost(30_000L)
}

/**
 * 구독 결과 처리 (A018·RA-7) — 순수 Kotlin. 게이트웨이가 SUBACK 결과(거부 여부)를 [onResult] 로 넘기면
 * 상태(거부 토픽)를 고치고, [SubscribeRetryPolicy.NOTIFY_AFTER] 번째 거부에서 한 번 알리고, 다시 걸 일을 [schedule] 에 맡긴다.
 * 예약한 재시도는 그 사이 접속(세대)이 바뀌었거나 토픽을 풀었으면([stillWanted] false) 보내지 않는다 — 새 접속은 resubscribe 가 처음부터 건다.
 */
class SubscribeRetrier(
    /** 지연 뒤 실행(게이트웨이: 타이머 스레드, 테스트: 가짜) */
    private val schedule: (delayMs: Long, task: () -> Unit) -> Unit,
    /** 그 재시도를 아직 보낼 만한가 — 같은 접속 세대가 살아 있고 토픽이 아직 구독 목록에 있다 */
    private val stillWanted: (topic: String, generation: Long) -> Boolean,
    /** 실제 SUBSCRIBE 를 다시 보낸다 */
    private val resend: (topic: String, attempt: Int, generation: Long) -> Unit,
    /** 거부 토픽 상태 — denied=true 면 넣고 false 면 뺀다 */
    private val markDenied: (topic: String, denied: Boolean) -> Unit,
    /** 연달아 거부됨 알림([MqttGateway.subscriptionDenied]) */
    private val notifyDenied: (topic: String) -> Unit,
    private val delayMs: (attempt: Int) -> Long = SubscribeRetryPolicy::delayMs,
) {
    /** @return 다시 걸기로 했으면 그 대기(ms), 받아들여졌으면 null */
    fun onResult(topic: String, attempt: Int, generation: Long, denied: Boolean): Long? {
        if (!denied) {
            markDenied(topic, false)
            return null
        }
        val wait = delayMs(attempt)
        markDenied(topic, true)
        if (attempt + 1 == SubscribeRetryPolicy.NOTIFY_AFTER) notifyDenied(topic)
        schedule(wait) { if (stillWanted(topic, generation)) resend(topic, attempt + 1, generation) }
        return wait
    }
}
