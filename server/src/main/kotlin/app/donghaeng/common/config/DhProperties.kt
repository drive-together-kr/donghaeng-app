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
 * - `dh.internal-token` (`DH_INTERNAL_TOKEN`): EMQX 가 ACL 훅(`POST /internal/mqtt/acl`)을 부를 때 `X-Internal-Token` 헤더로 보내는 공유 비밀.
 *   비어 있으면 검사하지 않는다(로컬 개발). 클라우드에서는 반드시 설정한다.
 * - `dh.auth.dev-login-key` (`DH_DEV_LOGIN_KEY`): dev 간편 로그인(`POST /v1/auth/login`) 공유 키. 설정되면 요청 헤더 `X-Dev-Login-Key` 가 같아야 한다(아니면 403).
 *   비어 있으면 검사하지 않는다(로컬 개발·테스트). 공개 VM 에서 이메일만으로 아무 계정이나 로그인되지 않게 클라우드에서는 반드시 설정한다(T32).
 * - `dh.mqtt.enabled` (`DH_MQTT_ENABLED`): 서버의 브로커 접속을 끌 수 있다(테스트·브로커 없는 로컬 실행). 기본 true.
 * - `dh.presence.*`: 스냅샷 주기(2s)·오프라인/정차 판정(60s)·도착 반경(150 m)·Redis 상태 TTL(600s) — presence(T24).
 * - `dh.app-links.*` (`DH_APP_LINKS_PACKAGE`·`DH_APP_LINKS_SHA256`): Android App Links 확인 파일(`/.well-known/assetlinks.json`)에 넣을 폰 앱 패키지와
 *   릴리스 서명 인증서 SHA-256(공개 값 — 비밀 아님). 초대·등록 주소를 누르면 브라우저가 아니라 폰 앱이 바로 열린다.
 * - `dh.rate-limit.*`: 공개 경로 IP 당 분당 요청 수(로그인·디바이스 코드 30)·동승자 메시지 그룹당 최소 간격(2 s) — docs/03 §1·§2.3.
 */
@ConfigurationProperties("dh")
class DhProperties(
    publicBaseUrl: String,
    val mqtt: Mqtt,
    val jwt: Jwt = Jwt(),
    val presence: Presence = Presence(),
    val auth: Auth = Auth(),
    val rateLimit: RateLimit = RateLimit(),
    val appLinks: AppLinks = AppLinks(),
    carIdSalt: String? = null,
    internalToken: String? = null,
) {
    /** 비어 있으면 null — ACL 훅이 헤더 검사를 건너뛴다 */
    val internalToken: String? = internalToken?.takeIf { it.isNotBlank() }

    /** 끝의 `/` 는 제거해 `{base}/pair?c=…` 조합 시 `//` 가 생기지 않게 한다 (docs/03: 끝에 `/` 없음). */
    val publicBaseUrl: String = publicBaseUrl.trimEnd('/')

    /** 비어 있으면 개발용 기본 salt. */
    val carIdSalt: String = carIdSalt?.takeIf { it.isNotBlank() } ?: DEFAULT_CAR_ID_SALT

    data class Mqtt(
        val uri: String,
        val publicUri: String,
        val enabled: Boolean = true,
    )

    data class Jwt(
        val privateKeyPem: String = "",
    )

    /** dev 간편 로그인 보호 (T32) */
    class Auth(devLoginKey: String? = null) {
        /** 비어 있으면 null — `POST /v1/auth/login` 이 키를 검사하지 않는다 */
        val devLoginKey: String? = devLoginKey?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** Android App Links (카메라 없이 초대·등록 링크로 폰 앱 열기) */
    class AppLinks(
        packageName: String = DEFAULT_APP_LINKS_PACKAGE,
        sha256: String = DEFAULT_APP_LINKS_SHA256,
    ) {
        /** 비어 있으면 null — assetlinks.json 이 빈 목록, 안내 페이지에 '앱에서 열기' 없음 */
        val packageName: String? = packageName.trim().takeIf { it.isNotEmpty() }

        /** 쉼표로 여러 개(키 교체 기간). `AA:BB:…` 대문자 */
        val sha256: List<String> = sha256.split(',').map { it.trim().uppercase() }.filter { it.isNotEmpty() }
    }

    /** 빈도 제한 (C094·C106, docs/03 §1·§2.3) */
    data class RateLimit(
        /** `POST /v1/auth/login`·`POST /v1/device/code` — IP 당 분당 요청 수. 0 이하면 끈다 */
        val ipPerMinute: Int = 30,
        /** `POST /v1/groups/{id}/messages` — 같은 그룹의 동승자 메시지 최소 간격(ms). 0 이하면 끈다 */
        val passengerMessageIntervalMs: Long = 2_000,
    )

    /** presence(T24): 스냅샷 주기·멤버 상태 판정 기준 (docs/03 §5.3) */
    data class Presence(
        val snapshotIntervalMs: Long = 2_000,
        val snapshotInitialDelayMs: Long = 5_000,
        /** 마지막 위치가 이보다 오래되면 OFFLINE */
        val offlineAfterMs: Long = 60_000,
        /** presence 토픽 값이 없을 때 "이 시간 안에 텔레메트리가 왔으면 online" */
        val onlineByTelemetryMs: Long = 15_000,
        /** 속도 < 1 km/h 가 이보다 길면 STOPPED */
        val stoppedAfterMs: Long = 60_000,
        /** 목적지 반경(m) 안 + 거의 정지 → ARRIVED (docs/10 R4) */
        val arrivedRadiusM: Double = 150.0,
        /** `veh:{vid}:state`·`grp:{gid}:geo` TTL */
        val stateTtlSeconds: Long = 600,
    )

    companion object {
        const val DEFAULT_APP_LINKS_PACKAGE = "app.donghaeng.companion.demo"
        const val DEFAULT_APP_LINKS_SHA256 = "25:24:46:02:6A:78:9E:55:1B:77:8E:95:43:18:84:B4:8D:DB:28:6E:30:94:2F:F0:63:0A:EF:58:6D:10:82:AB"
        const val DEFAULT_CAR_ID_SALT = "dev-salt-change-me"
    }
}
