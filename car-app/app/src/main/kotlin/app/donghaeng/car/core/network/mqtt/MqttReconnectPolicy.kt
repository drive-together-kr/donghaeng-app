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
 * - 전송 폴백: 끊긴 뒤 [NO_SWITCH_MS](60 s) 동안의 실패는 '전송이 막힘'으로 세지 않는다(방금까지 그 전송으로 붙어 있었다).
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

    /** 이번 실패를 전송 폴백 판단에 셀 것인가 — 끊긴 직후 [NO_SWITCH_MS] 동안은 세지 않는다 */
    fun countsForTransport(now: Long, lostAtMs: Long?): Boolean = lostAtMs == null || now - lostAtMs !in 0..NO_SWITCH_MS

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

/**
 * 구독 재시도 간격 (A018) — SUBACK 거부(EMQX 인가 훅이 아직 안 됨 → no_match=deny 0x87)·실패 뒤 2 → 4 → 8 → 16 → 30 s.
 * 서버 ServerMqttClient 의 공유 구독 재시도와 같은 모양. [NOTIFY_AFTER] 번째 거부에서 세션 관리자에 알린다(소속 다시 맞추기).
 */
object SubscribeRetryPolicy {
    const val NOTIFY_AFTER = 3

    fun delayMs(attempt: Int): Long = (2_000L shl attempt.coerceIn(0, 4)).coerceAtMost(30_000L)
}
