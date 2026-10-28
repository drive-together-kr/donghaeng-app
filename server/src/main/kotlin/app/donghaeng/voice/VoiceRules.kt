package app.donghaeng.voice

import app.donghaeng.common.id.Ids
import java.time.Duration

/**
 * F16 무전 업로드·보관 규칙 (docs/03 §2.6 계약) — 순수 함수라 단위 테스트로 고정한다.
 *
 * 서버는 오디오 내용을 해석하지 않는다(음성 인식·변환·코덱 검사 없음). 형식은 파트의 `Content-Type` 만 본다.
 */
object VoiceRules {

    /** 오디오 크기 상한 512 KB(= 524 288 바이트). Spring multipart `max-file-size`(application.yml)도 같은 값이다 */
    const val MAX_BYTES: Int = 512 * 1024

    /** 녹음 길이(ms) 허용 범위 — 차량은 최대 15 s 녹음, 1 s 여유 */
    const val MIN_DURATION_MS: Int = 1
    const val MAX_DURATION_MS: Int = 16_000

    /** 같은 차의 연속 업로드 최소 간격 (아니면 429 `VOICE_RATE_LIMITED`) */
    val RATE_WINDOW: Duration = Duration.ofSeconds(3)

    /** 보관 기간 — 업로드 + 24시간(`expiresAtMs`). 그룹이 끝나면 그 전에 지운다 */
    val RETENTION: Duration = Duration.ofHours(24)

    /** 다운로드 응답 `Cache-Control` (docs/03 §2.6) */
    const val CACHE_CONTROL: String = "private, max-age=86400"

    /** 허용 mime — `audio/mp4`(AAC-LC m4a 16 kHz 모노, 기본) · `audio/aac` · `audio/wav`(디버그 주입) */
    val ALLOWED_MIME: Set<String> = linkedSetOf("audio/mp4", "audio/aac", "audio/wav")

    private val VOICE_ID = Regex("^${Ids.VOICE_PREFIX}[0-9A-HJKMNP-TV-Z]{26}$")

    /**
     * 파트 `Content-Type` → 저장할 mime. 매개변수(`; codecs=…`)·대소문자·앞뒤 공백은 무시하고, 목록 밖이거나 없으면 null(→ 415).
     * 별칭(`audio/x-wav`, `audio/m4a` 등)은 받지 않는다 — 계약의 세 값만.
     */
    fun normalizeMime(contentType: String?): String? {
        val base = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return null
        return base.takeIf { it in ALLOWED_MIME }
    }

    /** 폼 필드 `durationMs` → 1~16 000 정수. 없음·정수 아님·범위 밖이면 null(→ 400 `INVALID_VOICE`) */
    fun parseDurationMs(raw: String?): Int? {
        val value = raw?.trim()?.toIntOrNull() ?: return null
        return value.takeIf { it in MIN_DURATION_MS..MAX_DURATION_MS }
    }

    /** 다운로드 경로의 `{voiceId}` 가 우리가 만든 형식(`vce_` + ULID 26자)인가 — 아니면 DB 를 보지 않고 404 */
    fun isVoiceId(raw: String): Boolean = VOICE_ID.matches(raw)
}
