package app.donghaeng.voice

import app.donghaeng.KPostgresContainer
import app.donghaeng.KRedisContainer
import app.donghaeng.event.GroupEventRecorded
import app.donghaeng.protocol.DhJson
import app.donghaeng.protocol.GroupEvent
import app.donghaeng.protocol.GroupEventType
import app.donghaeng.protocol.Place
import app.donghaeng.protocol.VoiceMessagePayload
import app.donghaeng.protocol.rest.ApiPaths
import app.donghaeng.protocol.rest.DeviceApproveRequest
import app.donghaeng.protocol.rest.DeviceCodeRequest
import app.donghaeng.protocol.rest.DeviceCodeResponse
import app.donghaeng.protocol.rest.DeviceTokenRequest
import app.donghaeng.protocol.rest.DeviceTokenResponse
import app.donghaeng.protocol.rest.ErrorCodes
import app.donghaeng.protocol.rest.ErrorEnvelope
import app.donghaeng.protocol.rest.GroupCreateRequest
import app.donghaeng.protocol.rest.GroupCreateResponse
import app.donghaeng.protocol.rest.GroupDetailResponse
import app.donghaeng.protocol.rest.GroupJoinRequest
import app.donghaeng.protocol.rest.LoginRequest
import app.donghaeng.protocol.rest.TokenResponse
import app.donghaeng.protocol.rest.VoiceUploadResponse
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.event.EventListener
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.util.LinkedMultiValueMap
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 커밋 뒤 발행되는 그룹 이벤트를 모은다 — MQTT 팬아웃(`GroupEventMqttFanout`)과 같은 Spring 이벤트 */
class RecordingGroupEvents {
    val events = CopyOnWriteArrayList<GroupEventRecorded>()

    @EventListener
    fun on(e: GroupEventRecorded) { events += e }
}

@TestConfiguration
class VoiceItConfig {
    @Bean
    fun recordingGroupEvents() = RecordingGroupEvents()
}

/**
 * F16 무전 통합 테스트 (docs/03 §2.6 계약): 실제 PostgreSQL 16(Flyway V6 `voice_message`) + Redis 7 로 부팅해 HTTP multipart 로
 * 업로드 → `VOICE_MESSAGE` 그룹 이벤트(커밋 뒤 발행 = MQTT 팬아웃 입력) → 다운로드, 권한(비멤버 404·역할 403·401), 크기·길이·mime·빈도 오류,
 * 그룹 종료 시 삭제, 만료 숨김·정리를 확인한다. 실제 브로커 팬아웃은 `MqttIT` 가 본다. Docker 가 없으면 건너뛴다.
 * 정기 정리 스케줄러는 테스트 중 끼어들지 않게 첫 실행을 늦춘다(테스트가 [VoiceRetention.purgeExpired] 를 직접 부른다).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = ["dh.voice.purge-initial-delay-ms=3600000"])
@Testcontainers(disabledWithoutDocker = true)
@Import(VoiceItConfig::class)
class VoiceFlowIT {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = KPostgresContainer("postgres:16")

        @Container
        @ServiceConnection(name = "redis")
        @JvmStatic
        val redis = KRedisContainer(DockerImageName.parse("redis:7")).withExposedPorts(6379)

        private val seq = AtomicInteger()
        private val DEST = Place(lat = 37.2752, lon = 127.4425, name = "덕평휴게소")
        private val M4A = MediaType.parseMediaType("audio/mp4")
    }

    @Autowired
    lateinit var rest: TestRestTemplate

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Autowired
    lateinit var recorded: RecordingGroupEvents

    @Autowired
    lateinit var retention: VoiceRetention

    @Autowired
    lateinit var txManager: PlatformTransactionManager

    private val json = DhJson.json

    @BeforeEach
    fun useJdkHttpClient() {
        // GroupFlowIT 와 같은 이유(401 재시도 예외, 본문 없는 POST 의 form Content-Type)로 JDK HttpClient 사용
        rest.restTemplate.requestFactory = JdkClientHttpRequestFactory()
    }

    // ---------------------------------------------------------------- helpers

    private class Driver(val user: TokenResponse, val vehicle: DeviceTokenResponse) {
        val userToken get() = user.accessToken
        val vehicleToken get() = vehicle.vehicleAccessToken
        val vehicleId get() = vehicle.vehicle.vehicleId
    }

    private fun call(method: HttpMethod, path: String, body: String? = null, bearer: String? = null): ResponseEntity<String> {
        val headers = HttpHeaders().apply {
            accept = listOf(MediaType.APPLICATION_JSON)
            if (body != null) contentType = MediaType.APPLICATION_JSON
            if (bearer != null) setBearerAuth(bearer)
        }
        return rest.exchange(path, method, HttpEntity(body, headers), String::class.java)
    }

    private inline fun <reified T> ResponseEntity<String>.decode(): T = json.decodeFromString<T>(body ?: error("empty body"))

    private fun ResponseEntity<String>.assertOk(expected: Int = 200): ResponseEntity<String> {
        assertEquals(expected, statusCode.value(), "status for body=$body")
        return this
    }

    private fun assertError(status: Int, code: String, actualStatus: Int, body: String?) {
        assertEquals(status, actualStatus, "status for body=$body")
        val envelope = json.decodeFromString<ErrorEnvelope>(body ?: error("empty error body"))
        assertEquals(code, envelope.error.code, body)
        assertTrue(envelope.error.message.isNotBlank() && !envelope.error.traceId.isNullOrBlank(), body)
    }

    private fun ResponseEntity<String>.assertError(status: Int, code: String) = assertError(status, code, statusCode.value(), body)

    @JvmName("assertBytesError")
    private fun ResponseEntity<ByteArray>.assertError(status: Int, code: String) = assertError(status, code, statusCode.value(), body?.toString(Charsets.UTF_8))

    private fun driver(label: String, nickname: String): Driver {
        val n = seq.incrementAndGet()
        val user: TokenResponse = call(HttpMethod.POST, ApiPaths.AUTH_LOGIN, json.encodeToString(LoginRequest(email = "voice-$label-$n@example.com", displayName = "사용자 $label"))).assertOk().decode()
        val code: DeviceCodeResponse = call(HttpMethod.POST, ApiPaths.DEVICE_CODE, json.encodeToString(DeviceCodeRequest(vehicleId = "CAR-VOICE-IT-$n", brand = "KIA", model = "EV9"))).assertOk().decode()
        call(HttpMethod.POST, ApiPaths.DEVICE_APPROVE, json.encodeToString(DeviceApproveRequest(code.userCode, nickname)), user.accessToken).assertOk()
        val token: DeviceTokenResponse = call(HttpMethod.POST, ApiPaths.DEVICE_TOKEN, json.encodeToString(DeviceTokenRequest(code.deviceCode))).assertOk().decode()
        return Driver(user, token)
    }

    /** A 가 만든 그룹에 나머지가 합류 */
    private fun group(leader: Driver, vararg others: Driver): String {
        val created: GroupCreateResponse = call(HttpMethod.POST, ApiPaths.GROUPS, json.encodeToString(GroupCreateRequest("무전 테스트", DEST)), leader.vehicleToken).assertOk(201).decode()
        others.forEach { call(HttpMethod.POST, ApiPaths.GROUPS_JOIN, json.encodeToString(GroupJoinRequest(created.invite.token)), it.userToken).assertOk() }
        return created.groupId
    }

    private fun voicePath(groupId: String) = "${ApiPaths.group(groupId)}/voice"

    /**
     * multipart 업로드. [mime] 이 파트 Content-Type, [durationMs] 가 null 이면 필드를 빼고, [audio] 가 null 이면 파일 파트를 뺀다.
     */
    private fun upload(groupId: String, bearer: String?, audio: ByteArray? = sample(), mime: MediaType = M4A, durationMs: String? = "4200"): ResponseEntity<String> {
        val parts = LinkedMultiValueMap<String, Any>()
        if (audio != null) {
            val file = object : ByteArrayResource(audio) {
                override fun getFilename() = "ptt.m4a"
            }
            parts.add("audio", HttpEntity(file, HttpHeaders().apply { contentType = mime }))
        }
        if (durationMs != null) parts.add("durationMs", durationMs)
        if (parts.isEmpty()) parts.add("note", "empty")
        val headers = HttpHeaders().apply {
            contentType = MediaType.MULTIPART_FORM_DATA
            accept = listOf(MediaType.APPLICATION_JSON)
            if (bearer != null) setBearerAuth(bearer)
        }
        return rest.exchange(voicePath(groupId), HttpMethod.POST, HttpEntity(parts, headers), String::class.java)
    }

    private fun uploadOk(groupId: String, d: Driver, audio: ByteArray = sample(), mime: MediaType = M4A, durationMs: String = "4200"): VoiceUploadResponse =
        upload(groupId, d.vehicleToken, audio, mime, durationMs).assertOk(201).decode()

    private fun download(groupId: String, voiceId: String, bearer: String, accept: List<MediaType>? = null): ResponseEntity<ByteArray> {
        val headers = HttpHeaders().apply {
            setBearerAuth(bearer)
            if (accept != null) this.accept = accept
        }
        return rest.exchange("${voicePath(groupId)}/$voiceId", HttpMethod.GET, HttpEntity<Void>(headers), ByteArray::class.java)
    }

    /** 가짜 m4a 바이트 — 서버는 내용을 해석하지 않는다 */
    private fun sample(size: Int = 12_345): ByteArray = ByteArray(size) { (it * 31 % 256).toByte() }

    /** 빈도 제한(3초)을 기다리지 않도록 그 차의 마지막 업로드 시각을 과거로 민다 */
    private fun forgetRate(vehicleId: String) {
        jdbc.update("update voice_message set created_at = created_at - interval '10 seconds' where sender_vehicle_id = ?", vehicleId)
    }

    private fun voiceCount(groupId: String): Long =
        jdbc.queryForObject("select count(*) from voice_message where group_id = ?", Long::class.javaObjectType, groupId)!!

    // ---------------------------------------------------------------- tests

    @Test
    fun `upload fans out VOICE_MESSAGE to the whole group and members download the bytes`() {
        val a = driver("a", "아빠 차")
        val b = driver("b", "엄마 차")
        val outsider = driver("o", "남의 차")
        val gid = group(a, b)
        group(outsider) // 남은 다른 그룹에 있다

        val audio = sample(20_000)
        val before = System.currentTimeMillis()
        val res = uploadOk(gid, b, audio, durationMs = "7300")
        assertTrue(VoiceRules.isVoiceId(res.voiceId), res.voiceId)
        assertEquals(7_300, res.durationMs)
        assertTrue(res.expiresAtMs in (before + 24 * 3_600_000L - 5_000)..(System.currentTimeMillis() + 24 * 3_600_000L), "expiresAtMs=${res.expiresAtMs}")

        // 커밋 뒤 이벤트(= MQTT 팬아웃 입력): 그룹 전체(audience 없음), '{닉네임} 무전', payload 는 계약 그대로
        val event: GroupEvent = recorded.events.single { it.event.eventId == res.eventId }.also { assertEquals(gid, it.groupId) }.event
        assertEquals(GroupEventType.VOICE_MESSAGE.name, event.type)
        assertEquals(b.vehicleId, event.sourceVehicleId)
        assertEquals("엄마 차 무전", event.speech)
        assertNull(event.audience)
        val payload = DhJson.payloadAs(event.payload, VoiceMessagePayload.serializer())
        assertEquals(VoiceMessagePayload(res.voiceId, 7_300, "audio/mp4", audio.size, res.expiresAtMs), payload)
        // 그룹 조회의 최근 이벤트에도 같은 값
        val detail: GroupDetailResponse = call(HttpMethod.GET, ApiPaths.group(gid), bearer = a.userToken).assertOk().decode()
        assertEquals(event, detail.recentEvents.first())
        // DB: 메타 + 이벤트 연결
        val row = jdbc.queryForMap("select group_id, sender_vehicle_id, event_id, mime, duration_ms, size_bytes, octet_length(audio) as len from voice_message where voice_id = ?", res.voiceId)
        assertEquals(mapOf("group_id" to gid, "sender_vehicle_id" to b.vehicleId, "event_id" to res.eventId, "mime" to "audio/mp4", "duration_ms" to 7_300, "size_bytes" to audio.size, "len" to audio.size), row)

        // 다운로드: 다른 멤버 차·멤버 차 소유자·보낸 차 자신 200, 헤더는 계약 그대로
        for (bearer in listOf(a.vehicleToken, a.userToken, b.vehicleToken)) {
            val got = download(gid, res.voiceId, bearer)
            assertEquals(200, got.statusCode.value())
            assertContentEquals(audio, got.body)
            assertEquals(M4A, got.headers.contentType)
            assertEquals("private, max-age=86400", got.headers.cacheControl)
            assertEquals(audio.size.toLong(), got.headers.contentLength)
        }
        // Content-Type 을 서버가 정하므로 Accept 에 audio/* 가 없어도(JSON 만 적어도) 406 이 아니라 그대로 내려간다
        val jsonOnly = download(gid, res.voiceId, a.vehicleToken, accept = listOf(MediaType.APPLICATION_JSON))
        assertEquals(200, jsonOnly.statusCode.value())
        assertContentEquals(audio, jsonOnly.body)
        // 남·다른 그룹 경로·없는 무전·형식 오류는 404 (존재 숨김)
        download(gid, res.voiceId, outsider.vehicleToken).assertError(404, ErrorCodes.NOT_FOUND)
        download(gid, res.voiceId, outsider.userToken).assertError(404, ErrorCodes.NOT_FOUND)
        download("grp_NOPE", res.voiceId, a.vehicleToken).assertError(404, ErrorCodes.NOT_FOUND)
        download(gid, "vce_" + "0".repeat(26), a.vehicleToken).assertError(404, ErrorCodes.NOT_FOUND)
        download(gid, "not-a-voice", a.vehicleToken).assertError(404, ErrorCodes.NOT_FOUND)
        // 비멤버 차량 업로드도 404, 없는 그룹 404
        upload(gid, outsider.vehicleToken).assertError(404, ErrorCodes.NOT_FOUND)
        upload("grp_NOPE", a.vehicleToken).assertError(404, ErrorCodes.NOT_FOUND)

        // 나간 멤버는 더 못 받는다
        call(HttpMethod.POST, ApiPaths.groupLeave(gid), bearer = b.vehicleToken).assertOk(204)
        download(gid, res.voiceId, b.vehicleToken).assertError(404, ErrorCodes.NOT_FOUND)
        download(gid, res.voiceId, a.vehicleToken).assertOk200()
    }

    private fun ResponseEntity<ByteArray>.assertOk200() = assertEquals(200, statusCode.value(), body?.toString(Charsets.UTF_8))

    @Test
    fun `validation - roles, size, duration, mime and missing parts map to the contract codes`() {
        val a = driver("va", "아빠 차")
        val b = driver("vb", "엄마 차")
        val gid = group(a, b)

        // 인증·역할: 무토큰 401, 사용자 토큰 403 (업로드는 차량만)
        upload(gid, null).assertError(401, ErrorCodes.UNAUTHORIZED)
        upload(gid, a.userToken).assertError(403, ErrorCodes.FORBIDDEN)

        // 크기: 정확히 512 KB 는 통과, 1 바이트 넘으면 413 (Spring multipart 상한 → GlobalExceptionHandler)
        val atLimit = uploadOk(gid, a, sample(VoiceRules.MAX_BYTES))
        assertEquals(VoiceRules.MAX_BYTES, jdbc.queryForObject("select size_bytes from voice_message where voice_id = ?", Int::class.javaObjectType, atLimit.voiceId))
        forgetRate(a.vehicleId)
        upload(gid, a.vehicleToken, sample(VoiceRules.MAX_BYTES + 1)).assertError(413, ErrorCodes.VOICE_TOO_LARGE)
        upload(gid, a.vehicleToken, sample(800 * 1024)).assertError(413, ErrorCodes.VOICE_TOO_LARGE)

        // 길이: 1~16000 정수만
        for (bad in listOf("0", "16001", "-1", "abc", "1.5", "")) {
            upload(gid, a.vehicleToken, durationMs = bad).assertError(400, ErrorCodes.INVALID_VOICE)
        }
        upload(gid, a.vehicleToken, durationMs = null).assertError(400, ErrorCodes.INVALID_VOICE)

        // mime: 세 값만 (매개변수는 무시하고 기본형으로 저장)
        upload(gid, a.vehicleToken, mime = MediaType.parseMediaType("audio/ogg")).assertError(415, ErrorCodes.UNSUPPORTED_VOICE_TYPE)
        upload(gid, a.vehicleToken, mime = MediaType.APPLICATION_OCTET_STREAM).assertError(415, ErrorCodes.UNSUPPORTED_VOICE_TYPE)
        upload(gid, a.vehicleToken, mime = MediaType.parseMediaType("audio/x-wav")).assertError(415, ErrorCodes.UNSUPPORTED_VOICE_TYPE)

        // 파일 없음·빈 파일·multipart 아님 → 400 INVALID_VOICE
        upload(gid, a.vehicleToken, audio = null).assertError(400, ErrorCodes.INVALID_VOICE)
        upload(gid, a.vehicleToken, audio = ByteArray(0)).assertError(400, ErrorCodes.INVALID_VOICE)
        call(HttpMethod.POST, voicePath(gid), """{"durationMs":1000}""", a.vehicleToken).assertError(400, ErrorCodes.INVALID_VOICE)
        // 잘린 multipart(닫는 경계 없음) — 해석 실패(MultipartException)도 500 이 아니라 400 INVALID_VOICE
        val truncated = listOf(
            "--zzz",
            "Content-Disposition: form-data; name=\"audio\"; filename=\"a.m4a\"",
            "Content-Type: audio/mp4",
            "",
            "abc",
        ).joinToString("\r\n")
        val truncatedHeaders = HttpHeaders().apply {
            contentType = MediaType.parseMediaType("multipart/form-data; boundary=zzz")
            accept = listOf(MediaType.APPLICATION_JSON)
            setBearerAuth(a.vehicleToken)
        }
        rest.exchange(voicePath(gid), HttpMethod.POST, HttpEntity(truncated, truncatedHeaders), String::class.java).assertError(400, ErrorCodes.INVALID_VOICE)

        // 거절된 시도는 빈도 제한에 들어가지 않는다 — 바로 다음 정상 업로드는 통과(경계값 16000 ms, 매개변수 붙은 mime)
        val withParams = uploadOk(gid, a, mime = MediaType.parseMediaType("audio/mp4;codecs=mp4a.40.2"), durationMs = "16000")
        assertEquals("audio/mp4", jdbc.queryForObject("select mime from voice_message where voice_id = ?", String::class.java, withParams.voiceId))
        val wav = uploadOk(gid, b, mime = MediaType.parseMediaType("audio/wav"), durationMs = "1")
        assertEquals(MediaType.parseMediaType("audio/wav"), download(gid, wav.voiceId, a.vehicleToken).headers.contentType)
        forgetRate(b.vehicleId)
        val aac = uploadOk(gid, b, mime = MediaType.parseMediaType("audio/aac"))
        assertEquals(MediaType.parseMediaType("audio/aac"), download(gid, aac.voiceId, a.vehicleToken).headers.contentType)
        assertEquals(4, voiceCount(gid))
    }

    @Test
    fun `rate limit - second upload from the same car within 3 s is 429, other cars are independent`() {
        val a = driver("ra", "아빠 차")
        val b = driver("rb", "엄마 차")
        val gid = group(a, b)

        val first = uploadOk(gid, a)
        upload(gid, a.vehicleToken).assertError(429, ErrorCodes.VOICE_RATE_LIMITED)
        uploadOk(gid, b) // 다른 차는 별개
        assertEquals(2, voiceCount(gid))
        assertEquals(1, recorded.events.count { it.groupId == gid && it.event.type == GroupEventType.VOICE_MESSAGE.name && it.event.sourceVehicleId == a.vehicleId }, "429 는 이벤트를 남기지 않는다")
        // 3초가 지나면(= 마지막 업로드를 과거로) 다시 보낼 수 있다
        forgetRate(a.vehicleId)
        val second = uploadOk(gid, a)
        assertTrue(second.voiceId != first.voiceId)
    }

    @Test
    fun `group end deletes its voices at once, later uploads are 409 and downloads 404`() {
        val a = driver("ea", "아빠 차")
        val b = driver("eb", "엄마 차")
        val c = driver("ec", "삼촌 차")
        val gid = group(a, b)
        val other = group(c) // 다른 그룹 무전은 남아야 한다
        val keep = uploadOk(other, c)
        val v1 = uploadOk(gid, a)
        val v2 = uploadOk(gid, b)
        assertEquals(2, voiceCount(gid))

        call(HttpMethod.POST, ApiPaths.groupEnd(gid), bearer = a.vehicleToken).assertOk(204)
        // GROUP_ENDED 커밋 뒤 같은 요청 스레드에서 지운다 → 응답을 받은 시점에 이미 0
        assertEquals(0, voiceCount(gid))
        assertEquals(1, voiceCount(other))
        download(gid, v1.voiceId, b.vehicleToken).assertError(404, ErrorCodes.NOT_FOUND)
        download(gid, v2.voiceId, a.userToken).assertError(404, ErrorCodes.NOT_FOUND)
        forgetRate(b.vehicleId)
        upload(gid, b.vehicleToken).assertError(409, ErrorCodes.GROUP_ENDED)
        download(other, keep.voiceId, c.vehicleToken).assertOk200()

        // 마지막 멤버가 나가 끝나는 경우(ALL_LEFT)도 같다
        assertEquals(1, voiceCount(other))
        call(HttpMethod.POST, ApiPaths.groupLeave(other), bearer = c.vehicleToken).assertOk(204)
        assertEquals(0, voiceCount(other))
    }

    @Test
    fun `an upload racing a group end waits for the group row and is refused - no voice left in an ended group`() {
        val a = driver("ra1", "아빠 차")
        val b = driver("rb1", "엄마 차")
        val gid = group(a, b)
        val pool = Executors.newSingleThreadExecutor()
        try {
            // 종료 트랜잭션 흉내: 그룹 행 FOR UPDATE 를 잡은 채로 업로드를 보낸다 → 업로드의 FOR SHARE 가 기다린다
            val pending = TransactionTemplate(txManager).execute {
                jdbc.queryForMap("select status from travel_group where group_id = ? for update", gid)
                val inFlight = pool.submit<ResponseEntity<String>> { upload(gid, b.vehicleToken) }
                Thread.sleep(700)
                assertFalse(inFlight.isDone, "upload must wait for the group row lock")
                jdbc.update("update travel_group set status = 'ENDED', ended_at = now() where group_id = ?", gid)
                inFlight
            }!!
            // 잠금 없이 읽었다면 ACTIVE 로 보고 201 이 됐을 것(FK 검사만 기다림) — FOR SHARE 라 커밋된 ENDED 를 본다
            pending.get(20, TimeUnit.SECONDS).assertError(409, ErrorCodes.GROUP_ENDED)
            assertEquals(0, voiceCount(gid))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `expired voices are hidden at once and purged by the retention job`() {
        val a = driver("xa", "아빠 차")
        val b = driver("xb", "엄마 차")
        val gid = group(a, b)
        val old = uploadOk(gid, a)
        val fresh = uploadOk(gid, b)
        jdbc.update("update voice_message set expires_at = now() - interval '1 second' where voice_id = ?", old.voiceId)

        download(gid, old.voiceId, b.vehicleToken).assertError(404, ErrorCodes.NOT_FOUND)
        download(gid, fresh.voiceId, a.vehicleToken).assertOk200()

        assertTrue(retention.purgeExpired() >= 1)
        assertEquals(0, jdbc.queryForObject("select count(*) from voice_message where voice_id = ?", Long::class.javaObjectType, old.voiceId))
        assertEquals(1, jdbc.queryForObject("select count(*) from voice_message where voice_id = ?", Long::class.javaObjectType, fresh.voiceId))
        assertEquals(0, jdbc.queryForObject("select count(*) from voice_message where expires_at <= now()", Long::class.javaObjectType))
    }

    @Test
    fun `flyway V6 created voice_message with external audio storage and lookup indexes`() {
        val indexes = jdbc.queryForList("select indexname from pg_indexes where tablename = 'voice_message'", String::class.java).toSet()
        assertTrue(indexes.containsAll(setOf("idx_voice_message_group_id", "idx_voice_message_expires_at", "idx_voice_message_sender_created")), indexes.toString())
        val storage = jdbc.queryForObject(
            "select attstorage from pg_attribute where attrelid = 'voice_message'::regclass and attname = 'audio'",
            String::class.java,
        )
        assertEquals("e", storage, "audio = STORAGE EXTERNAL")
    }
}
