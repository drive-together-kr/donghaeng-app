package app.donghaeng.event

import app.donghaeng.common.id.Ids
import app.donghaeng.common.tx.afterCommit
import app.donghaeng.event.repo.GroupEventRepository
import app.donghaeng.protocol.GroupEvent
import app.donghaeng.protocol.GroupEventType
import app.donghaeng.protocol.Severity
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant

/**
 * 그룹 이벤트를 남기는 창구 (docs/06 §2 `event` 패키지의 `GroupEventPublisher` 자리).
 * group 모듈이 합류·나가기·종료·동승자 메시지를 여기로 보내고, T15 부터는 저장 뒤 MQTT `dh/v1/group/{gid}/event` 로도 팬아웃된다.
 */
interface GroupEventSink {

    /**
     * 이벤트를 저장하고(현재 트랜잭션 안) 돌려준다. 커밋되면 [GroupEventRecorded] 가 발행된다.
     *
     * @param speech 음성 문구. 음성 생략(중복 억제)이면 null
     * @param payload docs/03 §5.4 `payload` — 차량 이벤트면 §5.2 의 data 그대로
     * @param audience docs/03 §5.4 `audience` — null = 그룹 전체, 목록 = 그 차량만(빈 목록 = 아무도 알리지 않음). 저장·REST·MQTT 모두 같은 값
     */
    fun record(
        groupId: String,
        type: GroupEventType,
        sourceVehicleId: String?,
        severity: Severity,
        speech: String?,
        payload: JsonObject,
        audience: List<String>? = null,
    ): GroupEvent
}

/** 저장된 그룹 이벤트 조회 (`GET /groups/{id}` 의 recentEvents) */
interface GroupEventReader {

    /** 최신순, 최대 [limit] 개 */
    fun recent(groupId: String, limit: Int = 20): List<GroupEvent>

    /** 그룹의 [type] 이벤트 가운데 가장 최근 기록 시각(서버 시계). 없으면 null — 동승자 메시지 빈도 제한(C106) */
    fun lastOccurredAt(groupId: String, type: GroupEventType): Instant? = null
}

/** 커밋 뒤 발행되는 Spring 애플리케이션 이벤트 — T15 의 MQTT 발행기가 `@TransactionalEventListener`/`@EventListener` 로 받는다 */
data class GroupEventRecorded(val groupId: String, val event: GroupEvent)

@Component
class DbGroupEventStore(
    private val repo: GroupEventRepository,
    private val publisher: ApplicationEventPublisher,
    private val clock: Clock,
) : GroupEventSink, GroupEventReader {

    override fun record(
        groupId: String,
        type: GroupEventType,
        sourceVehicleId: String?,
        severity: Severity,
        speech: String?,
        payload: JsonObject,
        audience: List<String>?,
    ): GroupEvent {
        val event = GroupEvent(
            eventId = Ids.event(),
            type = type.name,
            sourceVehicleId = sourceVehicleId,
            severity = severity,
            speech = speech,
            payload = payload,
            audience = audience,
        )
        repo.insert(groupId, event, clock.instant())
        // 문구·본문은 개인정보(닉네임·메시지)를 담으므로 로그에는 종류와 ID 만 남긴다 (CLAUDE.md 규칙 8). 대상은 수만(all = 그룹 전체)
        log.info("group event recorded groupId={} type={} eventId={} severity={} audience={}", groupId, type, event.eventId, severity, audience?.size ?: "all")
        afterCommit { publisher.publishEvent(GroupEventRecorded(groupId, event)) }
        return event
    }

    override fun recent(groupId: String, limit: Int): List<GroupEvent> = repo.listRecent(groupId, limit)

    override fun lastOccurredAt(groupId: String, type: GroupEventType): Instant? = repo.lastOccurredAt(groupId, type.name)

    companion object {
        private val log = LoggerFactory.getLogger(DbGroupEventStore::class.java)
    }
}
