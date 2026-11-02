package app.donghaeng.voice.repo

import app.donghaeng.common.jdbc.requireInstant
import app.donghaeng.common.jdbc.toDb
import app.donghaeng.voice.StoredVoice
import app.donghaeng.voice.VoiceMeta
import app.donghaeng.voice.VoiceStore
import org.springframework.jdbc.core.SqlParameterValue
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Types
import java.time.Instant
import java.time.OffsetDateTime

/**
 * `voice_message` (docs/04 V6). 오디오는 BYTEA(최대 512 KB, 압축 시도 없이 바깥 저장 — `STORAGE EXTERNAL`).
 * 그룹당 수십 건·24시간 보관이라 별도 객체 저장소 없이 PostgreSQL 에 둔다(운영 분리 시 이 구현만 S3 류로 바꾼다).
 */
@Repository
class JdbcVoiceStore(private val jdbc: NamedParameterJdbcTemplate) : VoiceStore {

    override fun lockSender(vehicleId: String) {
        // 두 키(int, int) 형식은 한 키(bigint) 형식과 키 공간이 겹치지 않는다(PostgreSQL 문서) — group 의 차량 잠금(hashtext 한 키)과 섞이지 않게
        jdbc.queryForObject(
            "SELECT pg_advisory_xact_lock(:ns, hashtext(:vehicleId))",
            mapOf("ns" to LOCK_NAMESPACE, "vehicleId" to vehicleId),
            Any::class.java,
        )
    }

    override fun lastCreatedAt(vehicleId: String): Instant? =
        jdbc.queryForObject(
            "SELECT max(created_at) FROM voice_message WHERE sender_vehicle_id = :vehicleId",
            mapOf("vehicleId" to vehicleId),
            OffsetDateTime::class.java,
        )?.toInstant()

    override fun countSince(vehicleId: String, since: Instant): Int =
        jdbc.queryForObject(
            "SELECT count(*) FROM voice_message WHERE sender_vehicle_id = :vehicleId AND created_at > :since",
            mapOf("vehicleId" to vehicleId, "since" to since.toDb()),
            Int::class.javaObjectType,
        ) ?: 0

    override fun activeBytesOfGroup(groupId: String, now: Instant): Long =
        jdbc.queryForObject(
            "SELECT coalesce(sum(size_bytes), 0) FROM voice_message WHERE group_id = :groupId AND expires_at > :now",
            mapOf("groupId" to groupId, "now" to now.toDb()),
            Long::class.javaObjectType,
        ) ?: 0L

    override fun insert(meta: VoiceMeta, audio: ByteArray) {
        jdbc.update(
            """
            INSERT INTO voice_message (voice_id, group_id, sender_vehicle_id, event_id, mime, duration_ms, size_bytes, audio, created_at, expires_at)
            VALUES (:voiceId, :groupId, :sender, :eventId, :mime, :durationMs, :sizeBytes, :audio, :createdAt, :expiresAt)
            """.trimIndent(),
            mapOf(
                "voiceId" to meta.voiceId,
                "groupId" to meta.groupId,
                "sender" to meta.senderVehicleId,
                "eventId" to meta.eventId,
                "mime" to meta.mime,
                "durationMs" to meta.durationMs,
                "sizeBytes" to meta.sizeBytes,
                "audio" to SqlParameterValue(Types.BINARY, audio),
                "createdAt" to meta.createdAt.toDb(),
                "expiresAt" to meta.expiresAt.toDb(),
            ),
        )
    }

    override fun find(groupId: String, voiceId: String, now: Instant): StoredVoice? =
        jdbc.query(
            """
            SELECT $META_COLUMNS, audio
              FROM voice_message
             WHERE voice_id = :voiceId AND group_id = :groupId AND expires_at > :now
            """.trimIndent(),
            mapOf("voiceId" to voiceId, "groupId" to groupId, "now" to now.toDb()),
        ) { rs, _ -> StoredVoice(mapMeta(rs), rs.getBytes("audio")) }.firstOrNull()

    override fun deleteByGroup(groupId: String): Int =
        jdbc.update("DELETE FROM voice_message WHERE group_id = :groupId", mapOf("groupId" to groupId))

    override fun deleteExpired(now: Instant): Int =
        jdbc.update("DELETE FROM voice_message WHERE expires_at <= :now", mapOf("now" to now.toDb()))

    private fun mapMeta(rs: ResultSet) = VoiceMeta(
        voiceId = rs.getString("voice_id"),
        groupId = rs.getString("group_id"),
        senderVehicleId = rs.getString("sender_vehicle_id"),
        eventId = rs.getString("event_id"),
        mime = rs.getString("mime"),
        durationMs = rs.getInt("duration_ms"),
        sizeBytes = rs.getInt("size_bytes"),
        createdAt = rs.requireInstant("created_at"),
        expiresAt = rs.requireInstant("expires_at"),
    )

    internal companion object {
        /** advisory lock 두 키 형식의 첫 키 — 무전 업로드(F16) 전용 (`VoiceFlowIT` 가 같은 잠금으로 업로드를 세워 경합을 재현한다) */
        const val LOCK_NAMESPACE = 1616
        private const val META_COLUMNS = "voice_id, group_id, sender_vehicle_id, event_id, mime, duration_ms, size_bytes, created_at, expires_at"
    }
}
