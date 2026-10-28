package app.donghaeng.voice

import app.donghaeng.common.id.Ids
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** docs/03 §2.6 계약 값 고정 — 크기·길이·mime·빈도·보관·ID 형식 */
class VoiceRulesTest {

    @Test
    fun `계약 상수`() {
        assertEquals(524_288, VoiceRules.MAX_BYTES, "512 KB")
        assertEquals(1, VoiceRules.MIN_DURATION_MS)
        assertEquals(16_000, VoiceRules.MAX_DURATION_MS)
        assertEquals(Duration.ofSeconds(3), VoiceRules.RATE_WINDOW)
        assertEquals(Duration.ofHours(24), VoiceRules.RETENTION)
        assertEquals("private, max-age=86400", VoiceRules.CACHE_CONTROL)
        assertEquals(setOf("audio/mp4", "audio/aac", "audio/wav"), VoiceRules.ALLOWED_MIME)
    }

    @Test
    fun `mime — 세 값만, 매개변수·대소문자·공백 무시`() {
        assertEquals("audio/mp4", VoiceRules.normalizeMime("audio/mp4"))
        assertEquals("audio/mp4", VoiceRules.normalizeMime("AUDIO/MP4"))
        assertEquals("audio/mp4", VoiceRules.normalizeMime(" audio/mp4 ; codecs=mp4a.40.2"))
        assertEquals("audio/aac", VoiceRules.normalizeMime("audio/aac"))
        assertEquals("audio/wav", VoiceRules.normalizeMime("audio/wav"))
        // 별칭·다른 형식·없음은 415
        assertNull(VoiceRules.normalizeMime("audio/x-wav"))
        assertNull(VoiceRules.normalizeMime("audio/m4a"))
        assertNull(VoiceRules.normalizeMime("audio/ogg"))
        assertNull(VoiceRules.normalizeMime("application/octet-stream"))
        assertNull(VoiceRules.normalizeMime("video/mp4"))
        assertNull(VoiceRules.normalizeMime(""))
        assertNull(VoiceRules.normalizeMime(null))
    }

    @Test
    fun `durationMs — 정수 1~16000 만`() {
        assertEquals(1, VoiceRules.parseDurationMs("1"))
        assertEquals(7_000, VoiceRules.parseDurationMs(" 7000 "))
        assertEquals(16_000, VoiceRules.parseDurationMs("16000"))
        assertNull(VoiceRules.parseDurationMs("0"))
        assertNull(VoiceRules.parseDurationMs("-5"))
        assertNull(VoiceRules.parseDurationMs("16001"))
        assertNull(VoiceRules.parseDurationMs("7000.5"))
        assertNull(VoiceRules.parseDurationMs("abc"))
        assertNull(VoiceRules.parseDurationMs("99999999999"))
        assertNull(VoiceRules.parseDurationMs(""))
        assertNull(VoiceRules.parseDurationMs(null))
    }

    @Test
    fun `voiceId 형식 — vce_ + ULID`() {
        val id = Ids.voice()
        assertTrue(id.startsWith("vce_"), id)
        assertTrue(VoiceRules.isVoiceId(id), id)
        assertFalse(VoiceRules.isVoiceId("vce_"))
        assertFalse(VoiceRules.isVoiceId("evt_" + id.removePrefix("vce_")))
        assertFalse(VoiceRules.isVoiceId(id.lowercase()))
        assertFalse(VoiceRules.isVoiceId(id + "0"))
        assertFalse(VoiceRules.isVoiceId("vce_" + "I".repeat(26)), "ULID 알파벳에 없는 글자")
        assertFalse(VoiceRules.isVoiceId("../etc/passwd"))
    }
}
