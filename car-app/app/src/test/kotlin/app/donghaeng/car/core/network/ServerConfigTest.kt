package app.donghaeng.car.core.network

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T32: 서버 주소 유효값 = 사용자 지정(숨은 메뉴) ?: 빌드 기본값, MQTT = 사용자 지정 ?: 페어링 응답, 요청마다 호스트 교체, 입력 검증 */
class ServerConfigTest {

    private val localBase = "http://10.0.2.2:8080"
    private val cloudBase = "https://1-2-3-4.sslip.io"

    private fun config(storage: ServerOverrideStorage = InMemoryServerOverrideStorage()) = ServerConfig(localBase, storage)

    @Test
    fun `without overrides the build defaults are effective`() {
        val c = config()
        assertEquals(ServerAddresses(localBase, baseUrlOverridden = false, mqttUriOverride = null), c.current.value)
        assertEquals(localBase, c.publicBaseUrl)
        assertEquals("ssl://server-given:8883", c.brokerUriFor("ssl://server-given:8883"))
        assertFalse(c.current.value.anyOverride)
    }

    @Test
    fun `override wins over build default and is persisted`() {
        val storage = InMemoryServerOverrideStorage()
        val c = config(storage)
        val change = c.apply(ServerOverrides(publicBaseUrl = "$cloudBase/", mqttUri = "wss://1-2-3-4.sslip.io/mqtt"))
        assertEquals(ServerChange(baseUrlChanged = true, mqttUriChanged = true), change)
        assertEquals(cloudBase, c.publicBaseUrl)
        assertTrue(c.current.value.baseUrlOverridden)
        assertEquals("wss://1-2-3-4.sslip.io/mqtt", c.current.value.mqttUriOverride)
        // MQTT 사용자 지정이 있으면 서버 응답보다 앞선다
        assertEquals("wss://1-2-3-4.sslip.io/mqtt", c.brokerUriFor("ssl://1-2-3-4.sslip.io:8883"))
        // 다음 시작(같은 저장소)에서도 유지
        assertEquals(cloudBase, config(storage).publicBaseUrl)
        assertEquals(ServerOverrides(cloudBase, "wss://1-2-3-4.sslip.io/mqtt"), storage.load())
    }

    @Test
    fun `base url equal to the build default (same origin) or blank mqtt count as no override`() {
        val storage = InMemoryServerOverrideStorage()
        val c = config(storage)
        val change = c.apply(ServerOverrides(publicBaseUrl = "HTTP://10.0.2.2:8080/", mqttUri = "  "))
        assertFalse(change.any)
        assertFalse(c.current.value.anyOverride)
        assertEquals(ServerOverrides(null, null), storage.load())
        // https 기본 포트를 붙여도 같은 서버 — 사용자 지정이 아니고 토큰도 지우지 않는다(T32 리뷰)
        val cloud = ServerConfig(cloudBase, InMemoryServerOverrideStorage())
        assertFalse(cloud.apply(ServerOverrides(publicBaseUrl = "https://1-2-3-4.sslip.io:443")).any)
        assertFalse(cloud.current.value.baseUrlOverridden)
    }

    @Test
    fun `mqtt override equal to the build default mqtt uri is kept — brokerUriFor returns it instead of the server answer`() {
        // 클라우드 서버의 DH_MQTT_PUBLIC_URI 설정이 빠져 페어링 응답이 tcp:// 인 경우를 고치려고 빌드 기본값(ssl://{host}:8883)을 넣는다(T32 리뷰)
        val storage = InMemoryServerOverrideStorage()
        val c = ServerConfig(cloudBase, storage)
        val buildDefaultMqtt = "ssl://1-2-3-4.sslip.io:8883"
        val change = c.apply(ServerOverrides(publicBaseUrl = null, mqttUri = buildDefaultMqtt))
        assertEquals(ServerChange(baseUrlChanged = false, mqttUriChanged = true), change)
        assertEquals(buildDefaultMqtt, c.brokerUriFor("tcp://1-2-3-4.sslip.io:1883"))
        assertEquals(ServerOverrides(null, buildDefaultMqtt), storage.load())
        // 숨은 메뉴 검증도 '바꿀 것 있음'(확인 창 → 저장)으로 본다
        assertEquals(
            ServerAddressRules.FormResult.Ready(ServerOverrides(cloudBase, buildDefaultMqtt), baseUrlChanges = false),
            ServerAddressRules.checkForm(cloudBase, buildDefaultMqtt, "10.0.2.2,localhost,127.0.0.1", ServerAddresses(cloudBase)),
        )
    }

    @Test
    fun `home and diagnostics show the broker actually used — override, else the pairing answer, else not yet known`() {
        val none = ServerAddresses(cloudBase)
        assertEquals(MqttAddressDisplay("ssl://1-2-3-4.sslip.io:8883", MqttAddressDisplay.Source.SERVER), MqttAddressDisplay.of(none, "ssl://1-2-3-4.sslip.io:8883"))
        assertEquals(MqttAddressDisplay.UNPAIRED, MqttAddressDisplay.of(none, null))
        // R9 폴백: 서버만 로컬로 바꿔도 MQTT 행은 빌드 기본(클라우드)값이 아니라 실제 페어링 응답(tcp://10.0.2.2:1883)
        assertEquals("tcp://10.0.2.2:1883", MqttAddressDisplay.of(ServerAddresses(localBase, baseUrlOverridden = true), "tcp://10.0.2.2:1883").uri)
        val override = ServerAddresses(cloudBase, mqttUriOverride = "wss://1-2-3-4.sslip.io/mqtt")
        assertEquals(MqttAddressDisplay("wss://1-2-3-4.sslip.io/mqtt", MqttAddressDisplay.Source.OVERRIDE), MqttAddressDisplay.of(override, "ssl://x:8883"))
    }

    @Test
    fun `reset returns to build defaults and reports what changed`() {
        val c = config(InMemoryServerOverrideStorage(ServerOverrides(cloudBase, null)))
        assertEquals(cloudBase, c.publicBaseUrl)
        assertEquals(ServerChange(baseUrlChanged = true, mqttUriChanged = false), c.reset())
        assertEquals(localBase, c.publicBaseUrl)
        // MQTT 만 바꾸면 서버는 그대로
        assertEquals(ServerChange(baseUrlChanged = false, mqttUriChanged = true), c.apply(ServerOverrides(null, "ws://10.0.2.2:8083")))
        assertEquals(localBase, c.publicBaseUrl)
    }

    @Test
    fun `interceptor rewrites only requests to the routed build default origin`() {
        val routed = "http://10.0.2.2:8080".toHttpUrl()
        val cloud = cloudBase.toHttpUrl()
        val url = "http://10.0.2.2:8080/v1/groups/grp_1/invites?x=1".toHttpUrl()
        assertEquals("https://1-2-3-4.sslip.io/v1/groups/grp_1/invites?x=1", ServerHostInterceptor.rewrite(url, routed, cloud).toString())
        // 기본값 그대로면 바꾸지 않는다
        assertNull(ServerHostInterceptor.rewrite(url, routed, routed))
        // 다른 origin 으로 가는 요청(절대 URL)은 건드리지 않는다
        assertNull(ServerHostInterceptor.rewrite("https://example.org/v1/time".toHttpUrl(), routed, cloud))
        // 포트까지 바꾼다
        assertEquals("http://192.168.0.5:18080/v1/time", ServerHostInterceptor.rewrite("http://10.0.2.2:8080/v1/time".toHttpUrl(), routed, "http://192.168.0.5:18080".toHttpUrl()).toString())
    }

    @Test
    fun `base url input must be http or https host with optional port`() {
        assertEquals(ServerAddressRules.Result.Ok(cloudBase), ServerAddressRules.checkBaseUrl(" https://1-2-3-4.sslip.io/ ", "*"))
        assertEquals(ServerAddressRules.Result.Ok("http://10.0.2.2:8080"), ServerAddressRules.checkBaseUrl("HTTP://10.0.2.2:8080", "10.0.2.2,localhost,127.0.0.1"))
        // 기본 포트는 지운다(같은 서버를 다른 서버로 보지 않게), 다른 포트는 남긴다
        assertEquals(ServerAddressRules.Result.Ok(cloudBase), ServerAddressRules.checkBaseUrl("https://1-2-3-4.sslip.io:443", "*"))
        assertEquals(ServerAddressRules.Result.Ok("http://10.0.2.2"), ServerAddressRules.checkBaseUrl("http://10.0.2.2:80/", "*"))
        assertEquals(ServerAddressRules.Result.Ok("https://1-2-3-4.sslip.io:8443"), ServerAddressRules.checkBaseUrl("https://1-2-3-4.sslip.io:8443", "*"))
        assertEquals(ServerAddressRules.Result.Invalid(ServerAddressRules.Error.BASE_FORMAT), ServerAddressRules.checkBaseUrl("1-2-3-4.sslip.io", "*"))
        assertEquals(ServerAddressRules.Result.Invalid(ServerAddressRules.Error.BASE_FORMAT), ServerAddressRules.checkBaseUrl("ftp://h", "*"))
        assertEquals(ServerAddressRules.Result.Invalid(ServerAddressRules.Error.BASE_FORMAT), ServerAddressRules.checkBaseUrl("", "*"))
        assertEquals(ServerAddressRules.Result.Invalid(ServerAddressRules.Error.BASE_HAS_PATH), ServerAddressRules.checkBaseUrl("https://h/v1", "*"))
        assertEquals(ServerAddressRules.Result.Invalid(ServerAddressRules.Error.BASE_HAS_PATH), ServerAddressRules.checkBaseUrl("https://h?x=1", "*"))
        // 클라우드 빌드(평문은 로컬 호스트만): LAN IP 의 http 는 막힌다
        assertEquals(ServerAddressRules.Result.Invalid(ServerAddressRules.Error.BASE_CLEARTEXT_BLOCKED), ServerAddressRules.checkBaseUrl("http://192.168.0.5:8080", "10.0.2.2,localhost,127.0.0.1"))
        assertEquals(ServerAddressRules.Result.Ok("http://192.168.0.5:8080"), ServerAddressRules.checkBaseUrl("http://192.168.0.5:8080", "*"))
    }

    @Test
    fun `mqtt input is optional and follows the broker policy of the new server`() {
        assertEquals(ServerAddressRules.Result.Ok(null), ServerAddressRules.checkMqttUri("  ", cloudBase))
        assertEquals(ServerAddressRules.Result.Ok("wss://1-2-3-4.sslip.io/mqtt"), ServerAddressRules.checkMqttUri("wss://1-2-3-4.sslip.io/mqtt", cloudBase))
        assertEquals(ServerAddressRules.Result.Invalid(ServerAddressRules.Error.MQTT_PLAINTEXT), ServerAddressRules.checkMqttUri("tcp://1-2-3-4.sslip.io:1883", cloudBase))
        assertEquals(ServerAddressRules.Result.Invalid(ServerAddressRules.Error.MQTT_FORMAT), ServerAddressRules.checkMqttUri("http://h", cloudBase))
        assertEquals(ServerAddressRules.Result.Ok("tcp://10.0.2.2:1883"), ServerAddressRules.checkMqttUri("tcp://10.0.2.2:1883", localBase))
    }

    @Test
    fun `same origin compares scheme host and port with default ports filled in`() {
        assertTrue(ServerAddressRules.sameOrigin("https://1-2-3-4.sslip.io:443", cloudBase))
        assertTrue(ServerAddressRules.sameOrigin("HTTPS://1-2-3-4.SSLIP.IO/", cloudBase))
        assertFalse(ServerAddressRules.sameOrigin("https://1-2-3-4.sslip.io:8443", cloudBase))
        assertFalse(ServerAddressRules.sameOrigin("http://1-2-3-4.sslip.io", cloudBase))
        assertTrue(ServerAddressRules.sameOrigin("nope", "NOPE/"))
    }

    @Test
    fun `form check compares with the current effective addresses`() {
        val current = ServerAddresses(localBase)
        assertEquals(ServerAddressRules.FormResult.Unchanged, ServerAddressRules.checkForm("http://10.0.2.2:8080/", "", "*", current))
        // 기본 포트만 붙인 같은 서버도 바뀐 것 없음
        assertEquals(ServerAddressRules.FormResult.Unchanged, ServerAddressRules.checkForm("https://1-2-3-4.sslip.io:443", "", "*", ServerAddresses(cloudBase)))
        assertEquals(
            ServerAddressRules.FormResult.Ready(ServerOverrides(cloudBase, null), baseUrlChanges = true),
            ServerAddressRules.checkForm(cloudBase, "", "*", current),
        )
        assertEquals(
            ServerAddressRules.FormResult.Ready(ServerOverrides(localBase, "ws://10.0.2.2:8083"), baseUrlChanges = false),
            ServerAddressRules.checkForm(localBase, "ws://10.0.2.2:8083", "*", current),
        )
        // https 로 바꾸면서 평문 브로커 → MQTT 오류(새 서버 기준)
        assertEquals(
            ServerAddressRules.FormResult.Invalid(null, ServerAddressRules.Error.MQTT_PLAINTEXT),
            ServerAddressRules.checkForm(cloudBase, "tcp://10.0.2.2:1883", "*", current),
        )
        assertEquals(
            ServerAddressRules.FormResult.Invalid(ServerAddressRules.Error.BASE_FORMAT, null),
            ServerAddressRules.checkForm("nope", "", "*", current),
        )
    }
}
