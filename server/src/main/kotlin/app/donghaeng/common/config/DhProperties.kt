package app.donghaeng.common.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * `dh.*` 설정값 (docs/03 머리말, docs/06 §5).
 *
 * - `dh.public-base-url` (`DH_PUBLIC_BASE_URL`): QR·딥링크·응답 URL 을 만들 때 쓰는 공개 base URL. 끝에 `/` 없음.
 * - `dh.mqtt.uri` (`DH_MQTT_URI`): 서버가 브로커에 붙을 때 쓰는 주소.
 * - `dh.mqtt.public-uri` (`DH_MQTT_PUBLIC_URI`): 차량 앱에 내려 주는 브로커 공개 주소.
 * - `dh.jwt.private-key-pem` (`DH_JWT_PRIVATE_KEY_PEM`): RS256 서명용 RSA 개인키(PKCS#8 PEM, 줄바꿈은 `\n` 이스케이프 허용).
 *   비어 있으면 기동 시 개발용 키를 생성한다(WARN, 재시작마다 바뀜) — identity/jwt/JwtKeyProvider.
 * - `dh.car-id-salt` (`DH_CAR_ID_SALT`): CarInfo 차량 ID 를 해시할 때 섞는 salt (docs/04 vehicle.car_info_id_hash). 운영에서는 반드시 바꾼다.
 */
@ConfigurationProperties("dh")
class DhProperties(
    publicBaseUrl: String,
    val mqtt: Mqtt,
    val jwt: Jwt = Jwt(),
    carIdSalt: String? = null,
) {
    /** 끝의 `/` 는 제거해 `{base}/pair?c=…` 조합 시 `//` 가 생기지 않게 한다 (docs/03: 끝에 `/` 없음). */
    val publicBaseUrl: String = publicBaseUrl.trimEnd('/')

    /** 비어 있으면 개발용 기본 salt. */
    val carIdSalt: String = carIdSalt?.takeIf { it.isNotBlank() } ?: DEFAULT_CAR_ID_SALT

    data class Mqtt(
        val uri: String,
        val publicUri: String,
    )

    data class Jwt(
        val privateKeyPem: String = "",
    )

    companion object {
        const val DEFAULT_CAR_ID_SALT = "dev-salt-change-me"
    }
}
