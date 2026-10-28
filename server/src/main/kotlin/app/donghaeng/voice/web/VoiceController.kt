package app.donghaeng.voice.web

import app.donghaeng.group.web.requireVehicle
import app.donghaeng.group.web.toCaller
import app.donghaeng.protocol.rest.ApiPaths
import app.donghaeng.protocol.rest.VoiceUploadResponse
import app.donghaeng.voice.VoiceAudioPart
import app.donghaeng.voice.VoiceRules
import app.donghaeng.voice.VoiceService
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

/**
 * docs/03 §2.6 무전 API. JWT → 호출자 변환은 그룹 API 와 같은 `group.web` 의 `toCaller`/`requireVehicle` 을 쓴다
 * (역할 제한: `SecurityConfig` 가 업로드를 ROLE_VEHICLE 로 먼저 막고, 여기서 `typ` 을 한 번 더 본다).
 *
 * 파라미터는 일부러 선택(required = false)·문자열로 받는다 — 빠졌거나 정수가 아닌 값이 Spring 기본 오류(400 BAD_REQUEST·500)가 아니라
 * 계약의 400 `INVALID_VOICE` 가 되도록 서비스가 검사한다.
 */
@RestController
@RequestMapping(ApiPaths.GROUPS)
class VoiceController(private val voices: VoiceService) {

    /** 차량 JWT, multipart(`audio` 파일 + `durationMs`) → 201 VoiceUploadResponse / 400·403·404·409·413·415·429 */
    @PostMapping("/{groupId}/voice")
    @ResponseStatus(HttpStatus.CREATED)
    fun upload(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable groupId: String,
        @RequestParam("audio", required = false) audio: MultipartFile?,
        @RequestParam("durationMs", required = false) durationMs: String?,
    ): VoiceUploadResponse =
        voices.upload(jwt.requireVehicle(), groupId, audio?.let(::MultipartAudio), durationMs)

    /** 멤버 차량 또는 멤버 차량 소유자 → 200 오디오 바이트(Content-Type = 올린 mime) / 404 */
    @GetMapping("/{groupId}/voice/{voiceId}")
    fun download(@AuthenticationPrincipal jwt: Jwt, @PathVariable groupId: String, @PathVariable voiceId: String): ResponseEntity<ByteArray> {
        val voice = voices.download(jwt.toCaller(), groupId, voiceId)
        // Content-Type 을 미리 정하면 Accept 협상을 건너뛴다 — 클라이언트가 audio/* 를 Accept 에 적지 않아도 된다
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(voice.meta.mime))
            .contentLength(voice.audio.size.toLong())
            .header(HttpHeaders.CACHE_CONTROL, VoiceRules.CACHE_CONTROL)
            .body(voice.audio)
    }

    private class MultipartAudio(private val file: MultipartFile) : VoiceAudioPart {
        override val contentType: String? get() = file.contentType
        override val size: Long get() = file.size
        override fun bytes(): ByteArray = file.bytes
    }
}
