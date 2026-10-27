package app.donghaeng.group

import app.donghaeng.identity.VehicleUnpaired
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * identity 가 발행하는 [VehicleUnpaired] 를 받아 그 차량을 활성 그룹에서 뺀다.
 * `@EventListener` 는 발행 스레드에서 동기 실행되므로 identity 의 해제·이전 트랜잭션 **안에서** 함께 커밋/롤백된다.
 */
@Component
class VehicleLifecycleListener(private val groups: GroupService) {

    @EventListener
    fun onUnpaired(event: VehicleUnpaired) {
        val reason = when (event.reason) {
            VehicleUnpaired.Reason.REVOKED -> GroupService.LEFT_REASON_UNPAIRED
            VehicleUnpaired.Reason.TRANSFERRED -> GroupService.LEFT_REASON_TRANSFERRED
        }
        val groupId = groups.evictVehicle(event.vehicleId, reason)
        if (groupId != null) log.info("vehicle evicted from group vehicleId={} groupId={} reason={}", event.vehicleId, groupId, reason)
    }

    companion object {
        private val log = LoggerFactory.getLogger(VehicleLifecycleListener::class.java)
    }
}
