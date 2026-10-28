package app.donghaeng.car.core.network

import app.donghaeng.protocol.DhJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.create
import java.io.File
import java.util.concurrent.TimeUnit

/** F16 무전 업로드(multipart)·다운로드 — docs/03 §2.6 계약 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceMessageClientTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var client: VoiceMessageClient

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build())
            .addConverterFactory(DhJson.json.asConverterFactory("application/json".toMediaType()))
            .build()
        client = VoiceMessageClient(retrofit.create<VoiceApi>(), io = Dispatchers.IO)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun clip(bytes: Int, name: String = "ptt_1.m4a"): File = tmp.newFile(name).apply { writeBytes(ByteArray(bytes) { (it % 7).toByte() }) }

    @Test
    fun `업로드 — multipart audio(파일·mime) + durationMs, 201 VoiceUploadResponse`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"voiceId":"vm_01","eventId":"evt_9","durationMs":3200,"expiresAtMs":1793000000000}"""))
        val r = client.upload("grp_1", clip(12_000), "audio/mp4", 3_200)
        assertTrue(r is ApiResult.Success)
        assertEquals("vm_01", (r as ApiResult.Success).value.voiceId)
        assertEquals(3_200, r.value.durationMs)

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/v1/groups/grp_1/voice", req.path)
        assertTrue(req.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
        val body = req.body.readUtf8()
        assertTrue(body.contains("""Content-Disposition: form-data; name="audio"; filename="ptt_1.m4a""""))
        assertTrue(body.contains("Content-Type: audio/mp4"))
        assertTrue(body.contains("""Content-Disposition: form-data; name="durationMs""""))
        assertTrue(body.contains("\r\n\r\n3200\r\n"))
    }

    @Test
    fun `서버 오류 코드를 그대로 올린다 — 429 VOICE_RATE_LIMITED, 413`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"code":"VOICE_RATE_LIMITED","message":"잠시 뒤","traceId":"t"}}"""))
        val r = client.upload("grp_1", clip(100), "audio/mp4", 1_000)
        assertTrue(r is ApiResult.HttpFailure)
        assertEquals("VOICE_RATE_LIMITED", (r as ApiResult.HttpFailure).code)
        assertEquals("HTTP 429 VOICE_RATE_LIMITED", r.summary)

        server.enqueue(MockResponse().setResponseCode(413).setBody("""{"error":{"code":"VOICE_TOO_LARGE","message":"크다","traceId":"t"}}"""))
        assertEquals("VOICE_TOO_LARGE", (client.upload("grp_1", clip(100, "b.m4a"), "audio/mp4", 1_000) as ApiResult.HttpFailure).code)
    }

    @Test
    fun `계약 상한을 넘으면 보내지 않는다 — 512 KB·16 s·mime`() = runTest {
        assertEquals("client-too-large", VoiceMessageClient.clientCheck(clip(512 * 1024 + 1), "audio/mp4", 3_000))
        assertEquals("client-bad-duration", VoiceMessageClient.clientCheck(clip(10, "c.m4a"), "audio/mp4", 16_001))
        assertEquals("client-bad-duration", VoiceMessageClient.clientCheck(clip(10, "d.m4a"), "audio/mp4", 0))
        assertEquals("client-bad-mime", VoiceMessageClient.clientCheck(clip(10, "e.mp3"), "audio/mpeg", 1_000))
        assertEquals(null, VoiceMessageClient.clientCheck(clip(10, "f.wav"), "audio/wav", 16_000))
        val r = client.upload("grp_1", clip(512 * 1024 + 1, "g.m4a"), "audio/mp4", 3_000)
        assertTrue(r is ApiResult.UnexpectedFailure)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `다운로드 — 바이트를 파일로, 404 는 실패`() = runTest {
        val bytes = ByteArray(9_000) { (it % 13).toByte() }
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "audio/mp4").setBody(Buffer().write(bytes)))
        val dest = File(tmp.root, "in/vm_01.m4a")
        val r = client.download("grp_1", "vm_01", dest)
        assertTrue(r is ApiResult.Success)
        assertTrue(dest.readBytes().contentEquals(bytes))
        assertFalse(File(tmp.root, "in/vm_01.m4a.part").exists())
        assertEquals("/v1/groups/grp_1/voice/vm_01", server.takeRequest().path)

        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":{"code":"NOT_FOUND","message":"없음","traceId":"t"}}"""))
        val missing = client.download("grp_1", "vm_02", File(tmp.root, "in/vm_02.m4a"))
        assertTrue(missing is ApiResult.HttpFailure.NotFound)
        assertFalse(File(tmp.root, "in/vm_02.m4a").exists())
    }
}
