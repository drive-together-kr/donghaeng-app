package app.donghaeng.car.core.network.mqtt

/**
 * 연결 시도 실패의 종류 (T32 리뷰). [HiveMqttGateway] 가 HiveMQ 예외 사슬에서 가른다([mqttFailureKind]).
 */
enum class AttemptFailure {
    /** 브로커가 CONNACK 으로 답했다(인증 거부 등) — 전송은 살아 있다. 세지 않고 연속 실패도 지운다 */
    BROKER_ANSWERED,

    /**
     * 망 자체가 없다(DNS 실패 `UnknownHostException`, `ENETUNREACH` — 터널·시동 직후 망 미준비). 후보는 같은 호스트(클라우드 = sslip.io 하나)라
     * 전송을 바꿔도 같다 — 세지 않고 연속 실패도 지운다(망이 바뀌었으니 이전 실패는 다른 망의 것).
     */
    NETWORK_DOWN,

    /** 그 밖(연결 거부·시간 초과·TLS/WebSocket 핸드셰이크 실패·`EHOSTUNREACH` 등) — 이 전송이 막혔을 수 있다. 센다 */
    UNREACHABLE,
}

/**
 * MQTT 전송 폴백 결정 (T32) — HiveMQ 타입 없이 순수 Kotlin. [HiveMqttGateway] 가 연결 사건을 알려 주면 전환할지·무엇으로 할지 답한다.
 *
 * - 후보: 기본 전송(서버 응답 `mqtt.uri`, 클라우드 `ssl://…:8883`) + https 서버면 파생 `wss://{베이스 호스트}/mqtt`([MqttUriPolicy.candidates]).
 *   [remembered](지난번에 '다른 전송이 실패한 뒤' 붙은 전송)가 후보에 있으면 그것부터 시도한다.
 * - [current] 는 **다음 시도에 쓸 전송**이다. 게이트웨이가 클라이언트에 실제로 건 전송(`attempted`/`applied`)과 다르면 다음 시도 직전에 바꾼다.
 * - 전환 조건 ① [AttemptFailure.UNREACHABLE] 이 **연속 [failureThreshold] 회**([onAttemptFailed]) — 곧바로 전환.
 *   ② 첫 연결이 [FIRST_CONNECT_TIMEOUT_MS] 안에 안 됨([onFirstConnectTimeout]) — 아직 붙은 적·전환한 적이 없고, 브로커가 답한 적도(인증 거부 대기 등)
 *   망 없음 실패도 없을 때 한 번만 '다음 전송' 을 미리 정한다. 매달린 시도가 끝나면(실패 콜백) 또는 다음 시도 직전에 적용한다 — 클라이언트를 새로 만들지 않아
 *   같은 clientId 로 두 연결이 겹치지 않는다. 매달린 시도가 결국 브로커 응답·망 없음으로 끝나거나 늦게 붙으면 그 전환은 무른다.
 * - 다음 후보로 돌고, 마지막 다음은 처음(= 반대로 되돌아감). **마지막 연결 뒤 후보를 한 바퀴 다 돌았으면**([cycledWithoutSuccess]) 전송 문제가 아니라
 *   망·서버 문제일 가능성이 크다 — 계속 번갈아 시도하되 곧바로 시도(quick)하지 않고 정상 백오프를 쓰며, 전송 기억도 갱신하지 않는다.
 * - 연결됐다가 끊긴 것은 실패가 아니다([onConnectionLost]).
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

    /** 마지막 연결(또는 시작) 뒤 전송을 바꾼 횟수 — 후보 수 이상이면 한 바퀴를 다 돈 것 */
    private var switchesSinceConnected = 0

    private var everConnected = false

    /** 브로커가 CONNACK 으로 답한 적이 있다(전송이 살아 있다는 증거 — 첫 연결 시간 초과로 바꾸지 않는다) */
    private var brokerReached = false

    /** 망 없음 실패를 본 적이 있다(첫 연결이 늦은 건 전송 탓이 아니다) */
    private var networkDownSeen = false

    /** [onAttemptFailed] 결과 — [to] 로 바꿔 다음 시도 */
    data class Switch(
        val to: BrokerEndpoint,
        /** 이번 실패로 전환을 정했다. false = 첫 연결 시간 초과가 미리 정한 전환을 지금 적용만 한다(로그는 그때 남겼다) */
        val decidedNow: Boolean,
        /** 한 바퀴 안 — 새 전송을 백오프 없이 곧바로 시도한다. false = 한 바퀴를 돌았으니 정상 백오프 */
        val quick: Boolean,
    )

    /** 다음 시도에 쓸 전송 */
    val current: BrokerEndpoint
        @Synchronized get() = candidates[index]

    val hasAlternative: Boolean get() = candidates.size > 1

    /** 마지막 연결 뒤 후보를 한 바퀴 다 돌고도 못 붙었다 */
    val cycledWithoutSuccess: Boolean
        @Synchronized get() = switchesSinceConnected >= candidates.size

    /**
     * [applied] 로 연결됐다 — 실패 수를 지운다.
     * @return 기억할 전송. **다른 전송이 실제로 실패한 뒤 한 바퀴 안에 이 전송으로 붙었을 때만** 돌려준다(아니면 null — 기억을 바꾸지 않는다)
     */
    @Synchronized
    fun onConnected(applied: BrokerEndpoint): BrokerEndpoint? {
        val i = candidates.indexOf(applied)
        if (i >= 0 && i != index) {
            // 첫 연결 시간 초과가 미리 정한 전환을 적용하기 전에 원래 전송이 늦게 붙었다 — 그 전환은 무른다
            index = i
            undoSwitch()
        }
        val remember = switchesSinceConnected in 1 until candidates.size
        consecutiveFailures = 0
        switchesSinceConnected = 0
        everConnected = true
        return if (remember) applied else null
    }

    /** 연결돼 있다가 끊겼다(네트워크 변화·브로커 재시작) — 실패로 세지 않고 다시 센다 */
    @Synchronized
    fun onConnectionLost() {
        consecutiveFailures = 0
    }

    /**
     * [attempted] 전송으로 한 연결 시도가 [failure] 로 실패했다.
     * @return 다음 시도의 전송을 바꿔야 하면 [Switch], 그대로면 null
     */
    @Synchronized
    fun onAttemptFailed(failure: AttemptFailure, attempted: BrokerEndpoint): Switch? {
        val pending = candidates[index].takeIf { it != attempted }
        if (pending != null) {
            // 첫 연결 시간 초과가 이미 다음 전송을 정해 두었다 — 이 실패는 매달렸던 이전 전송의 것
            if (failure == AttemptFailure.UNREACHABLE) return Switch(pending, decidedNow = false, quick = !cycledWithoutSuccess)
            // 브로커가 답했거나 망이 없었다 — 이전 전송 탓이 아니었으니 전환을 무른다
            candidates.indexOf(attempted).takeIf { it >= 0 }?.let { index = it }
            undoSwitch()
        }
        when (failure) {
            AttemptFailure.BROKER_ANSWERED -> {
                brokerReached = true
                consecutiveFailures = 0
                return null
            }
            AttemptFailure.NETWORK_DOWN -> {
                networkDownSeen = true
                consecutiveFailures = 0
                return null
            }
            AttemptFailure.UNREACHABLE -> Unit
        }
        consecutiveFailures++
        if (consecutiveFailures < failureThreshold || !hasAlternative) return null
        val next = switchNext()
        return Switch(next, decidedNow = true, quick = !cycledWithoutSuccess)
    }

    /**
     * 첫 연결이 [FIRST_CONNECT_TIMEOUT_MS] 안에 안 됐다(소켓·TLS 연결이 매달린 경우 — 실패 콜백을 기다리지 않고 다음 전송을 정해 둔다).
     * 이미 붙은 적이 있거나, 전환한 적이 있거나, 브로커가 답한 적이 있거나(인증 거부 뒤 갱신 대기 — 전송은 멀쩡하다), 망 없음 실패를 봤으면 null.
     * @return 미리 정한 다음 전송(다음 시도부터 적용), 그대로면 null
     */
    @Synchronized
    fun onFirstConnectTimeout(): BrokerEndpoint? {
        if (everConnected || brokerReached || networkDownSeen || switches > 0 || !hasAlternative) return null
        return switchNext()
    }

    private fun switchNext(): BrokerEndpoint {
        index = (index + 1) % candidates.size
        consecutiveFailures = 0
        switches++
        switchesSinceConnected++
        return candidates[index]
    }

    private fun undoSwitch() {
        switches = (switches - 1).coerceAtLeast(0)
        switchesSinceConnected = (switchesSinceConnected - 1).coerceAtLeast(0)
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
 * 전송 기억 (T32) — **다른 전송이 실제로 실패한 뒤 붙은 전송**만 남긴다([MqttTransportFallback.onConnected]). 망 없음·인증 거부·서버 다운(한 바퀴 다 실패)으로는
 * 바뀌지 않는다. [serverKey] = 기본 브로커 호스트 — 서버가 바뀌면 다른 키라 옛 기억을 쓰지 않는다.
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
