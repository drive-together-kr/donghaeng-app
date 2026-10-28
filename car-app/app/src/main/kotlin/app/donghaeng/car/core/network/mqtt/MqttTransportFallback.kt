package app.donghaeng.car.core.network.mqtt

/**
 * MQTT 전송 폴백 결정 (T32) — HiveMQ 타입 없이 순수 Kotlin. [HiveMqttGateway] 가 연결 사건을 알려 주면 전환할지·무엇으로 할지 답한다.
 *
 * - 후보: 기본 전송(서버 응답 `mqtt.uri`, 클라우드 `ssl://…:8883`) + https 서버면 파생 `wss://{베이스 호스트}/mqtt`([MqttUriPolicy.candidates]).
 *   [remembered](지난번에 붙은 전송)가 후보에 있으면 그것부터 시도한다.
 * - 전환 조건: 브로커에 닿지 못한 연결 시도가 **연속 [failureThreshold] 회**([onAttemptFailed]) 이거나, 첫 연결이 [FIRST_CONNECT_TIMEOUT_MS] 안에 안 됨
 *   ([onFirstConnectTimeout] — 아직 한 번도 전환하지 않았을 때 한 번만). 다음 후보로 돌고, 마지막 다음은 처음(= 반대로 되돌아감).
 * - 브로커가 CONNACK 으로 답한 실패(인증 거부 등)는 전송이 살아 있다는 뜻이라 세지 않는다. 연결됐다가 끊긴 것도 실패가 아니다([onConnectionLost]).
 *
 * 스레드: HiveMQ 이벤트 루프·게이트웨이 타이머에서 불리므로 모든 메서드를 동기화한다.
 */
class MqttTransportFallback(
    candidates: List<BrokerEndpoint>,
    remembered: MqttTransport? = null,
    private val failureThreshold: Int = FAILURE_THRESHOLD,
) {
    init {
        require(candidates.isNotEmpty()) { "전송 후보가 비어 있다" }
    }

    /** 시도 순서(기억된 전송이 맨 앞) */
    val candidates: List<BrokerEndpoint> = order(candidates, remembered)

    private var index = 0

    /** 지금 전송으로 브로커에 닿지 못한 연속 시도 수 */
    var consecutiveFailures: Int = 0
        @Synchronized get
        private set

    /** 이 세션에서 전송을 바꾼 횟수 */
    var switches: Int = 0
        @Synchronized get
        private set

    private var everConnected = false

    val current: BrokerEndpoint
        @Synchronized get() = candidates[index]

    val hasAlternative: Boolean get() = candidates.size > 1

    /** 연결 성공 — 실패 수를 지우고, 기억할 전송(= 지금 전송)을 돌려준다 */
    @Synchronized
    fun onConnected(): BrokerEndpoint {
        consecutiveFailures = 0
        everConnected = true
        return candidates[index]
    }

    /** 연결돼 있다가 끊겼다(네트워크 변화·브로커 재시작) — 실패로 세지 않고 다시 센다 */
    @Synchronized
    fun onConnectionLost() {
        consecutiveFailures = 0
    }

    /**
     * 연결 시도가 실패했다.
     * @param reachedBroker 브로커가 CONNACK 으로 답했다(인증 거부 등) — 전송은 살아 있으므로 전환하지 않는다
     * @return 전환할 다음 대상, 그대로면 null
     */
    @Synchronized
    fun onAttemptFailed(reachedBroker: Boolean): BrokerEndpoint? {
        if (reachedBroker) {
            consecutiveFailures = 0
            return null
        }
        consecutiveFailures++
        if (consecutiveFailures < failureThreshold || !hasAlternative) return null
        return switchNext()
    }

    /**
     * 첫 연결이 [FIRST_CONNECT_TIMEOUT_MS] 안에 안 됐다(소켓 연결이 매달린 경우 — 실패 콜백을 기다리지 않는다).
     * 이미 붙은 적이 있거나 실패 횟수로 이미 바꿨으면 null(되돌려 흔들지 않는다).
     */
    @Synchronized
    fun onFirstConnectTimeout(): BrokerEndpoint? {
        if (everConnected || switches > 0 || !hasAlternative) return null
        return switchNext()
    }

    private fun switchNext(): BrokerEndpoint {
        index = (index + 1) % candidates.size
        consecutiveFailures = 0
        switches++
        return candidates[index]
    }

    companion object {
        const val FAILURE_THRESHOLD = 2
        const val FIRST_CONNECT_TIMEOUT_MS = 10_000L

        /** [remembered] 전송인 후보를 맨 앞으로(없으면 그대로) */
        fun order(candidates: List<BrokerEndpoint>, remembered: MqttTransport?): List<BrokerEndpoint> {
            val first = candidates.firstOrNull { it.transport == remembered } ?: return candidates
            return listOf(first) + (candidates - first)
        }
    }
}

/**
 * 마지막으로 붙은 전송 기억 (T32). [serverKey] = 기본 브로커 호스트 — 서버가 바뀌면 다른 키라 옛 기억을 쓰지 않는다.
 * 구현: [SharedPrefsMqttTransportMemory](기기) / [InMemoryMqttTransportMemory](테스트).
 */
interface MqttTransportMemory {
    fun recall(serverKey: String): MqttTransport?

    fun remember(serverKey: String, transport: MqttTransport)

    fun clear()
}

class InMemoryMqttTransportMemory : MqttTransportMemory {
    private val map = java.util.concurrent.ConcurrentHashMap<String, MqttTransport>()

    override fun recall(serverKey: String): MqttTransport? = map[serverKey]

    override fun remember(serverKey: String, transport: MqttTransport) {
        map[serverKey] = transport
    }

    override fun clear() = map.clear()
}

/** SharedPreferences `mqtt_transport` — 키 하나(`last` = "{serverKey}|{label}"). 서버 하나만 기억하면 충분하다 */
class SharedPrefsMqttTransportMemory(context: android.content.Context) : MqttTransportMemory {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, android.content.Context.MODE_PRIVATE)

    override fun recall(serverKey: String): MqttTransport? {
        val saved = runCatching { prefs.getString(KEY_LAST, null) }.getOrNull() ?: return null
        val key = saved.substringBeforeLast('|')
        return if (key == serverKey) MqttTransport.fromLabel(saved.substringAfterLast('|')) else null
    }

    override fun remember(serverKey: String, transport: MqttTransport) {
        val value = "$serverKey|${transport.label}"
        if (runCatching { prefs.getString(KEY_LAST, null) }.getOrNull() == value) return
        prefs.edit().putString(KEY_LAST, value).apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY_LAST).apply()
    }

    private companion object {
        const val FILE = "mqtt_transport"
        const val KEY_LAST = "last"
    }
}
