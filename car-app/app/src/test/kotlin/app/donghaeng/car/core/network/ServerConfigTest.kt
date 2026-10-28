package app.donghaeng.car.core.network

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T32: 서버 주소 유효값 = 사용자 지정(숨은 메뉴) ?: 빌드 기본값, 요청마다 호스트 교체, 입력 검증 */
class ServerConfigTest {

    private val localBase = "http://10.0.2.2:8080"
    private val localMqtt = "tcp://10.0.2.2:1883"
    private val cloudBase = "https://1-2-3-4.sslip.io"

    private fun config(storage: ServerOverrideStorage = InMemoryServerOverrideStorage()) = ServerConfig(localBase, localMqtt, storage)

    @Test
    fun `without overrides the build defaults are effective`() {
        val c = config()
        assertEquals(ServerAddresses(localBase, localMqtt, baseUrlOverridden = false, mqttUriOverride = null), c.current.value)
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
        assertEquals("wss://1-2-3-4.sslip.io/mqtt", c.current.value.mqttPublicUri)
        // MQTT 사용자 지정이 있으면 서버 응답보다 앞선다
        assertEquals("wss://1-2-3-4.sslip.io/mqtt", c.brokerUriFor("ssl://1-2-3-4.sslip.io:8883"))
        // 다음 시작(같은 저장소)에서도 유지
        assertEquals(cloudBase, config(storage).publicBaseUrl)
        assertEquals(ServerOverrides(cloudBase, "wss://1-2-3-4.sslip.io/mqtt"), storage.load())
    }

    @Test
    fun `values equal to the build default or blank count as no override`() {
        val storage = InMemoryServerOverrideStorage()
        val c = config(storage)
        val change = c.apply(ServerOverrides(publicBaseUrl = "HTTP://10.0.2.2:8080/", mqttUri = "  "))
        assertFalse(change.any)
        assertFalse(c.current.value.anyOverride)
        assertEquals(ServerOverrides(null, null), storage.load())
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
    fun `form check compares with the current effective addresses`() {
        val current = ServerAddresses(localBase, localMqtt)
        assertEquals(ServerAddressRules.FormResult.Unchanged, ServerAddressRules.checkForm("http://10.0.2.2:8080/", "", "*", current))
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
