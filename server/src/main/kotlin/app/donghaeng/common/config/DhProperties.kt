package app.donghaeng.common.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * `dh.*` 설정값 (docs/03 머리말).
 *
 * - `dh.public-base-url` (`DH_PUBLIC_BASE_URL`): QR·딥링크·응답 URL 을 만들 때 쓰는 공개 base URL. 끝에 `/` 없음.
 * - `dh.mqtt.uri` (`DH_MQTT_URI`): 서버가 브로커에 붙을 때 쓰는 주소.
 * - `dh.mqtt.public-uri` (`DH_MQTT_PUBLIC_URI`): 차량 앱에 내려 주는 브로커 공개 주소.
 */
@ConfigurationProperties("dh")
data class DhProperties(
    val publicBaseUrl: String,
    val mqtt: Mqtt,
) {
    data class Mqtt(
        val uri: String,
        val publicUri: String,
    )
}
