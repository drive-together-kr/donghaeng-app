package app.donghaeng.car.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 디버그 `net_rtt`·`net_mqtt_rtt` 결과 줄 — tools/demo/latency.py 가 이 줄을 그대로 해석한다(test_latency.py 의 같은 모양 줄).
 * 첫 시도는 `first=` 로만, `n`·백분위는 두 번째 시도부터의 성공 표본(최근접 순위법), 실패는 `fail=` 로만 센다.
 */
class NetProbeFormatTest {

    @Test
    fun `first sample is separated and percentiles start from the second`() {
        // µs: 첫 시도 412.3 ms(연결 수립), 이후 20개 = 100.0, 101.0, …, 119.0 ms
        val attempts = listOf<Long?>(412_300L) + (0 until 20).map { 100_000L + it * 1_000L }
        val line = NetProbeFormat.line("http_rtt", attempts, base = "https://1.2.3.4.sslip.io", extra = "proto=http/1.1 firstConn=new")
        assertEquals(
            "net: http_rtt n=20 first=412.3ms p50=109.0ms p95=118.0ms min=100.0ms max=119.0ms base=https://1.2.3.4.sslip.io fail=0 proto=http/1.1 firstConn=new",
            line,
        )
    }

    @Test
    fun `failures are counted, not sampled`() {
        val line = NetProbeFormat.line("mqtt_rtt", listOf(null, 5_000L, null, 7_000L), base = "ssl://1.2.3.4.sslip.io:8883")
        assertEquals("net: mqtt_rtt n=2 first=fail p50=5.0ms p95=7.0ms min=5.0ms max=7.0ms base=ssl://1.2.3.4.sslip.io:8883 fail=2", line)
        assertEquals(
            "net: mqtt_rtt n=0 first=fail p50=- p95=- min=- max=- base=- fail=2 lastError=not-connected",
            NetProbeFormat.line("mqtt_rtt", listOf(null, null), base = "-", extra = "lastError=not-connected"),
        )
    }

    @Test
    fun `origin keeps scheme host and port only`() {
        assertEquals("https://1.2.3.4.sslip.io", NetProbeFormat.origin("https://1.2.3.4.sslip.io/v1/time?token=secret"))
        assertEquals("http://10.0.2.2:8080", NetProbeFormat.origin("http://10.0.2.2:8080"))
        assertEquals("wss://1.2.3.4.sslip.io", NetProbeFormat.origin("wss://1.2.3.4.sslip.io/mqtt"))
        assertEquals("ssl://1.2.3.4.sslip.io:8883", NetProbeFormat.origin("ssl://1.2.3.4.sslip.io:8883"))
        val withUser = NetProbeFormat.origin("https://user:pass@host.example:8443/path")
        assertEquals("https://host.example:8443", withUser)
        assertFalse(withUser.contains("pass"))
        assertEquals("-", NetProbeFormat.origin(null))
        assertEquals("-", NetProbeFormat.origin("not a uri"))
    }

    @Test
    fun `values are locale independent and tokens have no spaces`() {
        assertEquals("0.9ms", NetProbeFormat.ms(940))
        assertEquals("1234.6ms", NetProbeFormat.ms(1_234_560))
        assertEquals("PUBACK_contained_an_Error_Code", NetProbeFormat.token(" PUBACK contained an Error Code "))
        assertEquals("-", NetProbeFormat.token("   "))
    }
}
