package app.donghaeng.car.core.network.mqtt

import java.net.URI

/**
 * MQTT 전송 방식 (T32). [label] 은 상태 표시·로그·전송 기억(SharedPreferences)에 쓰는 이름.
 *
 * - `tcp` 평문 TCP(1883) · `tls` TLS(8883, `ssl://`) · `ws` 평문 WebSocket · `wss` TLS WebSocket(443 `/mqtt`, Caddy → EMQX 8083)
 */
enum class MqttTransport(val label: String, val tls: Boolean, val webSocket: Boolean) {
    TCP("tcp", tls = false, webSocket = false),
    TLS("tls", tls = true, webSocket = false),
    WS("ws", tls = false, webSocket = true),
    WSS("wss", tls = true, webSocket = true),
    ;

    companion object {
        fun of(tls: Boolean, webSocket: Boolean): MqttTransport = entries.first { it.tls == tls && it.webSocket == webSocket }

        fun fromLabel(label: String?): MqttTransport? = entries.firstOrNull { it.label == label }
    }
}

/**
 * 브로커 접속 대상 — 스킴 해석 결과.
 *
 * @param webSocket MQTT over WebSocket(`ws://` · `wss://`) 인가
 * @param path      WebSocket 경로(기본 `/mqtt`). TCP 전송에서는 쓰지 않는다
 */
data class BrokerEndpoint(
    val host: String,
    val port: Int,
    val tls: Boolean,
    val webSocket: Boolean = false,
    val path: String = MqttUriPolicy.DEFAULT_WS_PATH,
) {
    val transport: MqttTransport get() = MqttTransport.of(tls, webSocket)

    /** 상태 표시·로그용 주소(토큰 없음) — `ssl://h:8883`, `wss://h:443/mqtt` */
    fun displayUri(): String = when (transport) {
        MqttTransport.TCP -> "tcp://$host:$port"
        MqttTransport.TLS -> "ssl://$host:$port"
        MqttTransport.WS -> "ws://$host:$port$path"
        MqttTransport.WSS -> "wss://$host:$port$path"
    }
}

/**
 * `DH_MQTT_PUBLIC_URI`/서버 응답 `mqtt.uri`/숨은 메뉴 MQTT 주소 해석 + TLS 강제 규칙 (docs/08 T16, T32).
 *
 * - `tcp://` `mqtt://` → 평문, 기본 포트 1883. `ssl://` `mqtts://` `tls://` → TLS, 기본 포트 8883.
 * - `ws://` → 평문 WebSocket(기본 포트 80), `wss://` → TLS WebSocket(기본 포트 443). 경로가 없으면 `/mqtt`.
 * - **서버 공개 주소가 https 면 평문 브로커(`tcp://` · `ws://`)를 허용하지 않는다**: 네트워크 보안 설정(평문 http 차단)은 HTTP 에만 적용되고
 *   MQTT(Netty) 소켓은 막지 못하므로 여기서 코드로 강제한다(클라우드 빌드에서 평문 주소가 섞여 들어와도 평문으로 나가지 않게).
 * - **WSS 폴백 주소는 서버 응답이 아니라 베이스 URL 에서 파생한다**([webSocketFallback], docs/03 머리말): `https://{host}[:{port}]` → `wss://{host}[:{port}]/mqtt`.
 */
object MqttUriPolicy {

    const val DEFAULT_WS_PATH = "/mqtt"

    class PolicyViolation(message: String) : IllegalArgumentException(message)

    fun resolve(mqttUri: String, publicBaseUrl: String): BrokerEndpoint {
        val uri = runCatching { URI(mqttUri.trim()) }.getOrElse { throw PolicyViolation("브로커 주소 형식 오류") }
        val scheme = uri.scheme?.lowercase() ?: throw PolicyViolation("브로커 주소에 스킴이 없어요")
        val host = uri.host ?: throw PolicyViolation("브로커 주소에 호스트가 없어요")
        val transport = when (scheme) {
            "ssl", "mqtts", "tls" -> MqttTransport.TLS
            "tcp", "mqtt" -> MqttTransport.TCP
            "wss" -> MqttTransport.WSS
            "ws" -> MqttTransport.WS
            else -> throw PolicyViolation("지원하지 않는 브로커 스킴: $scheme")
        }
        val defaultPort = when (transport) {
            MqttTransport.TCP -> 1883
            MqttTransport.TLS -> 8883
            MqttTransport.WS -> 80
            MqttTransport.WSS -> 443
        }
        val port = if (uri.port > 0) uri.port else defaultPort
        if (!transport.tls && isHttps(publicBaseUrl)) {
            throw PolicyViolation("서버가 https 이므로 브로커도 ssl:// 또는 wss:// 이어야 해요 (받은 주소: $scheme://)")
        }
        val path = if (transport.webSocket) uri.rawPath?.takeIf { it.isNotEmpty() } ?: DEFAULT_WS_PATH else DEFAULT_WS_PATH
        return BrokerEndpoint(host, port, transport.tls, transport.webSocket, path)
    }

    /**
     * 베이스 URL 이 https 면 같은 호스트·포트(기본 443)의 `wss://…/mqtt`, 아니면 null (T32, docs/03 머리말).
     * 클라우드는 Caddy 한 대가 443 에서 REST 와 `/mqtt`(→ EMQX WebSocket 8083)를 함께 받는다 — 8883 이 막힌 망(회사·공공 Wi-Fi)용 폴백.
     */
    fun webSocketFallback(publicBaseUrl: String): BrokerEndpoint? {
        val uri = runCatching { URI(publicBaseUrl.trim()) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() != "https") return null
        val host = uri.host ?: return null
        return BrokerEndpoint(host, if (uri.port > 0) uri.port else 443, tls = true, webSocket = true, path = DEFAULT_WS_PATH)
    }

    /**
     * 시도할 전송 후보 — 기본은 [primary] 하나, 베이스 URL 이 https 면 파생 WSS 를 뒤에 붙인다(같은 대상이면 하나만).
     * 순서(기억된 전송 우선)는 [MqttTransportFallback.order] 가 정한다.
     */
    fun candidates(primary: BrokerEndpoint, publicBaseUrl: String): List<BrokerEndpoint> =
        listOfNotNull(primary, webSocketFallback(publicBaseUrl)).distinct()

    private fun isHttps(publicBaseUrl: String): Boolean = publicBaseUrl.trim().lowercase().startsWith("https://")
}
