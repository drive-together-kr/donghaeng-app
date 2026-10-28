package app.donghaeng.companion.sdk

import app.donghaeng.protocol.GroupEvent
import app.donghaeng.protocol.MemberState

/**
 * 계정에 연결된 내 차 한 대 (`GET /me/vehicles` 항목).
 *
 * @param vehicleId 서버가 발급한 차량 ID(`veh_...`)
 * @param nickname 차 별명(예: "아빠 차")
 * @param brand 제조사 코드(`HYUNDAI`, `KIA`, `GENESIS`)
 * @param model 모델명 코드(`GRANDEUR`, `EV9` …)
 * @param isPrimary 대표 차량 여부. [DongHaeng.joinGroup] 에 vehicleId 를 생략하면 이 차로 합류한다
 */
public data class MyVehicle(
    public val vehicleId: String,
    public val nickname: String,
    public val brand: String,
    public val model: String,
    public val isPrimary: Boolean,
)

/** 그룹 안에서의 역할 */
public enum class MemberRole {
    /** 방장(선두차). 경로 변경·그룹 종료 권한 */
    LEADER,

    /** 일반 멤버 */
    MEMBER,
}

/**
 * 그룹 멤버 한 대 (docs/03 §2.4 Member 객체의 요약).
 *
 * @param color 화면 표시 색(`#RRGGBB`)
 */
public data class GroupMember(
    public val vehicleId: String,
    public val nickname: String,
    public val color: String,
    public val role: MemberRole,
)

/**
 * [DongHaeng.joinGroup] 결과 — 합류한 그룹과 현재 멤버.
 *
 * @param name 그룹 이름(예: "추석 귀성길")
 */
public data class GroupJoinResult(
    public val groupId: String,
    public val name: String,
    public val members: List<GroupMember>,
)

/**
 * [DongHaeng.pairVehicle] 결과 — 계정에 새로 연결된 차.
 */
public data class PairResult(
    public val vehicleId: String,
    public val nickname: String,
)

/**
 * [DongHaeng.observeGroup] 가 내보내는 그룹 스냅샷.
 *
 * 멤버 상태와 이벤트는 공용 스키마(`app.donghaeng.protocol`)의 타입을 그대로 쓴다 — 차량 앱·서버와 같은 정의다.
 *
 * **무전(F16, `type == "VOICE_MESSAGE"`)**: payload 는 `app.donghaeng.protocol.VoiceMessagePayload`
 * (`voiceId, durationMs, mime, sizeBytes, expiresAtMs`, `DhJson.payloadAs` 로 해석)이고, `sourceVehicleId` 가 보낸 차다.
 * 호스트 앱은 동행 토큰을 갖지 않으므로 모듈이 오디오를 미리 내려받아(앱 전용 캐시) payload 에 두 키를 **덧붙인다**:
 * - `localAudioStatus`: `READY`(재생 가능) · `PENDING`(받는 중 — 다음 스냅샷에서 바뀐다) · `EXPIRED`(서버 보관 끝·그룹 종료) · `FAILED`(받을 수 없음)
 * - `localAudioPath`: `READY` 일 때만, 내려받은 파일의 절대 경로(`MediaPlayer.setDataSource(path)` 로 재생).
 *   모듈이 만료(서버 시계)·그룹 종료를 보면 바로 지우고, 더 관찰하지 않는 그룹의 파일은 받은 지 25시간 지나 정리한다(docs/07 §3.1)
 *
 * 상태는 모듈이 서버 시계로 정한 것이므로 호스트가 폰 시계로 다시 만료를 판정하지 않는다. 파일 작업은 모듈이 IO 스레드에서 하므로
 * 이 흐름은 메인 스레드에서 수집해도 된다.
 *
 * 공개 함수는 늘리지 않았다(docs/07 §3·§3.1). 재생(오디오 포커스·출력)은 호스트 앱 몫이다.
 *
 * @param members 각 차의 위치·속도·ETA·잔량·상태 (서버 STATE 와 동일 형태)
 * @param events 최근 그룹 이벤트, 오래된 것부터 (합류·저잔량·동승자 메시지·무전 등)
 */
public data class GroupSnapshot(
    public val groupId: String,
    public val members: List<MemberState>,
    public val events: List<GroupEvent>,
)
