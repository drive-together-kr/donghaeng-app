package app.donghaeng.car.core.network

import android.content.Context
import app.donghaeng.car.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 지금 쓰는 서버·브로커 주소 (T32). **유효값 = 사용자 지정(숨은 메뉴) ?: 빌드 기본값(BuildConfig)**.
 *
 * @param publicBaseUrl   REST·QR 의 서버 주소(끝 `/` 없음)
 * @param mqttPublicUri   표시용 MQTT 주소 — 사용자 지정 ?: 빌드 기본값. 실제 접속은 [brokerUriFor](사용자 지정 ?: 페어링 응답 `mqtt.uri`)
 * @param baseUrlOverridden 서버 주소가 사용자 지정인가
 * @param mqttUriOverride 사용자 지정 MQTT 주소. null = 페어링 응답 `mqtt.uri` 를 쓴다
 */
data class ServerAddresses(
    val publicBaseUrl: String,
    val mqttPublicUri: String,
    val baseUrlOverridden: Boolean = false,
    val mqttUriOverride: String? = null,
) {
    val anyOverride: Boolean get() = baseUrlOverridden || mqttUriOverride != null
}

/** 숨은 메뉴에서 저장한 값. null = 빌드 기본값을 쓴다 */
data class ServerOverrides(val publicBaseUrl: String? = null, val mqttUri: String? = null)

/** 무엇이 바뀌었나 — 서버 주소가 바뀌면 토큰을 지우고(다른 서버의 토큰은 무효), MQTT 주소만 바뀌면 다시 붙는다 */
data class ServerChange(val baseUrlChanged: Boolean, val mqttUriChanged: Boolean) {
    val any: Boolean get() = baseUrlChanged || mqttUriChanged
}

/** 사용자 지정 주소 저장소. 구현: [SharedPrefsServerOverrideStorage](기기) / [InMemoryServerOverrideStorage](테스트) */
interface ServerOverrideStorage {
    fun load(): ServerOverrides

    fun save(overrides: ServerOverrides)
}

class InMemoryServerOverrideStorage(private var value: ServerOverrides = ServerOverrides()) : ServerOverrideStorage {
    override fun load(): ServerOverrides = value

    override fun save(overrides: ServerOverrides) {
        value = overrides
    }
}

/**
 * SharedPreferences `server_config` (키 `baseUrl`, `mqttUri`). OkHttp 인터셉터가 요청마다 동기로 읽어야 해서 DataStore 가 아니라
 * SharedPreferences + 메모리 [ServerConfig.current] 를 쓴다. 비밀 값이 아니다(공개 서버 주소).
 */
class SharedPrefsServerOverrideStorage(context: Context) : ServerOverrideStorage {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun load(): ServerOverrides = runCatching {
        ServerOverrides(prefs.getString(KEY_BASE_URL, null), prefs.getString(KEY_MQTT_URI, null))
    }.getOrDefault(ServerOverrides())

    override fun save(overrides: ServerOverrides) {
        prefs.edit().apply {
            if (overrides.publicBaseUrl == null) remove(KEY_BASE_URL) else putString(KEY_BASE_URL, overrides.publicBaseUrl)
            if (overrides.mqttUri == null) remove(KEY_MQTT_URI) else putString(KEY_MQTT_URI, overrides.mqttUri)
        }.commit()
    }

    private companion object {
        const val FILE = "server_config"
        const val KEY_BASE_URL = "baseUrl"
        const val KEY_MQTT_URI = "mqttUri"
    }
}

/**
 * 서버·브로커 주소 (T32 — 빌드 기본값 + 숨은 메뉴 사용자 지정).
 *
 * - 빌드 기본값: BuildConfig ← Gradle(debug = `dhPublicBaseUrl`/`dhMqttPublicUri` 로컬, release = `dhCloudHost` 클라우드). [BUILD_DEFAULT_BASE_URL]
 * - 사용자 지정: 설정 화면 제목 5번 탭 → '서버 주소'([apply]). 빌드 기본값과 같은 값은 지정하지 않은 것으로 친다.
 * - REST: `NetworkModule` 의 [ServerHostInterceptor] 가 요청마다 [publicBaseUrl] 로 호스트를 바꾼다(Retrofit 은 다시 만들지 않는다).
 * - MQTT: 게이트웨이가 접속할 때 [brokerUriFor](사용자 지정 ?: 페어링 응답 `mqtt.uri`)와 [publicBaseUrl](https 판정·WSS 파생)을 읽는다.
 * - 차량 앱은 QR·딥링크 URL 을 직접 만들지 않는다(서버 응답을 그대로 표시, CLAUDE.md 규칙 12) — 서버를 바꿔도 QR 쪽은 할 일이 없다.
 */
class ServerConfig(
    private val defaultBaseUrl: String,
    private val defaultMqttUri: String,
    private val storage: ServerOverrideStorage,
) {
    private val _current = MutableStateFlow(resolve(defaultBaseUrl, defaultMqttUri, storage.load()))
    val current: StateFlow<ServerAddresses> = _current.asStateFlow()

    val publicBaseUrl: String get() = _current.value.publicBaseUrl

    /** 실제 브로커 주소 — 사용자 지정 MQTT 주소가 있으면 그것, 없으면 서버가 페어링 때 준 `mqtt.uri` */
    fun brokerUriFor(serverMqttUri: String): String = _current.value.mqttUriOverride ?: serverMqttUri

    /** 사용자 지정을 저장하고 무엇이 바뀌었는지 돌려준다. 값 검증은 [ServerAddressRules] 가 먼저 한다 */
    @Synchronized
    fun apply(overrides: ServerOverrides): ServerChange {
        val before = _current.value
        val normalized = normalize(defaultBaseUrl, defaultMqttUri, overrides)
        storage.save(normalized)
        val after = resolve(defaultBaseUrl, defaultMqttUri, normalized)
        _current.value = after
        return ServerChange(baseUrlChanged = before.publicBaseUrl != after.publicBaseUrl, mqttUriChanged = before.mqttUriOverride != after.mqttUriOverride)
    }

    /** 빌드 기본값으로 되돌린다 */
    fun reset(): ServerChange = apply(ServerOverrides())

    companion object {
        val BUILD_DEFAULT_BASE_URL: String = BuildConfig.DH_PUBLIC_BASE_URL.trimEnd('/')
        val BUILD_DEFAULT_MQTT_URI: String = BuildConfig.DH_MQTT_PUBLIC_URI

        /** 빌드 기본값만 담은 스냅샷(화면 상태 기본값) */
        val BUILD_DEFAULTS: ServerAddresses = ServerAddresses(BUILD_DEFAULT_BASE_URL, BUILD_DEFAULT_MQTT_URI)

        /** 우선순위: 사용자 지정 > 빌드 기본값. 빈 값·기본값과 같은 값은 지정하지 않은 것 */
        fun resolve(defaultBaseUrl: String, defaultMqttUri: String, overrides: ServerOverrides): ServerAddresses {
            val n = normalize(defaultBaseUrl, defaultMqttUri, overrides)
            return ServerAddresses(
                publicBaseUrl = n.publicBaseUrl ?: defaultBaseUrl.trimEnd('/'),
                mqttPublicUri = n.mqttUri ?: defaultMqttUri,
                baseUrlOverridden = n.publicBaseUrl != null,
                mqttUriOverride = n.mqttUri,
            )
        }

        private fun normalize(defaultBaseUrl: String, defaultMqttUri: String, overrides: ServerOverrides): ServerOverrides {
            val base = overrides.publicBaseUrl?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() && !it.equals(defaultBaseUrl.trimEnd('/'), ignoreCase = true) }
            val mqtt = overrides.mqttUri?.trim()?.takeIf { it.isNotEmpty() && !it.equals(defaultMqttUri, ignoreCase = true) }
            return ServerOverrides(base, mqtt)
        }
    }
}
