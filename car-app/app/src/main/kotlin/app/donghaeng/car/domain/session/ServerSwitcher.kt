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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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
 * - **앱 수명 범위에서 돈다**(T32 리뷰): 부른 쪽(설정 ViewModel)이 화면을 벗어나 취소돼도 주소 교체·토큰 삭제·재연결이 반쯤 멈추지 않는다
 *   (`await` 만 취소된다). SharedPreferences `commit()`·MQTT 끊기(최대 3 s 대기)도 메인 스레드가 아니라 [dispatcher](기본 IO)에서.
 */
@Singleton
class ServerSwitcher(
    private val serverConfig: ServerConfig,
    private val tokenStore: TokenStore,
    private val gateway: MqttGateway,
    private val transportMemory: MqttTransportMemory,
    private val clockSync: ClockSync,
    dispatcher: CoroutineDispatcher,
) {
    @Inject
    constructor(
        serverConfig: ServerConfig,
        tokenStore: TokenStore,
        gateway: MqttGateway,
        transportMemory: MqttTransportMemory,
        clockSync: ClockSync,
    ) : this(serverConfig, tokenStore, gateway, transportMemory, clockSync, Dispatchers.IO)

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutex = Mutex()

    /** 사용자 지정을 저장하고 뒤처리(토큰 삭제·재연결)까지 마친 뒤 무엇이 바뀌었는지 돌려준다. 부른 쪽이 취소돼도 작업은 끝까지 간다 */
    suspend fun apply(overrides: ServerOverrides): ServerChange = scope.async { mutex.withLock { applyLocked(overrides) } }.await()

    private suspend fun applyLocked(overrides: ServerOverrides): ServerChange {
        val change = serverConfig.apply(overrides)
        if (!change.any) return change
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
        return change
    }

    /** 빌드 기본값으로 되돌린다 */
    suspend fun reset(): ServerChange = apply(ServerOverrides())
}
