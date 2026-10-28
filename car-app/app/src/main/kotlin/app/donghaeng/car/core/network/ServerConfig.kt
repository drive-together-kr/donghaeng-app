package app.donghaeng.car.core.network

import android.content.Context
import app.donghaeng.car.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 지금 쓰는 서버·브로커 주소 (T32). 서버 주소 **유효값 = 사용자 지정(숨은 메뉴) ?: 빌드 기본값(BuildConfig)**.
 * MQTT 는 빌드 기본값이 없다 — **사용자 지정 ?: 페어링 응답 `mqtt.uri`**([ServerConfig.brokerUriFor]). BuildConfig `DH_MQTT_PUBLIC_URI` 는 접속에 쓰이지 않는다.
 *
 * @param publicBaseUrl   REST·QR 의 서버 주소(끝 `/` 없음)
 * @param baseUrlOverridden 서버 주소가 사용자 지정인가
 * @param mqttUriOverride 사용자 지정 MQTT 주소. null = 페어링 응답 `mqtt.uri` 를 쓴다
 */
data class ServerAddresses(
    val publicBaseUrl: String,
    val baseUrlOverridden: Boolean = false,
    val mqttUriOverride: String? = null,
) {
    val anyOverride: Boolean get() = baseUrlOverridden || mqttUriOverride != null
}

/**
 * 홈·진단 'MQTT 주소' 행 (T32 리뷰) — 실제로 쓰는 브로커 주소와 출처. 빌드 기본 MQTT 주소는 보이지 않는다(접속에 안 쓰여 실제와 달라 보였다).
 * 실제 전송(ssl → wss 폴백 등)은 진단 MQTT 카드의 '브로커'·'전송' 행이 보인다.
 */
data class MqttAddressDisplay(val uri: String?, val source: Source) {
    enum class Source {
        /** 숨은 메뉴 사용자 지정 */
        OVERRIDE,

        /** 페어링 때 서버가 준 `mqtt.uri` */
        SERVER,

        /** 페어링 전 — 서버가 아직 알려 주지 않았다 */
        UNPAIRED,
    }

    companion object {
        val UNPAIRED = MqttAddressDisplay(null, Source.UNPAIRED)

        /** @param pairedMqttUri 지금 자격 증명의 `mqtt.uri`(페어링 전이면 null) */
        fun of(server: ServerAddresses, pairedMqttUri: String?): MqttAddressDisplay = when {
            server.mqttUriOverride != null -> MqttAddressDisplay(server.mqttUriOverride, Source.OVERRIDE)
            pairedMqttUri != null -> MqttAddressDisplay(pairedMqttUri, Source.SERVER)
            else -> UNPAIRED
        }
    }
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
 * - 빌드 기본값: BuildConfig ← Gradle(debug = `dhPublicBaseUrl` 로컬, release = `dhCloudHost` 클라우드). [BUILD_DEFAULT_BASE_URL]
 * - 사용자 지정: 설정 화면 제목 5번 탭 → '서버 주소'([apply]). 서버 주소는 빌드 기본값과 같은 origin(scheme·host·port, 기본 포트 생략 포함)이면
 *   지정하지 않은 것으로 친다. MQTT 주소는 **빈 값만** '지정 안 함'(= 페어링 응답) — 빌드 기본 MQTT 주소와 같아도 사용자 지정으로 저장한다
 *   (서버 응답 `mqtt.uri` 가 틀렸을 때 바로 그 값으로 고치는 메뉴라서, T32 리뷰).
 * - REST: `NetworkModule` 의 [ServerHostInterceptor] 가 요청마다 [publicBaseUrl] 로 호스트를 바꾼다(Retrofit 은 다시 만들지 않는다).
 * - MQTT: 게이트웨이가 접속할 때 [brokerUriFor](사용자 지정 ?: 페어링 응답 `mqtt.uri`)와 [publicBaseUrl](https 판정·WSS 파생)을 읽는다.
 * - 차량 앱은 QR·딥링크 URL 을 직접 만들지 않는다(서버 응답을 그대로 표시, CLAUDE.md 규칙 12) — 서버를 바꿔도 QR 쪽은 할 일이 없다.
 */
class ServerConfig(
    private val defaultBaseUrl: String,
    private val storage: ServerOverrideStorage,
) {
    private val _current = MutableStateFlow(resolve(defaultBaseUrl, storage.load()))
    val current: StateFlow<ServerAddresses> = _current.asStateFlow()

    val publicBaseUrl: String get() = _current.value.publicBaseUrl

    /** 실제 브로커 주소 — 사용자 지정 MQTT 주소가 있으면 그것, 없으면 서버가 페어링 때 준 `mqtt.uri` */
    fun brokerUriFor(serverMqttUri: String): String = _current.value.mqttUriOverride ?: serverMqttUri

    /** 사용자 지정을 저장하고 무엇이 바뀌었는지 돌려준다. 값 검증은 [ServerAddressRules] 가 먼저 한다 */
    @Synchronized
    fun apply(overrides: ServerOverrides): ServerChange {
        val before = _current.value
        val normalized = normalize(defaultBaseUrl, overrides)
        storage.save(normalized)
        val after = resolve(defaultBaseUrl, normalized)
        _current.value = after
        return ServerChange(
            baseUrlChanged = !ServerAddressRules.sameOrigin(before.publicBaseUrl, after.publicBaseUrl),
            mqttUriChanged = before.mqttUriOverride != after.mqttUriOverride,
        )
    }

    /** 빌드 기본값으로 되돌린다 */
    fun reset(): ServerChange = apply(ServerOverrides())

    companion object {
        val BUILD_DEFAULT_BASE_URL: String = BuildConfig.DH_PUBLIC_BASE_URL.trimEnd('/')

        /** 빌드 기본값만 담은 스냅샷(화면 상태 기본값) */
        val BUILD_DEFAULTS: ServerAddresses = ServerAddresses(BUILD_DEFAULT_BASE_URL)

        /** 우선순위: 사용자 지정 > 빌드 기본값(서버 주소) / 페어링 응답(MQTT). 정규화 규칙은 [normalize] */
        fun resolve(defaultBaseUrl: String, overrides: ServerOverrides): ServerAddresses {
            val n = normalize(defaultBaseUrl, overrides)
            return ServerAddresses(
                publicBaseUrl = n.publicBaseUrl ?: defaultBaseUrl.trimEnd('/'),
                baseUrlOverridden = n.publicBaseUrl != null,
                mqttUriOverride = n.mqttUri,
            )
        }

        /**
         * 서버 주소: 빈 값·빌드 기본값과 같은 origin 이면 지정 안 함. MQTT 주소: **빈 값만** 지정 안 함(= 페어링 응답 `mqtt.uri`) —
         * MQTT 의 '지정 안 함' 은 빌드 기본값이 아니라 서버 응답이라, 빌드 기본값과 같은 값도 사용자 지정으로 둔다(T32 리뷰).
         */
        private fun normalize(defaultBaseUrl: String, overrides: ServerOverrides): ServerOverrides {
            val base = overrides.publicBaseUrl?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() && !ServerAddressRules.sameOrigin(it, defaultBaseUrl) }
            val mqtt = overrides.mqttUri?.trim()?.takeIf { it.isNotEmpty() }
            return ServerOverrides(base, mqtt)
        }
    }
}
