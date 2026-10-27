package app.donghaeng.group

import app.donghaeng.protocol.GroupEnded
import app.donghaeng.protocol.GroupJoined

/**
 * 차량별 CMD(`dh/v1/vehicle/{vid}/cmd`, docs/03 §5.5) 를 내보내는 출구. group 모듈은 이 인터페이스만 부른다(docs/06 §2).
 * 호출은 항상 트랜잭션 커밋 뒤에 일어난다. 구현은 `mqtt` 패키지의 `MqttVehicleCommandPort`(T15).
 */
interface VehicleCommandPort {

    /** 합류 완료 — 차량 B 가 그룹 토픽을 구독하게 한다 */
    fun groupJoined(vehicleId: String, cmd: GroupJoined)

    /** 그룹 종료 — 종료 시점의 모든 멤버에게 */
    fun groupEnded(vehicleId: String, cmd: GroupEnded)
}
