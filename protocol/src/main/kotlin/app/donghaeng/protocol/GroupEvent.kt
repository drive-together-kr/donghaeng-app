package app.donghaeng.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * 그룹 EVENT 본문 (서버 → 그룹, `dh/v1/group/{gid}/event`) — docs/03 §5.4.
 *
 * [type] 은 [GroupEventType] 의 이름이지만, 서버가 먼저 새 종류를 추가해도 구버전 차량 앱이
 * 디코딩에 실패하지 않도록 문자열로 둔다. enum 으로 해석하려면 [eventType] 을 쓴다.
 *
 * @param sourceVehicleId 이벤트를 일으킨 차량. 서버 생성 이벤트(투표 결과 등)는 null
 * @param speech          서버가 만든 한국어 음성 문구. 음성 생략(중복 억제) 시 null
 * @param payload         원본 이벤트 본문(차량 이벤트면 §5.2 의 data 그대로). [DhJson.payloadAs] 로 해석
 * @param audience        알릴 차량(vehicleId) 목록. **null(필드 없음) = 그룹 전체**, 값이 있으면 그 차량만, 빈 목록 = 아무도 알리지 않음(서버 기록·REST 원문에만 — 어느 화면에도 안 나온다).
 *                        지금은 `HARD_BRAKE` 만 채운다(출처보다 경로상 뒤에 있는 차 — [EventAudience]). 옛 클라이언트는 모르는 키라 무시한다
 */
@Serializable
data class GroupEvent(
    val eventId: String,
    val type: String,
    val sourceVehicleId: String?,
    val severity: Severity,
    val speech: String?,
    val payload: JsonObject,
    val audience: List<String>? = null,
) {
    /** [type] 을 enum 으로 해석. 모르는 종류면 null (호출 쪽에서 무시하거나 로그만 남긴다) */
    val eventType: GroupEventType?
        get() = GroupEventType.entries.firstOrNull { it.name == type }
}

/** 이벤트 심각도 — 음성 안내 우선순위에 쓴다 */
@Serializable
enum class Severity { INFO, WARN, ALERT }

/**
 * 서버가 발행하는 그룹 이벤트 종류 = 차량 이벤트 전부([VehicleEventType]) + 서버 생성 이벤트.
 */
@Serializable
enum class GroupEventType {
    // ---- 차량 이벤트 전달 (VehicleEventType 과 이름 동일) ----
    LOW_ENERGY,
    HARD_BRAKE,
    HAZARD_ON,
    AIRBAG_DEPLOYED,
    ROUTE_CHANGED,
    WAYPOINT_ARRIVED,
    DESTINATION_ARRIVED,
    STOPPED,
    RESUMED,
    PRESET_MESSAGE,

    // ---- 서버 생성 이벤트 ----
    /** 멤버 합류 */
    MEMBER_JOINED,

    /** 멤버 이탈(나가기) */
    MEMBER_LEFT,

    /** 방장 변경(위임 또는 방장 이탈 시 자동 승계). payload `{ leaderVehicleId, previousLeaderVehicleId }` */
    LEADER_CHANGED,

    /** 위치 미수신 60초 이상 */
    MEMBER_OFFLINE,

    /** 휴게소 제안(규칙 엔진) */
    REST_STOP_PROPOSAL,

    /** 휴게소 투표 결과 */
    REST_STOP_VOTE_RESULT,

    /** 선두 경로 이탈 감지 */
    DEVIATION,

    /** companion 모듈(공식 앱의 동행 메뉴) 동승자 메시지 */
    PASSENGER_MESSAGE,

    /** F16 무전(녹음형 PTT) — 차량이 `POST /groups/{id}/voice` 로 올린 녹음. payload [VoiceMessagePayload], sourceVehicleId = 보낸 차 */
    VOICE_MESSAGE,

    /** 그룹 종료 */
    GROUP_ENDED,
    ;

    /** 차량에서 올라온 이벤트를 그대로 전달하는 종류인지 */
    val isVehicleEvent: Boolean
        get() = VehicleEventType.entries.any { it.name == name }
}

/**
 * `VOICE_MESSAGE` 그룹 이벤트 payload (F16 무전, docs/03 §2.6·§5.4).
 * 받는 쪽은 `GET /groups/{groupId}/voice/{voiceId}` 로 오디오를 내려받는다(멤버만, [expiresAtMs] 까지 — 그룹이 끝나면 즉시 지운다).
 */
@Serializable
data class VoiceMessagePayload(
    val voiceId: String,
    /** 녹음 길이(ms, 서버가 상한 16 000 으로 검증) */
    val durationMs: Int,
    /** `audio/mp4`(AAC-LC m4a, 기본) · `audio/aac` · `audio/wav`(디버그 주입) */
    val mime: String,
    val sizeBytes: Int,
    /** 서버 보관 만료(epoch ms) — 업로드 + 24시간 */
    val expiresAtMs: Long,
)
