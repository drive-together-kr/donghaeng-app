package app.donghaeng.event.repo

import app.donghaeng.common.jdbc.toDb
import app.donghaeng.protocol.DhJson
import app.donghaeng.protocol.GroupEvent
import app.donghaeng.protocol.Severity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant

/** `group_event` (docs/04 §1 + V3 `speech`). payload 는 JSONB. 24시간 보관 후 배치 삭제(추후 스케줄러). */
@Repository
class GroupEventRepository(private val jdbc: NamedParameterJdbcTemplate) {

    fun insert(groupId: String, event: GroupEvent, occurredAt: Instant) {
        jdbc.update(
            """
            INSERT INTO group_event (event_id, group_id, type, source_vehicle_id, severity, speech, payload, occurred_at)
            VALUES (:eventId, :groupId, :type, :sourceVehicleId, :severity, :speech, CAST(:payload AS jsonb), :occurredAt)
            """.trimIndent(),
            mapOf(
                "eventId" to event.eventId,
                "groupId" to groupId,
                "type" to event.type,
                "sourceVehicleId" to event.sourceVehicleId,
                "severity" to event.severity.name,
                "speech" to event.speech,
                "payload" to DhJson.json.encodeToString(JsonObject.serializer(), event.payload),
                "occurredAt" to occurredAt.toDb(),
            ),
        )
    }

    /** 최신순 */
    fun listRecent(groupId: String, limit: Int): List<GroupEvent> =
        jdbc.query(
            """
            SELECT event_id, type, source_vehicle_id, severity, speech, payload::text AS payload
              FROM group_event
             WHERE group_id = :groupId
             ORDER BY occurred_at DESC, event_id DESC
             LIMIT :limit
            """.trimIndent(),
            mapOf("groupId" to groupId, "limit" to limit),
            ::mapRow,
        )

    fun deleteByGroup(groupId: String): Int =
        jdbc.update("DELETE FROM group_event WHERE group_id = :groupId", mapOf("groupId" to groupId))

    private fun mapRow(rs: ResultSet, @Suppress("UNUSED_PARAMETER") rowNum: Int) = GroupEvent(
        eventId = rs.getString("event_id"),
        type = rs.getString("type"),
        sourceVehicleId = rs.getString("source_vehicle_id"),
        severity = Severity.valueOf(rs.getString("severity")),
        speech = rs.getString("speech"),
        payload = DhJson.json.parseToJsonElement(rs.getString("payload")).jsonObject,
    )
}
