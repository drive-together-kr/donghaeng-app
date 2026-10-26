package app.donghaeng.protocol

import kotlinx.serialization.Serializable

/**
 * 모든 MQTT 메시지의 공통 봉투 (docs/03 §5).
 *
 * ```json
 * { "v": 1, "type": "TELEMETRY", "vid": "veh_01H...", "ts": 1793000000000, "seq": 1024, "data": { } }
 * ```
 *
 * @param v    스키마 버전. 현재 1.
 * @param type 메시지 종류. [MessageTypes] 상수, [VehicleEventType] 이름, [CmdType] 이름 중 하나.
 * @param vid  발신(또는 대상) 차량 ID. 서버가 그룹 토픽에 발행할 때는 서버 클라이언트 ID.
 * @param ts   epoch milliseconds.
 * @param seq  발신자 단조 증가 시퀀스(재연결 후 중복 제거용).
 * @param data 메시지 본문. 타입별 페이로드 클래스 또는 [kotlinx.serialization.json.JsonElement].
 */
@Serializable
data class Envelope<T>(
    val v: Int = 1,
    val type: String,
    val vid: String,
    val ts: Long,
    val seq: Long,
    val data: T,
)
