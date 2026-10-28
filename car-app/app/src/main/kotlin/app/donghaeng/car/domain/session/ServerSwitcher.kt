package app.donghaeng.car.domain.session

import app.donghaeng.car.core.common.DhLog
import app.donghaeng.car.core.common.TokenClearReason
import app.donghaeng.car.core.common.TokenStore
import app.donghaeng.car.core.common.TokenStoreState
import app.donghaeng.car.core.network.ServerChange
import app.donghaeng.car.core.network.ServerConfig
import app.donghaeng.car.core.network.ServerOverrides
import app.donghaeng.car.core.network.mqtt.MqttGateway
import app.donghaeng.car.core.network.mqtt.MqttTransportMemory
import app.donghaeng.car.domain.time.ClockSync
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 숨은 메뉴 '서버 주소' 저장·복원 (T32). 값 검증은 화면이 [app.donghaeng.car.core.network.ServerAddressRules] 로 먼저 한다.
 *
 * - **서버 주소가 바뀌면** 페어링 자격 증명을 지운다(`TokenStore.clear(SERVER_CHANGED)`) — 차량 JWT 는 그 서버의 키로 서명돼 다른 서버에서는 무효다.
 *   토큰 Empty 를 본 `MqttSessionManager` 가 MQTT 를 끊고 그룹 세션을 정리한다(기존 경로). 다시 페어링하면 새 서버 응답으로 붙는다. 시계도 새 서버로 다시 맞춘다.
 * - **MQTT 주소만 바뀌면** 같은 자격 증명으로 MQTT 를 끊었다가 다시 붙는다(토큰은 그대로).
 * - 어느 쪽이든 기억해 둔 MQTT 전송(tls/wss)은 지운다.
 * - QR·초대 URL 은 서버가 `DH_PUBLIC_BASE_URL` 로 만들므로 앱이 바꿀 것은 없다.
 */
@Singleton
class ServerSwitcher @Inject constructor(
    private val serverConfig: ServerConfig,
    private val tokenStore: TokenStore,
    private val gateway: MqttGateway,
    private val transportMemory: MqttTransportMemory,
    private val clockSync: ClockSync,
) {
    private val mutex = Mutex()

    suspend fun apply(overrides: ServerOverrides): ServerChange = mutex.withLock {
        val change = serverConfig.apply(overrides)
        if (!change.any) return@withLock change
        transportMemory.clear()
        val now = serverConfig.current.value
        DhLog.i(
            "server address changed base=${now.publicBaseUrl}${if (now.baseUrlOverridden) " (override)" else " (build)"} " +
                "mqtt=${now.mqttUriOverride ?: "server-provided"} baseChanged=${change.baseUrlChanged} mqttChanged=${change.mqttUriChanged}",
        )
        if (change.baseUrlChanged) {
            if (tokenStore.state.value !is TokenStoreState.Empty) {
                tokenStore.clear(TokenClearReason.SERVER_CHANGED)
            }
            clockSync.resync()
        } else {
            val creds = (tokenStore.state.value as? TokenStoreState.Present)?.credentials
            gateway.disconnect()
            if (creds != null) gateway.connect(creds)
        }
        change
    }

    /** 빌드 기본값으로 되돌린다 */
    suspend fun reset(): ServerChange = apply(ServerOverrides())
}
