package app.donghaeng.companion.sdk.internal

import app.donghaeng.companion.sdk.AccountProvider
import app.donghaeng.companion.sdk.DongHaengConfig
import app.donghaeng.companion.sdk.GroupSnapshot
import app.donghaeng.companion.sdk.TokenProvider
import app.donghaeng.protocol.DhJson
import app.donghaeng.protocol.GroupEvent
import app.donghaeng.protocol.VoiceMessagePayload
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * F16 무전 수신의 HTTP 경로 — `observeGroup` 폴링이 `VOICE_MESSAGE` 를 보면 같은 동행 JWT 로
 * `GET /v1/groups/{id}/voice/{voiceId}` 를 불러 캐시하고 payload 에 상태를 붙이는지(MockWebServer).
 */
class HttpVoiceDownloadTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var baseUrl: String
    private val dhJwt = jwt()
    private val audio = ByteArray(2_048) { (it % 251).toByte() }

    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        baseUrl = server.url("/").toString().trimEnd('/')
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun api(clock: () -> Long = System::currentTimeMillis) = HttpDongHaengApi(
        config = DongHaengConfig(publicBaseUrl = baseUrl, provider = AccountProvider.DEV, appVersion = "0.1.0-test"),
        tokenProvider = TokenProvider { "host-token" },
        client = client,
        pollIntervalMs = 10,
        clock = clock,
        voiceInbox = VoiceInbox(rootDir = File(tmp.root, "voice"), clock = clock),
    )

    private fun jwt(expEpochS: Long = System.currentTimeMillis() / 1000 + 3600): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        val exp = expEpochS
        return enc.encodeToString("""{"alg":"RS256"}""".toByteArray()) + "." +
            enc.encodeToString("""{"sub":"usr_t","typ":"user","exp":$exp}""".toByteArray()) + ".sig"
    }

    private fun json(code: Int, body: String) = MockResponse().setResponseCode(code)
        .setHeader("Content-Type", "application/json").setBody(body)

    private fun exchangeOk(token: String = dhJwt) =
        json(200, """{"accessToken":"$token","refreshToken":"rt","user":{"userId":"usr_t","displayName":"동승자"}}""")

    private fun groupWithVoice(expiresAtMs: Long = System.currentTimeMillis() + 24 * 3600_000L, voiceId: String = "vm_1"): MockResponse {
        val payload = DhJson.json.encodeToString(VoiceMessagePayload.serializer(), VoiceMessagePayload(voiceId, 3_200, "audio/mp4", audio.size, expiresAtMs))
        return json(
            200,
            """{"groupId":"grp_1","name":"추석 귀성길","leaderVehicleId":"veh_lead","status":"ACTIVE",
                "members":[{"vehicleId":"veh_lead","nickname":"아빠 차","color":"#2E7DFF","role":"LEADER","joinedAt":1}],
                "recentEvents":[
                  {"eventId":"evt_v1","type":"VOICE_MESSAGE","sourceVehicleId":"veh_lead","severity":"INFO","speech":"아빠 차 무전","payload":$payload},
                  {"eventId":"evt_j1","type":"MEMBER_JOINED","sourceVehicleId":"veh_lead","severity":"INFO","speech":"합류","payload":{}}
                ]}""",
        )
    }

    private fun audioResponse(bytes: ByteArray = audio) = MockResponse().setResponseCode(200)
        .setHeader("Content-Type", "audio/mp4").setBody(Buffer().write(bytes))

    private fun httpDate(ms: Long): String =
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

    private fun GroupSnapshot.voice(): GroupEvent = events.first { it.type == "VOICE_MESSAGE" }
    private fun GroupEvent.status(): String? = payload[VoiceMessages.KEY_STATUS]?.jsonPrimitive?.content
    private fun GroupEvent.path(): String? = payload[VoiceMessages.KEY_PATH]?.jsonPrimitive?.content

    @Test
    fun `VOICE_MESSAGE 오디오를 동행 JWT 로 받아 캐시하고 payload 에 READY·경로를 붙인다`() = runTest {
        server.enqueue(exchangeOk())
        server.enqueue(groupWithVoice())
        server.enqueue(audioResponse())
        server.enqueue(groupWithVoice()) // 두 번째 폴링 — 다시 받지 않는다

        val snapshots = api().observeGroup("grp_1").take(2).toList()

        assertEquals("교환 + 그룹 + 오디오 + 그룹", 4, server.requestCount)
        assertEquals("/v1/auth/exchange", server.takeRequest().path)
        assertEquals("/v1/groups/grp_1", server.takeRequest().path)
        val download = server.takeRequest()
        assertEquals("GET", download.method)
        assertEquals("/v1/groups/grp_1/voice/vm_1", download.path)
        assertEquals("Bearer $dhJwt", download.getHeader("Authorization"))
        assertTrue(download.getHeader("Accept").orEmpty().startsWith("audio/"))
        assertEquals("/v1/groups/grp_1", server.takeRequest().path)

        val first = snapshots[0]
        assertEquals("events 는 오래된 것부터", listOf("evt_j1", "evt_v1"), first.events.map { it.eventId })
        val voice = first.voice()
        assertEquals("READY", voice.status())
        assertArrayEquals(audio, File(voice.path()!!).readBytes())
        // 원래 payload 는 그대로 해석된다
        val payload = DhJson.payloadAs(voice.payload, VoiceMessagePayload.serializer())
        assertEquals("vm_1", payload.voiceId)
        assertEquals(3_200, payload.durationMs)
        assertEquals("아빠 차 무전", voice.speech)
        assertEquals("veh_lead", voice.sourceVehicleId)
        assertNull("다른 이벤트에는 붙이지 않는다", first.events[0].payload[VoiceMessages.KEY_STATUS])

        assertEquals("READY", snapshots[1].voice().status())
        assertEquals(voice.path(), snapshots[1].voice().path())
    }

    @Test
    fun `404 는 EXPIRED - 스냅샷은 그대로 나온다`() = runTest {
        server.enqueue(exchangeOk())
        server.enqueue(groupWithVoice())
        server.enqueue(json(404, """{"error":{"code":"NOT_FOUND","message":"무전을 찾을 수 없어요","traceId":"t"}}"""))

        val snap = api().observeGroup("grp_1").take(1).toList().single()

        assertEquals("EXPIRED", snap.voice().status())
        assertNull(snap.voice().path())
        assertEquals(2, snap.events.size)
    }

    @Test
    fun `5xx 는 PENDING - 다음 폴링에 다시 받아 READY`() = runTest {
        server.enqueue(exchangeOk())
        server.enqueue(groupWithVoice())
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(groupWithVoice())
        server.enqueue(audioResponse())

        val snapshots = api().observeGroup("grp_1").take(2).toList()

        assertEquals("PENDING", snapshots[0].voice().status())
        assertEquals("READY", snapshots[1].voice().status())
        assertEquals(5, server.requestCount)
    }

    @Test
    fun `상한(1 MB)을 넘는 오디오 응답은 버리고 FAILED`() = runTest {
        server.enqueue(exchangeOk())
        server.enqueue(groupWithVoice())
        server.enqueue(audioResponse(ByteArray((VoiceMessages.MAX_AUDIO_BYTES + 1).toInt())))

        val snap = api().observeGroup("grp_1").take(1).toList().single()

        assertEquals("FAILED", snap.voice().status())
        assertTrue(File(tmp.root, "voice/grp_1").listFiles().isNullOrEmpty())
    }

    @Test
    fun `오디오 요청이 401 이면 재교환 후 다시 받는다`() = runTest {
        val second = jwt() + "2"
        server.enqueue(exchangeOk())
        server.enqueue(groupWithVoice())
        server.enqueue(json(401, """{"error":{"code":"INVALID_TOKEN","message":"만료","traceId":"t"}}"""))
        server.enqueue(json(200, """{"accessToken":"$second","refreshToken":"rt","user":{"userId":"usr_t","displayName":"동승자"}}"""))
        server.enqueue(audioResponse())

        val snap = api().observeGroup("grp_1").take(1).toList().single()

        assertEquals("READY", snap.voice().status())
        server.takeRequest(); server.takeRequest(); server.takeRequest()
        assertEquals("/v1/auth/exchange", server.takeRequest().path)
        val retry = server.takeRequest()
        assertEquals("/v1/groups/grp_1/voice/vm_1", retry.path)
        assertEquals("Bearer $second", retry.getHeader("Authorization"))
    }

    @Test
    fun `만료 판단은 응답 Date 헤더(서버 시계) 기준 - 폰 시계가 이틀 빨라도 받는다`() = runTest {
        val serverNow = System.currentTimeMillis()
        val phoneClock = { serverNow + 2 * 24 * 3600_000L }
        // 동행 JWT 캐시 만료도 이 기기 시계로 본다(기존 동작) — 테스트 토큰은 틀린 시계 기준으로도 유효하게
        server.enqueue(exchangeOk(jwt(expEpochS = phoneClock() / 1000 + 3600)))
        server.enqueue(groupWithVoice(expiresAtMs = serverNow + 23 * 3600_000L).setHeader("Date", httpDate(serverNow)))
        server.enqueue(audioResponse())

        val snap = api(clock = phoneClock).observeGroup("grp_1").take(1).toList().single()

        assertEquals("READY", snap.voice().status())
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `서버 시계로도 만료된 무전은 요청하지 않는다`() = runTest {
        val serverNow = System.currentTimeMillis()
        server.enqueue(exchangeOk())
        server.enqueue(groupWithVoice(expiresAtMs = serverNow - 1_000).setHeader("Date", httpDate(serverNow)))

        val snap = api().observeGroup("grp_1").take(1).toList().single()

        assertEquals("EXPIRED", snap.voice().status())
        assertEquals("교환 + 그룹만", 2, server.requestCount)
    }
}
