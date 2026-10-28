package app.donghaeng.car.debug

import android.content.Context
import android.content.Intent
import app.donghaeng.car.core.common.DhLog
import app.donghaeng.car.core.network.ServerConfig
import app.donghaeng.car.core.network.mqtt.MqttGateway
import app.donghaeng.car.domain.latency.LatencyMeter
import app.donghaeng.car.domain.latency.LatencyPercentiles
import app.donghaeng.protocol.rest.ApiPaths
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** 네트워크·지연 진단 액션 전용 의존성 — [DemoControlReceiver.Deps] 와 따로 둔다(다른 작업의 Deps 변경과 충돌하지 않게) */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface DemoNetDeps {
    /** 앱의 REST 클라이언트 그 자체(같은 인터셉터·같은 연결 풀) — `NetworkModule.provideOkHttpClient` */
    fun okHttpClient(): OkHttpClient
    fun serverConfig(): ServerConfig
    fun mqttGateway(): MqttGateway
    fun latencyMeter(): LatencyMeter
}

/**
 * 네트워크·지연 진단 액션 (debug 빌드 전용 — `DemoControlReceiver` 가 넘겨준다). `tools/demo/latency.py` 가 쓴다(docs/09 §4.2, docs/05 §3.4).
 *
 * ```
 * adb -s <serial> shell am broadcast -n app.donghaeng.car/.debug.DemoControlReceiver --es action net_rtt --ei n 30 --ei gap_ms 300 [--ez fresh true]
 * ```
 * | action | extras | 동작 · 로그 |
 * |---|---|---|
 * | `net_rtt` | `n`(기본 30, 2~500), `gap_ms`(기본 300), `fresh`(ez, 기본 false — 먼저 **유휴** 연결을 비운다. 진행 중 요청이 쓰는 연결은 남아 첫 호출이 거기 붙을 수 있으니 수립 포함 여부는 `firstConn=new` 로 확인) | 앱 REST 클라이언트(DI 의 OkHttpClient — 같은 인터셉터·기본 주소·TLS 연결 풀)로 `GET /v1/time` n 번. → `net: http_rtt n=… first=…ms p50=…ms p95=…ms min=…ms max=…ms base=<scheme://host[:port]> firstConn=new\|reused …` |
 * | `net_mqtt_rtt` | `n`, `gap_ms` | 앱 MQTT 클라이언트(HiveMQ)의 QoS 1 PUBLISH → PUBACK n 번(내 presence 토픽에 지금과 같은 `{"online":true}`, retain 없음 — [MqttGateway.probeAckNanos]). **브로커(EMQX)까지 왕복 — 서버 앱은 거치지 않는다.** → `net: mqtt_rtt … base=<브로커 scheme://host:port> transport=tls\|wss… via=broker-puback` |
 * | `latency_dump` | | [LatencyMeter.summary] — `latency: summary event n=… p50=…ms p95=…ms min=…ms max=…ms \| state n=… … \| clockRtt=…ms offset=…ms clamped=… unsynced=…` |
 * | `latency_reset` | | 누적 표본 비움 — `latency: reset event=<비운 수> state=<비운 수>` |
 *
 * - **첫 표본은 따로**(`first=`): 연결 수립(DNS·TCP·TLS)이 들어갈 수 있다. `n`·P50·P95·min·max 는 **두 번째 표본부터**(`n` = 백분위에 들어간 표본 수).
 *   백분위는 최근접 순위법([LatencyPercentiles]). 실패한 시도는 표본에 넣지 않고 `fail=` 로 센다.
 * - 왕복은 `System.nanoTime` — HTTP 는 요청 직전 ~ 응답 본문을 다 읽은 뒤(같은 스레드 `execute`), MQTT 는 보내기 직전 ~ PUBACK 완료 콜백.
 * - 프로브는 브로드캐스트(goAsync 시간 한도)와 떼어 앱 프로세스에서 끝까지 돈다 — 시작하면 `net: … start` 줄, 끝나면 결과 줄. 동시에 하나만.
 * - 로그에는 주소의 scheme·host·port 만 — 경로·토큰·좌표·닉네임 없음(규칙 8).
 */
object DemoNetActions {
    val ACTIONS = setOf("net_rtt", "net_mqtt_rtt", "latency_dump", "latency_reset")

    private val probeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)

    const val DEFAULT_N = 30
    const val DEFAULT_GAP_MS = 300
    private const val MQTT_ACK_TIMEOUT_MS = 5_000L

    suspend fun handle(context: Context, action: String, intent: Intent) {
        val d = EntryPointAccessors.fromApplication(context.applicationContext, DemoNetDeps::class.java)
        when (action) {
            "net_rtt", "net_mqtt_rtt" -> {
                val kind = if (action == "net_rtt") "http_rtt" else "mqtt_rtt"
                val n = intent.intArg("n", DEFAULT_N).coerceIn(2, 500)
                val gapMs = intent.intArg("gap_ms", DEFAULT_GAP_MS).coerceIn(0, 10_000).toLong()
                val fresh = intent.getBooleanExtra("fresh", false)
                if (!running.compareAndSet(false, true)) {
                    DhLog.w("net: $kind busy — 다른 프로브가 도는 중")
                    return
                }
                DhLog.i("net: $kind start n=$n gap=${gapMs}ms" + if (kind == "http_rtt") " fresh=$fresh" else "")
                probeScope.launch {
                    try {
                        val line = if (kind == "http_rtt") httpRtt(d, n, gapMs, fresh) else mqttRtt(d, n, gapMs)
                        DhLog.i(line)
                    } catch (e: Exception) {
                        DhLog.w("net: $kind 실패 ${e.javaClass.simpleName}")
                    } finally {
                        running.set(false)
                    }
                }
            }
            "latency_dump" -> DhLog.i(d.latencyMeter().summary().toLogLine())
            "latency_reset" -> {
                val (events, states) = d.latencyMeter().resetHistory()
                DhLog.i("latency: reset event=$events state=$states")
            }
        }
    }

    /** `GET /v1/time` n 번 — 앱 클라이언트를 `newBuilder()` 로 늘려 이벤트 리스너만 붙인다(연결 풀·디스패처·인터셉터는 그대로 공유) */
    private suspend fun httpRtt(d: DemoNetDeps, n: Int, gapMs: Long, fresh: Boolean): String {
        val appClient = d.okHttpClient()
        // 유휴 연결만 닫는다 — 앱의 다른 요청이 진행 중인 연결(h2 다중화)은 남아 첫 호출이 거기 붙을 수 있다(firstConn=reused 로 드러난다)
        if (fresh) appClient.connectionPool.evictAll()
        var trace: CallTrace? = null
        val client = appClient.newBuilder().eventListenerFactory { CallTrace().also { trace = it } }.build()
        // Retrofit 과 같은 주소: 빌드 기본값 + 경로 → ServerHostInterceptor 가 지금 서버 주소(숨은 메뉴)로 바꾼다
        val url = ServerConfig.BUILD_DEFAULT_BASE_URL + ApiPaths.BASE + "/time"
        val attempts = ArrayList<Long?>(n)
        var first: CallTrace? = null
        var newConnAfterFirst = 0
        var protocol: Protocol? = null
        var lastError: String? = null
        for (i in 0 until n) {
            val request = Request.Builder().url(url).get().build()
            val t0 = System.nanoTime()
            val us: Long? = try {
                client.newCall(request).execute().use { resp ->
                    resp.body?.bytes()
                    val elapsed = (System.nanoTime() - t0) / 1_000
                    protocol = resp.protocol
                    if (resp.isSuccessful) elapsed else { lastError = "http${resp.code}"; null }
                }
            } catch (e: Exception) {
                lastError = e.javaClass.simpleName
                null
            }
            attempts += us
            if (i == 0) first = trace else if (trace?.newConnection == true) newConnAfterFirst++
            if (i < n - 1) delay(gapMs)
        }
        val f = first
        val firstInfo = when {
            f == null -> ""
            f.newConnection -> " firstConn=new firstDns=${NetProbeFormat.ms(f.dnsNs / 1_000)} firstConnect=${NetProbeFormat.ms(f.connectNs / 1_000)}" +
                (if (f.tlsNs > 0) " firstTls=${NetProbeFormat.ms(f.tlsNs / 1_000)}" else "")
            else -> " firstConn=reused"
        }
        val extra = "proto=${protocol ?: "-"}$firstInfo newConnAfterFirst=$newConnAfterFirst gap=${gapMs}ms" + (lastError?.let { " lastError=$it" } ?: "")
        return NetProbeFormat.line("http_rtt", attempts, base = NetProbeFormat.origin(d.serverConfig().publicBaseUrl), extra = extra)
    }

    /** QoS 1 PUBLISH → PUBACK n 번(브로커 왕복) */
    private suspend fun mqttRtt(d: DemoNetDeps, n: Int, gapMs: Long): String {
        val gateway = d.mqttGateway()
        val attempts = ArrayList<Long?>(n)
        var lastError: String? = null
        for (i in 0 until n) {
            val r = gateway.probeAckNanos(MQTT_ACK_TIMEOUT_MS)
            attempts += r.getOrNull()?.let { it / 1_000 }
            r.exceptionOrNull()?.let { lastError = NetProbeFormat.token(it.message ?: it.javaClass.simpleName) }
            if (i < n - 1) delay(gapMs)
        }
        val st = gateway.status.value
        val extra = "transport=${st.transport.label} state=${st.state} via=broker-puback gap=${gapMs}ms" + (lastError?.let { " lastError=$it" } ?: "")
        return NetProbeFormat.line("mqtt_rtt", attempts, base = NetProbeFormat.origin(st.brokerUri), extra = extra)
    }

    /** 호출 하나의 연결 수립 구간(`execute` 라 모든 콜백이 호출 스레드에서 온다) */
    private class CallTrace : EventListener() {
        var newConnection = false
        var dnsNs = 0L
        var connectNs = 0L
        var tlsNs = 0L
        private var dnsStart = 0L
        private var connectStart = 0L
        private var tlsStart = 0L

        override fun dnsStart(call: Call, domainName: String) { dnsStart = System.nanoTime() }
        override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) { dnsNs = System.nanoTime() - dnsStart }
        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) { newConnection = true; connectStart = System.nanoTime() }
        override fun secureConnectStart(call: Call) { tlsStart = System.nanoTime() }
        override fun secureConnectEnd(call: Call, handshake: Handshake?) { tlsNs = System.nanoTime() - tlsStart }
        override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) { connectNs = System.nanoTime() - connectStart }
    }

    /** `--ei n 30` 과 `--es n 30` 둘 다 받는다(demo.py `broadcast` 는 문자열로 보낸다) */
    @Suppress("DEPRECATION")
    private fun Intent.intArg(name: String, default: Int): Int = when (val v = extras?.get(name)) {
        is Int -> v
        is Long -> v.toInt()
        is String -> v.trim().toIntOrNull() ?: default
        else -> default
    }
}

/** 프로브 결과 한 줄 만들기 — 순수 함수(testDebug `NetProbeFormatTest`) */
internal object NetProbeFormat {

    /**
     * `net: <kind> n=… first=…ms p50=…ms p95=…ms min=…ms max=…ms base=… fail=… <extra>`.
     * [attemptsUs] = 시도 순서대로 왕복(µs), 실패는 null. 첫 시도는 `first=`(실패면 `fail`)로만, 백분위·`n` 은 **두 번째 시도부터의 성공 표본**.
     */
    fun line(kind: String, attemptsUs: List<Long?>, base: String, extra: String = ""): String {
        val first = attemptsUs.firstOrNull()
        val rest = attemptsUs.drop(1).filterNotNull()
        val fail = attemptsUs.count { it == null }
        val s = LatencyPercentiles.summarize(rest)
        val stats = if (s == null) "n=0 first=${first?.let(::ms) ?: "fail"} p50=- p95=- min=- max=-"
        else "n=${s.n} first=${first?.let(::ms) ?: "fail"} p50=${ms(s.p50)} p95=${ms(s.p95)} min=${ms(s.min)} max=${ms(s.max)}"
        return "net: $kind $stats base=$base fail=$fail" + (if (extra.isNotBlank()) " ${extra.trim()}" else "")
    }

    /** µs → `12.3ms`(소수 한 자리, 로캘 무관) */
    fun ms(us: Long): String = String.format(Locale.ROOT, "%.1fms", us / 1_000.0)

    /** `scheme://host[:port]` — 경로·쿼리·사용자 정보(토큰이 섞일 수 있는 곳)는 버린다. 해석이 안 되면 `-` */
    fun origin(uri: String?): String {
        if (uri.isNullOrBlank()) return "-"
        return runCatching {
            val u = URI(uri.trim())
            val scheme = u.scheme ?: return "-"
            val host = u.host ?: return "-"
            "$scheme://$host" + if (u.port >= 0) ":${u.port}" else ""
        }.getOrDefault("-")
    }

    /** 로그 값 하나(공백 없이 48자까지) — 예외 메시지를 key=value 로 남길 때 */
    fun token(text: String): String = text.trim().replace(Regex("\\s+"), "_").take(48).ifEmpty { "-" }
}
