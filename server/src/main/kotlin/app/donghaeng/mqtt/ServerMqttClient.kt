package app.donghaeng.mqtt

import app.donghaeng.common.config.DhProperties
import app.donghaeng.common.id.Ids
import app.donghaeng.identity.jwt.JwtIssuer
import app.donghaeng.protocol.DhJson
import app.donghaeng.protocol.Envelope
import app.donghaeng.protocol.Topics
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.MqttGlobalPublishFilter
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient
import com.hivemq.client.mqtt.mqtt5.lifecycle.Mqtt5ClientReconnector
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5Publish
import jakarta.annotation.PreDestroy
import kotlinx.serialization.KSerializer
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import java.net.URI
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicLong

/**
 * 서버의 MQTT 출구/입구 — 발행은 [MqttPublisher], 수신은 [TopicRouter] 로.
 */
interface MqttPublisher {
    /** 연결돼 있으면 true */
    val connected: Boolean

    /**
     * 봉투에 싸서 발행한다(`vid` = 서버 클라이언트 ID, `seq` = 토픽별 단조 증가). 연결이 없으면 WARN 로그 후 false.
     * 비동기 — 브로커 응답(QoS 1 PUBACK)은 기다리지 않고, 실패는 로그로만 남긴다.
     */
    fun <T> publish(topic: String, type: String, data: T, serializer: KSerializer<T>, qos: Int = 1, retain: Boolean = false): Boolean

    /** retained 메시지 삭제(빈 payload, retained). 연결이 없으면 false */
    fun clearRetained(topic: String): Boolean
}

/**
 * HiveMQ MQTT 5 클라이언트로 EMQX 에 붙는다 (docs/06 §1·§3, docs/03 §4).
 *
 * - 자기 토큰: `JwtIssuer.serverToken(srv_…)` — username = clientId = `sub`(EMQX `verify_claims {sub = "${username}"}`), password = JWT.
 *   재연결 때마다 새 토큰을 넣는다(12h 만료 대비).
 * - 공유 구독 `$share/server/dh/v1/vehicle/+/#` (QoS 1) → [TopicRouter].
 * - 기동 시 EMQX 가 아직 JWKS 를 못 받았으면 인증이 거부된다 → 자동 재연결(지수 백오프 1~30s)로 복구한다. 기동 자체는 막지 않는다.
 * - `dh.mqtt.enabled=false` 면 아무것도 하지 않는다(브로커 없는 로컬 실행·웹 테스트).
 */
@Component
class ServerMqttClient(
    private val props: DhProperties,
    private val jwt: JwtIssuer,
    private val router: TopicRouter,
    private val clock: Clock,
) : MqttPublisher, SmartLifecycle {

    /** 서버 인스턴스의 MQTT 신원(`srv_` + ULID). 봉투 `vid` 로도 쓴다 */
    val clientId: String = "srv_" + Ids.ulid()

    private val running = AtomicBoolean(false)
    private val seqs = ConcurrentHashMap<String, AtomicLong>()
    private val retryExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "mqtt-subscribe-retry").apply { isDaemon = true } }

    @Volatile
    private var client: Mqtt5AsyncClient? = null

    override val connected: Boolean
        get() = client?.state?.isConnected == true

    // ---------------------------------------------------------------- lifecycle

    override fun start() {
        if (!props.mqtt.enabled) {
            log.warn("mqtt disabled (dh.mqtt.enabled=false) — cmd·그룹 이벤트는 발행되지 않습니다")
            return
        }
        if (!running.compareAndSet(false, true)) return
        val uri = URI(props.mqtt.uri)
        val useTls = uri.scheme.equals("ssl", true) || uri.scheme.equals("mqtts", true) || uri.scheme.equals("tls", true)
        val port = if (uri.port > 0) uri.port else if (useTls) 8883 else 1883
        val builder = MqttClient.builder()
            .useMqttVersion5()
            .identifier(clientId)
            .serverHost(uri.host)
            .serverPort(port)
            .automaticReconnect().initialDelay(1, TimeUnit.SECONDS).maxDelay(30, TimeUnit.SECONDS).applyAutomaticReconnect()
            .addConnectedListener {
                log.info("mqtt connected broker={}:{} clientId={}", uri.host, port, clientId)
                // 연결될 때마다 구독을 다시 건다(세션이 만료됐거나 첫 연결이 인증 거부였을 수 있다). 중복 구독은 무해하다
                client?.let(::subscribeShared)
            }
            .addDisconnectedListener { ctx ->
                // 재연결 때마다 새 토큰 — 만료(12h)·키 회전 대비. 인증 거부(JWKS 미수신 등)도 같은 경로로 재시도된다
                (ctx.reconnector as Mqtt5ClientReconnector).connectWith()
                    .simpleAuth().username(clientId).password(jwt.serverToken(clientId).toByteArray()).applySimpleAuth()
                    .cleanStart(false)
                    .sessionExpiryInterval(SESSION_EXPIRY_S)
                    .applyConnect()
                log.warn("mqtt disconnected source={} cause={} — reconnecting", ctx.source, ctx.cause.message)
            }
        val built = if (useTls) builder.sslWithDefaultConfig().buildAsync() else builder.buildAsync()
        client = built
        built.publishes(MqttGlobalPublishFilter.SUBSCRIBED) { publish: Mqtt5Publish ->
            try {
                router.route(publish.topic.toString(), publish.payloadAsBytes)
            } catch (e: Exception) {
                log.error("inbound route failed topic={} cause={}", publish.topic, e.toString())
            }
        }
        built.connectWith()
            .simpleAuth().username(clientId).password(jwt.serverToken(clientId).toByteArray()).applySimpleAuth()
            .cleanStart(false)
            .sessionExpiryInterval(SESSION_EXPIRY_S)
            .send()
            .whenComplete { ack, error ->
                if (error != null) {
                    log.warn("mqtt first connect failed cause={} — automatic reconnect continues", error.message)
                } else {
                    log.info("mqtt connack reason={} sessionPresent={}", ack.reasonCode, ack.isSessionPresent)
                }
            }
    }

    /**
     * 공유 구독. 실패(예외 또는 SUBACK 오류 코드 — 서버 재기동 직후 EMQX 의 HTTP 인가 커넥터가 아직 복구되지 않아 0x87 Not authorized 가 오는 경우를 실측)하면
     * 연결이 살아 있는 동안 2·4·…·30초 간격으로 다시 건다. 구독이 없으면 텔레메트리가 한 건도 안 들어오므로 반드시 성공할 때까지 반복한다.
     */
    private fun subscribeShared(c: Mqtt5AsyncClient, attempt: Int = 1) {
        c.subscribeWith()
            .topicFilter(Topics.SERVER_SHARED_VEHICLE_SUB)
            .qos(MqttQos.AT_LEAST_ONCE)
            .send()
            .whenComplete { ack, error ->
                val badCodes = ack?.reasonCodes?.filter { it.isError } ?: emptyList()
                if (error != null || badCodes.isNotEmpty()) {
                    val delayS = minOf(SUBSCRIBE_RETRY_MAX_S, 2L * attempt)
                    log.warn("mqtt subscribe failed filter={} attempt={} cause={} — {}s 뒤 재시도", Topics.SERVER_SHARED_VEHICLE_SUB, attempt, error?.message ?: badCodes.toString(), delayS)
                    retryExecutor.schedule({ if (running.get() && c.state.isConnected) subscribeShared(c, attempt + 1) }, delayS, TimeUnit.SECONDS)
                } else {
                    log.info("mqtt subscribed filter={} reason={} attempt={}", Topics.SERVER_SHARED_VEHICLE_SUB, ack.reasonCodes, attempt)
                }
            }
    }

    override fun stop() {
        if (!running.compareAndSet(true, false)) return
        val c = client ?: return
        try {
            c.disconnect().get(3, TimeUnit.SECONDS)
        } catch (e: Exception) {
            log.debug("mqtt disconnect on stop: {}", e.toString())
        }
        log.info("mqtt stopped clientId={}", clientId)
    }

    @PreDestroy
    fun shutdown() = stop()

    override fun isRunning(): Boolean = running.get()

    /** 웹 컨테이너 뒤에 시작(JWKS 가 먼저 열려야 EMQX 가 토큰을 검증할 수 있다) */
    override fun getPhase(): Int = Int.MAX_VALUE

    override fun isAutoStartup(): Boolean = true

    // ---------------------------------------------------------------- publish

    override fun <T> publish(topic: String, type: String, data: T, serializer: KSerializer<T>, qos: Int, retain: Boolean): Boolean {
        val c = client
        if (c == null || !c.state.isConnectedOrReconnect) {
            log.warn("mqtt publish skipped (not connected) topic={} type={}", topic, type)
            return false
        }
        val envelope = Envelope(type = type, vid = clientId, ts = clock.millis(), seq = seqs.computeIfAbsent(topic) { AtomicLong() }.incrementAndGet(), data = data)
        val payload = DhJson.encode(envelope, serializer).toByteArray(Charsets.UTF_8)
        c.publishWith()
            .topic(topic)
            .qos(MqttQos.fromCode(qos) ?: MqttQos.AT_LEAST_ONCE)
            .retain(retain)
            .payload(payload)
            .send()
            .whenComplete { _, error ->
                if (error != null) log.warn("mqtt publish failed topic={} type={} cause={}", topic, type, error.message)
                else if (log.isDebugEnabled) log.debug("mqtt published topic={} type={} bytes={}", topic, type, payload.size)
            }
        return true
    }

    override fun clearRetained(topic: String): Boolean {
        val c = client
        if (c == null || !c.state.isConnectedOrReconnect) {
            log.warn("mqtt clearRetained skipped (not connected) topic={}", topic)
            return false
        }
        c.publishWith()
            .topic(topic)
            .qos(MqttQos.AT_MOST_ONCE)
            .retain(true)
            .payload(ByteArray(0))
            .send()
            .whenComplete { _, error -> if (error != null) log.warn("mqtt clearRetained failed topic={} cause={}", topic, error.message) }
        return true
    }

    companion object {
        private val log = LoggerFactory.getLogger(ServerMqttClient::class.java)
        private const val SUBSCRIBE_RETRY_MAX_S = 30L
        private const val SESSION_EXPIRY_S = 300L
    }
}
