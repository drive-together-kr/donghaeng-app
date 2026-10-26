package app.donghaeng.protocol

import kotlinx.serialization.Serializable

/**
 * ACK 본문 (차량 → 서버, `dh/v1/vehicle/{vid}/ack`) — docs/03 §5.6.
 * 경로 따라가기·휴게소 제안에 대한 수락/거절.
 */
@Serializable
data class Ack(
    val proposalId: String,
    val accepted: Boolean,
)

/**
 * presence 본문 (`dh/v1/vehicle/{vid}/presence`) — docs/03 §4.
 *
 * 브로커 LWT 로 등록되는 메시지는 봉투 없이 `{"online":false}` 그대로이다.
 * 차량 앱이 정상 접속/종료 시 직접 발행할 때도 같은 형태를 쓴다.
 */
@Serializable
data class Presence(
    val online: Boolean,
)
