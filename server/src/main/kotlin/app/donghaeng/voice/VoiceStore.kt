package app.donghaeng.voice

import java.time.Instant

/** 저장된 무전 한 건의 메타데이터 (`voice_message`, docs/04 V6) — 오디오 바이트는 [StoredVoice] 에만 */
data class VoiceMeta(
    val voiceId: String,
    val groupId: String,
    val senderVehicleId: String,
    /** 이 무전을 알린 `VOICE_MESSAGE` 그룹 이벤트 */
    val eventId: String,
    val mime: String,
    val durationMs: Int,
    val sizeBytes: Int,
    val createdAt: Instant,
    val expiresAt: Instant,
)

/** 다운로드용 — 메타 + 오디오. (오디오 배열은 equals 비교 대상이 아니라 data class 로 두지 않는다) */
class StoredVoice(val meta: VoiceMeta, val audio: ByteArray)

/**
 * 무전 저장소 (구현 `repo/JdbcVoiceStore` — PostgreSQL `voice_message`). 서비스 단위 테스트는 메모리 구현으로 바꿔 끼운다.
 * 모든 메서드는 호출자의 트랜잭션에 참여한다.
 */
interface VoiceStore {

    /** 같은 차의 업로드를 직렬화한다(트랜잭션 끝에 자동 해제) — 빈도 제한 검사와 저장 사이에 다른 업로드가 끼지 않게 */
    fun lockSender(vehicleId: String)

    /** 그 차가 마지막으로 올린 시각(저장된 것 중). 없으면 null */
    fun lastCreatedAt(vehicleId: String): Instant?

    fun insert(meta: VoiceMeta, audio: ByteArray)

    /** 그룹·ID 가 맞고 [now] 에 아직 만료되지 않은 무전. 없으면 null */
    fun find(groupId: String, voiceId: String, now: Instant): StoredVoice?

    /** 그룹 종료 — 그 그룹 무전 전부. 지운 수 */
    fun deleteByGroup(groupId: String): Int

    /** 정기 정리 — [now] 까지 만료된 무전. 지운 수 */
    fun deleteExpired(now: Instant): Int
}
