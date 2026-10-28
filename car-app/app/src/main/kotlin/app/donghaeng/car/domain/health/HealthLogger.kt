package app.donghaeng.car.domain.health

import app.donghaeng.car.core.common.DhLog
import app.donghaeng.car.core.common.TextMasking
import app.donghaeng.car.core.common.TokenStore
import app.donghaeng.car.core.common.TokenStoreState
import app.donghaeng.car.core.network.mqtt.MqttGateway
import app.donghaeng.car.domain.pairing.TokenRefresher
import app.donghaeng.car.domain.session.MqttSessionManager
import app.donghaeng.car.domain.telemetry.TelemetryCollector
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 30초마다 상태 한 줄(`health …`)을 남긴다 (T33 — 30분 연속 실행 실측 `tools/demo/soak.py` 가 이 줄을 읽는다).
 *
 * ```
 * health mqtt=CONNECTED tr=tls rc=0 auth=0 in=1520 out=1830 tel=1790 telFail=0 group=grp_…7Q stateAgeMs=812 token=PRESENT expMin=1310 refresh=0/0 mqttRot=0 repair=0
 * ```
 * 값은 모두 카운터·상태 이름·마스킹 ID 뿐이다(좌표·닉네임·토큰 없음, 규칙 8). 링 버퍼·`files/dh_log.txt` 에도 남는다.
 * `mqttRot` = 회전한 토큰도 브로커가 거부해 이어진 MQTT 발 회전 횟수(백오프 단계), `repair` = 선제 갱신 거부로 '다시 연결 필요'(1) 인지.
 */
@Singleton
class HealthLogger @Inject constructor(
    private val gateway: MqttGateway,
    private val sessionManager: MqttSessionManager,
    private val telemetry: TelemetryCollector,
    private val tokenStore: TokenStore,
    private val refresher: TokenRefresher,
) {
    private val started = AtomicBoolean(false)

    fun start(dispatcher: CoroutineDispatcher = Dispatchers.Default) {
        if (!started.compareAndSet(false, true)) return
        val handler = CoroutineExceptionHandler { _, e -> DhLog.e("health logger failed", e) }
        CoroutineScope(SupervisorJob() + dispatcher + handler).launch {
            while (isActive) {
                delay(INTERVAL_MS)
                DhLog.i(line(System.currentTimeMillis()))
            }
        }
    }

    /** 지금 상태 한 줄 */
    fun line(nowMs: Long): String {
        val m = gateway.status.value
        val t = telemetry.state.value
        val session = sessionManager.session.value
        val stateAge = sessionManager.groupStateAt.value?.let { nowMs - it }
        val tokens = tokenStore.state.value
        val tokenText = when (tokens) {
            TokenStoreState.Loading -> "LOADING"
            TokenStoreState.Empty -> "EMPTY"
            is TokenStoreState.Present -> "PRESENT"
            is TokenStoreState.Unavailable -> "UNAVAILABLE"
        }
        val r = refresher.state.value
        val expMin = r.accessExpiresAtMs?.let { (it - nowMs) / 60_000 }
        return "health mqtt=${m.state} tr=${m.transport.label} rc=${m.reconnects} auth=${m.authFailures} in=${m.inboundCount} out=${m.outboundCount} " +
            "tel=${t.sentCount} telFail=${t.failedCount} group=${session?.groupId?.let(TextMasking::maskVehicleId) ?: "-"} " +
            "stateAgeMs=${stateAge ?: "-"} token=$tokenText expMin=${expMin ?: "-"} refresh=${r.refreshes}/${r.failures} " +
            "mqttRot=${r.mqttRotationStreak} repair=${if (r.repairNeededSinceMs != null) 1 else 0}"
    }

    companion object {
        const val INTERVAL_MS = 30_000L
    }
}
