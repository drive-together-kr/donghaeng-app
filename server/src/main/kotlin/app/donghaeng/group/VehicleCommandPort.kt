package app.donghaeng.group

import app.donghaeng.protocol.GroupEnded
import app.donghaeng.protocol.GroupJoined
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 차량별 CMD(`dh/v1/vehicle/{vid}/cmd`, docs/03 §5.5) 를 내보내는 출구. group 모듈은 이 인터페이스만 부른다(docs/06 §2).
 * 호출은 항상 트랜잭션 커밋 뒤에 일어난다.
 *
 * T14: 로그만 남기는 구현. T15 의 `mqtt` 패키지가 실제 발행 구현으로 바꿔 끼운다.
 */
interface VehicleCommandPort {

    /** 합류 완료 — 차량 B 가 그룹 토픽을 구독하게 한다 */
    fun groupJoined(vehicleId: String, cmd: GroupJoined)

    /** 그룹 종료 — 종료 시점의 모든 멤버에게 */
    fun groupEnded(vehicleId: String, cmd: GroupEnded)
}

@Component
class LogOnlyVehicleCommandPort : VehicleCommandPort {

    override fun groupJoined(vehicleId: String, cmd: GroupJoined) {
        log.info("cmd GROUP_JOINED (not published until T15) vehicleId={} groupId={}", vehicleId, cmd.groupId)
    }

    override fun groupEnded(vehicleId: String, cmd: GroupEnded) {
        log.info("cmd GROUP_ENDED (not published until T15) vehicleId={} groupId={} reason={}", vehicleId, cmd.groupId, cmd.reason)
    }

    companion object {
        private val log = LoggerFactory.getLogger(LogOnlyVehicleCommandPort::class.java)
    }
}
