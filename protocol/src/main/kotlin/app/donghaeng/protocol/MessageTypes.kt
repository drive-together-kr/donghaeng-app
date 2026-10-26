package app.donghaeng.protocol

/**
 * [Envelope.type] 에 들어가는 고정 메시지 종류 (docs/03 §4~§5).
 *
 * - 차량 EVENT 는 [VehicleEventType] 의 이름을, CMD 는 [CmdType] 의 이름을 그대로 `type` 으로 쓴다.
 * - 그룹 EVENT(서버 → 그룹)는 봉투 `type` 이 [EVENT] 이고 세부 종류는 [GroupEvent.type] 에 있다.
 */
object MessageTypes {
    /** 차량 → 서버, 1Hz 위치·속도·ETA·잔량 ([Telemetry]) */
    const val TELEMETRY = "TELEMETRY"

    /** 서버 → 그룹, 2s 주기 스냅샷 ([GroupState]) */
    const val STATE = "STATE"

    /** 서버 → 그룹, 그룹 이벤트 ([GroupEvent]) */
    const val EVENT = "EVENT"

    /** 차량 → 서버, 제안 응답 ([Ack]) */
    const val ACK = "ACK"

    /** 브로커 LWT / 차량 접속 상태 ([Presence]) */
    const val PRESENCE = "PRESENCE"
}
