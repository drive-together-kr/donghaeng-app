package app.donghaeng.car.core.network.mqtt

import java.net.URI

/** 브로커 접속 대상 — 스킴 해석 결과 */
data class BrokerEndpoint(val host: String, val port: Int, val tls: Boolean)

/**
 * `DH_MQTT_PUBLIC_URI`/서버 응답 `mqtt.uri` 해석 + TLS 강제 규칙 (docs/08 T16).
 *
 * - `tcp://` `mqtt://` → 평문, 기본 포트 1883. `ssl://` `mqtts://` `tls://` → TLS, 기본 포트 8883.
 * - **서버 공개 주소가 https 면 평문 브로커를 허용하지 않는다**: `usesCleartextTraffic` 은 HTTP 에만 적용되고 MQTT 소켓은 막지 못하므로
 *   여기서 코드로 강제한다(클라우드 빌드에서 `tcp://` 가 섞여 들어와도 평문으로 나가지 않게).
 */
object MqttUriPolicy {

    class PolicyViolation(message: String) : IllegalArgumentException(message)

    fun resolve(mqttUri: String, publicBaseUrl: String): BrokerEndpoint {
        val uri = runCatching { URI(mqttUri.trim()) }.getOrElse { throw PolicyViolation("브로커 주소 형식 오류") }
        val scheme = uri.scheme?.lowercase() ?: throw PolicyViolation("브로커 주소에 스킴이 없어요")
        val host = uri.host ?: throw PolicyViolation("브로커 주소에 호스트가 없어요")
        val tls = when (scheme) {
            "ssl", "mqtts", "tls" -> true
            "tcp", "mqtt" -> false
            else -> throw PolicyViolation("지원하지 않는 브로커 스킴: $scheme")
        }
        val port = if (uri.port > 0) uri.port else if (tls) 8883 else 1883
        if (!tls && publicBaseUrl.trim().lowercase().startsWith("https://")) {
            throw PolicyViolation("서버가 https 이므로 브로커도 ssl:// 이어야 해요 (받은 주소: $scheme://)")
        }
        return BrokerEndpoint(host, port, tls)
    }
}
