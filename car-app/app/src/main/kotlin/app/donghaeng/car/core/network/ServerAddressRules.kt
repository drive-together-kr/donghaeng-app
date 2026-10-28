package app.donghaeng.car.core.network

import app.donghaeng.car.core.network.mqtt.MqttUriPolicy
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI

/**
 * 숨은 메뉴 '서버 주소' 입력 검증 (T32). 화면은 [Error] 를 문구로 바꿔 보여 준다.
 *
 * - 서버 주소: `http(s)://host[:port]` 만(경로·쿼리 없음 — API 경로가 `/v1/…` 절대 경로라 경로 접두는 쓰이지 않는다). 끝 `/` 와 기본 포트(http 80·https 443)는 지운다
 *   — `https://h:443` 과 `https://h` 를 다른 서버로 보면 토큰이 쓸데없이 지워진다(T32 리뷰). 같은 서버인지는 [sameOrigin](scheme·host·port)으로 본다.
 *   평문 `http://` 는 이 빌드의 네트워크 보안 설정이 허용하는 호스트만([cleartextHosts], BuildConfig.DH_CLEARTEXT_HOSTS — `*` = 전부).
 * - MQTT 주소: 비우면 페어링 응답 `mqtt.uri` 를 쓴다. 넣으면 [MqttUriPolicy.resolve] 규칙(tcp/ssl/ws/wss, https 서버면 평문 금지)을 그대로 적용한다.
 */
object ServerAddressRules {

    enum class Error {
        /** 비었거나 http(s):// 형식이 아님 */
        BASE_FORMAT,

        /** 경로·쿼리·조각이 붙음 */
        BASE_HAS_PATH,

        /** 이 빌드가 막는 평문 http 호스트 */
        BASE_CLEARTEXT_BLOCKED,

        /** MQTT 주소 형식·스킴 오류 */
        MQTT_FORMAT,

        /** https 서버인데 평문 브로커(tcp:// · ws://) */
        MQTT_PLAINTEXT,
    }

    sealed interface Result<out T> {
        data class Ok<T>(val value: T) : Result<T>
        data class Invalid(val error: Error) : Result<Nothing>
    }

    /** @return 정리된 서버 주소(`scheme://host[:port]`) */
    fun checkBaseUrl(input: String, cleartextHosts: String): Result<String> {
        val trimmed = input.trim().trimEnd('/')
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return Result.Invalid(Error.BASE_FORMAT)
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return Result.Invalid(Error.BASE_FORMAT)
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return Result.Invalid(Error.BASE_FORMAT)
        if (uri.rawUserInfo != null) return Result.Invalid(Error.BASE_FORMAT)
        if (!uri.rawPath.isNullOrEmpty() || uri.rawQuery != null || uri.rawFragment != null) return Result.Invalid(Error.BASE_HAS_PATH)
        if (scheme == "http" && !cleartextAllowed(host, cleartextHosts)) return Result.Invalid(Error.BASE_CLEARTEXT_BLOCKED)
        val defaultPort = if (scheme == "https") 443 else 80
        val port = if (uri.port > 0 && uri.port != defaultPort) ":${uri.port}" else ""
        return Result.Ok("$scheme://${host.lowercase()}$port")
    }

    /** @return null(비움 = 페어링 응답 사용) 또는 정리된 MQTT 주소 */
    fun checkMqttUri(input: String, publicBaseUrl: String): Result<String?> {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return Result.Ok(null)
        return try {
            MqttUriPolicy.resolve(trimmed, publicBaseUrl)
            Result.Ok(trimmed)
        } catch (e: MqttUriPolicy.PolicyViolation) {
            val plaintext = runCatching { URI(trimmed).scheme?.lowercase() }.getOrNull() in setOf("tcp", "mqtt", "ws")
            Result.Invalid(if (plaintext && publicBaseUrl.trim().lowercase().startsWith("https://")) Error.MQTT_PLAINTEXT else Error.MQTT_FORMAT)
        }
    }

    /** 다이얼로그 [저장] 결과 */
    sealed interface FormResult {
        data class Invalid(val baseError: Error?, val mqttError: Error?) : FormResult

        /** 지금 값과 같다 — 할 일 없음 */
        data object Unchanged : FormResult

        /** 저장할 값. [baseUrlChanges] 면 토큰이 지워진다(확인 창 문구가 다르다) */
        data class Ready(val overrides: ServerOverrides, val baseUrlChanges: Boolean) : FormResult
    }

    /**
     * 서버 주소·MQTT 주소 입력을 함께 검증하고, [current](지금 유효값)와 비교한다. MQTT 의 https 판정은 **새** 서버 주소 기준.
     */
    fun checkForm(baseInput: String, mqttInput: String, cleartextHosts: String, current: ServerAddresses): FormResult {
        val base = checkBaseUrl(baseInput, cleartextHosts)
        val baseValue = (base as? Result.Ok)?.value
        val mqtt = checkMqttUri(mqttInput, baseValue ?: current.publicBaseUrl)
        if (base is Result.Invalid || mqtt is Result.Invalid) {
            return FormResult.Invalid((base as? Result.Invalid)?.error, (mqtt as? Result.Invalid)?.error)
        }
        val mqttValue = (mqtt as Result.Ok).value
        val baseChanges = !sameOrigin(baseValue.orEmpty(), current.publicBaseUrl)
        val mqttChanges = mqttValue != current.mqttUriOverride
        if (!baseChanges && !mqttChanges) return FormResult.Unchanged
        return FormResult.Ready(ServerOverrides(baseValue, mqttValue), baseChanges)
    }

    /** 같은 서버인가 — scheme·host·port(기본 포트 채움) 비교. OkHttp `HttpUrl` 로 못 읽으면 문자열 비교(대소문자·끝 `/` 무시) */
    fun sameOrigin(a: String, b: String): Boolean {
        val x = a.trim().toHttpUrlOrNull()
        val y = b.trim().toHttpUrlOrNull()
        if (x == null || y == null) return a.trim().trimEnd('/').equals(b.trim().trimEnd('/'), ignoreCase = true)
        return x.scheme == y.scheme && x.host == y.host && x.port == y.port
    }

    fun cleartextAllowed(host: String, cleartextHosts: String): Boolean {
        val allowed = cleartextHosts.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        return "*" in allowed || host.lowercase() in allowed
    }
}
