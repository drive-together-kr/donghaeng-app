package app.donghaeng.event.repo

import app.donghaeng.common.jdbc.instant
import app.donghaeng.common.jdbc.toDb
import app.donghaeng.protocol.DhJson
import app.donghaeng.protocol.GroupEvent
import app.donghaeng.protocol.Severity
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.springframework.jdbc.core.SqlParameterValue
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Types
import java.time.Instant

/**
 * `group_event` (docs/04 §1 + V3 `speech` + V5 `audience`). payload·audience 는 JSONB. 24시간 보관 후 배치 삭제(`EventRetention`, 1시간 주기),
 * 그룹이 끝나면 위치성 payload 를 바로 지운다([scrubLocations]).
 * audience 는 NULL(그룹 전체)과 `[]`(아무도 아님)을 구분해 그대로 저장·복원한다 — `GET /groups/{id}` 의 recentEvents 가 MQTT 와 같은 값을 돌려주게.
 */
@Repository
class GroupEventRepository(private val jdbc: NamedParameterJdbcTemplate) {

    fun insert(groupId: String, event: GroupEvent, occurredAt: Instant) {
        jdbc.update(
            """
            INSERT INTO group_event (event_id, group_id, type, source_vehicle_id, severity, speech, payload, audience, occurred_at)
            VALUES (:eventId, :groupId, :type, :sourceVehicleId, :severity, :speech, CAST(:payload AS jsonb), CAST(:audience AS jsonb), :occurredAt)
            """.trimIndent(),
            mapOf(
                "eventId" to event.eventId,
                "groupId" to groupId,
                "type" to event.type,
                "sourceVehicleId" to event.sourceVehicleId,
                "severity" to event.severity.name,
                "speech" to event.speech,
                "payload" to DhJson.json.encodeToString(JsonObject.serializer(), event.payload),
                // NULL 도 문자열 타입으로 넘겨 CAST 가 타입을 추론하게 한다
                "audience" to SqlParameterValue(Types.VARCHAR, event.audience?.let { DhJson.json.encodeToString(AUDIENCE, it) }),
                "occurredAt" to occurredAt.toDb(),
            ),
        )
    }

    /** 최신순 */
    fun listRecent(groupId: String, limit: Int): List<GroupEvent> =
        jdbc.query(
            """
            SELECT event_id, type, source_vehicle_id, severity, speech, payload::text AS payload, audience::text AS audience
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

    /** 그룹의 [type] 이벤트 가운데 가장 최근 시각 — 동승자 메시지 빈도 제한(C106). 인덱스 (group_id, occurred_at DESC) */
    fun lastOccurredAt(groupId: String, type: String): Instant? =
        jdbc.query(
            "SELECT occurred_at FROM group_event WHERE group_id = :groupId AND type = :type ORDER BY occurred_at DESC LIMIT 1",
            mapOf("groupId" to groupId, "type" to type),
        ) { rs, _ -> rs.instant("occurred_at") }.firstOrNull()

    /** 24시간 보관 정리(C033) — [before] 보다 오래된 이벤트. 지운 수. 인덱스 `idx_group_event_occurred_at`(V7) */
    fun deleteOlderThan(before: Instant): Int =
        jdbc.update("DELETE FROM group_event WHERE occurred_at < :before", mapOf("before" to before.toDb()))

    /**
     * 그룹 종료 시 위치성 payload 정리(PRD §7 '도착·경유지: 그룹 종료 시 삭제', C091): `WAYPOINT_ARRIVED` 의 좌표(`lat`·`lon`)와
     * `ROUTE_CHANGED` 의 경로(`destination`·`waypoints`)를 지운다. 행·문구(speech)는 24시간 보관 규칙대로 남는다(종료 시점 멤버의 피드). 바꾼 행 수
     */
    fun scrubLocations(groupId: String): Int =
        jdbc.update(
            """
            UPDATE group_event
               SET payload = CASE type
                     WHEN 'WAYPOINT_ARRIVED' THEN payload - 'lat' - 'lon'
                     ELSE payload - 'destination' - 'waypoints'
                   END
             WHERE group_id = :groupId AND type IN ('WAYPOINT_ARRIVED', 'ROUTE_CHANGED')
            """.trimIndent(),
            mapOf("groupId" to groupId),
        )

    private fun mapRow(rs: ResultSet, @Suppress("UNUSED_PARAMETER") rowNum: Int) = GroupEvent(
        eventId = rs.getString("event_id"),
        type = rs.getString("type"),
        sourceVehicleId = rs.getString("source_vehicle_id"),
        severity = Severity.valueOf(rs.getString("severity")),
        speech = rs.getString("speech"),
        payload = DhJson.json.parseToJsonElement(rs.getString("payload")).jsonObject,
        audience = rs.getString("audience")?.let { DhJson.json.decodeFromString(AUDIENCE, it) },
    )

    private companion object {
        val AUDIENCE = ListSerializer(String.serializer())
    }
}
