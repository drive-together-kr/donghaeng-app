package app.donghaeng.voice.web

import app.donghaeng.protocol.rest.ApiHeaders
import app.donghaeng.protocol.rest.ApiPaths
import app.donghaeng.protocol.rest.VoiceUploadResponse
import app.donghaeng.voice.VoiceAudioPart
import app.donghaeng.voice.VoiceRules
import app.donghaeng.voice.VoiceService
import jakarta.servlet.http.Part
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * docs/03 §2.6 무전 API. JWT → 호출자(group 의 `GroupAccessApi` 계약 타입 `Caller`) 변환은 이 패키지의 [toCaller]/[requireVehicle]
 * (group 웹 계층의 내부 함수를 쓰지 않는다 — docs/06 §2). 역할 제한: `SecurityConfig` 가 업로드를 ROLE_VEHICLE 로 먼저 막고, 여기서 `typ` 을 한 번 더 본다.
 *
 * 파라미터는 일부러 선택(required = false)·문자열로 받는다 — 빠졌거나 정수가 아닌 값이 Spring 기본 오류(400 BAD_REQUEST·500)가 아니라
 * 계약의 400 `INVALID_VOICE` 가 되도록 서비스가 검사한다.
 * `audio` 는 `MultipartFile` 이 아니라 Servlet [Part] 로 받는다 — Spring 은 Content-Disposition 에 filename 이 없는 파트를 파일이 아닌 폼 필드로
 * 분류해 `MultipartFile` 이 null 이 되지만(계약은 filename 을 요구하지 않는다, OkHttp `createFormData(name, null, body)` 가 이 모양), `Part` 는 둘 다 같다.
 * 크기 상한(Spring `max-file-size` 512KB)은 Tomcat 이 filename 유무와 무관하게 파트마다 적용한다.
 */
@RestController
@RequestMapping(ApiPaths.GROUPS)
class VoiceController(private val voices: VoiceService) {

    /**
     * 차량 JWT, multipart(`audio` 파일 + `durationMs`) → 201 VoiceUploadResponse / 400·403·404·409·413·415·429.
     * 선택 헤더 `Idempotency-Key` — 같은 키의 재시도는 첫 201 응답을 그대로 받는다(다시 저장·팬아웃 안 함, 계약 3).
     */
    @PostMapping("/{groupId}/voice")
    @ResponseStatus(HttpStatus.CREATED)
    fun upload(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable groupId: String,
        @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) idempotencyKey: String?,
        @RequestParam("audio", required = false) audio: Part?,
        @RequestParam("durationMs", required = false) durationMs: String?,
    ): VoiceUploadResponse =
        voices.upload(jwt.requireVehicle(), groupId, audio?.let(::ServletPartAudio), durationMs, idempotencyKey)

    /** 멤버 차량 또는 멤버 차량 소유자 → 200 오디오 바이트(Content-Type = 올린 mime) / 404 */
    @GetMapping("/{groupId}/voice/{voiceId}")
    fun download(@AuthenticationPrincipal jwt: Jwt, @PathVariable groupId: String, @PathVariable voiceId: String): ResponseEntity<ByteArray> {
        val voice = voices.download(jwt.toCaller(), groupId, voiceId)
        // Content-Type 을 미리 정하면 Accept 협상을 건너뛴다 — 클라이언트가 audio/* 를 Accept 에 적지 않아도 된다.
        // 오류(404·403)는 GlobalExceptionHandler 가 JSON 을 미리 정하므로 Accept 가 audio/* 뿐이어도 그대로 내려간다
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(voice.meta.mime))
            .contentLength(voice.audio.size.toLong())
            .header(HttpHeaders.CACHE_CONTROL, VoiceRules.CACHE_CONTROL)
            .body(voice.audio)
    }

    private class ServletPartAudio(private val part: Part) : VoiceAudioPart {
        override val contentType: String? get() = part.contentType
        override val size: Long get() = part.size
        override fun bytes(): ByteArray = part.inputStream.use { it.readBytes() }
    }
}
