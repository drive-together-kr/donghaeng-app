package app.donghaeng.group

import app.donghaeng.protocol.GroupEnded
import app.donghaeng.protocol.GroupJoined

/**
 * 차량별 CMD(`dh/v1/vehicle/{vid}/cmd`, docs/03 §5.5) 를 내보내는 출구. group 모듈은 이 인터페이스만 부른다(docs/06 §2).
 * 호출은 항상 트랜잭션 커밋 뒤에 일어난다. 구현은 `mqtt` 패키지의 `MqttVehicleCommandPort`(T15). 모두 QoS 1, retain 없음.
 */
interface VehicleCommandPort {

    /** 합류 완료 — 차량 B 가 그룹 토픽을 구독하게 한다 */
    fun groupJoined(vehicleId: String, cmd: GroupJoined)

    /** 그룹 종료 — 종료 시점의 모든 멤버에게 */
    fun groupEnded(vehicleId: String, cmd: GroupEnded)

    /**
     * 차량 토큰 폐기(T33) — 페어링 해제(`DELETE /me/vehicles/{id}`)·소유권 이전(다른 계정이 같은 차를 승인) 뒤 **이전 vehicleId** 에.
     * 이미 발급된 차량 access token 은 만료(24h)까지 서명상 유효하므로, 차량 앱이 이 cmd 를 받고 스스로 자격 증명을 지운다.
     * retain 하면 재페어링한 같은 차가 다시 받으므로 retain 금지(차량 앱은 토큰 iat 이전에 발행된 것을 무시한다).
     */
    fun tokenRevoked(vehicleId: String)
}
