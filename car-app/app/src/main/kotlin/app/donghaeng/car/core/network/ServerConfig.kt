package app.donghaeng.car.core.network

import app.donghaeng.car.BuildConfig

/**
 * 서버·브로커 주소 (BuildConfig ← Gradle 속성 `dhPublicBaseUrl` / `dhMqttPublicUri`).
 *
 * - 에뮬레이터 기본값: `http://10.0.2.2:8080`, `tcp://10.0.2.2:1883`
 * - 클라우드 빌드: `-PdhPublicBaseUrl=https://<VM-IP>.sslip.io -PdhMqttPublicUri=ssl://<VM-IP>.sslip.io:8883`
 *
 * 차량 앱은 QR·딥링크 URL 을 직접 만들지 않는다(서버 응답을 그대로 표시, CLAUDE.md 규칙 12).
 *
 * - T12: `NetworkModule` 이 Retrofit base URL 로 `publicBaseUrl + "/"` 를 쓴다. 토큰 저장은 `EncryptedTokenStore`(security-crypto).
 * TODO(T16): MqttGateway(HiveMQ) 생성 시 `mqttPublicUri` 파싱(scheme tcp/ssl, host, port). 실제 접속 URI 는 서버 응답 `mqtt.uri` 우선.
 */
object ServerConfig {
    val publicBaseUrl: String = BuildConfig.DH_PUBLIC_BASE_URL.trimEnd('/')
    val mqttPublicUri: String = BuildConfig.DH_MQTT_PUBLIC_URI
}
