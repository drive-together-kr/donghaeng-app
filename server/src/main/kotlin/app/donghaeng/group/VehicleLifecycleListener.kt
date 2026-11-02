package app.donghaeng.group

import app.donghaeng.common.tx.afterCommit
import app.donghaeng.identity.VehicleUnpaired
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * identity 가 발행하는 [VehicleUnpaired] 를 받아 그 차량을 활성 그룹에서 빼고, 커밋 뒤 차량에 cmd `TOKEN_REVOKED` 를 보낸다.
 * `@EventListener` 는 발행 스레드에서 동기 실행되므로 identity 의 해제·이전 트랜잭션 **안에서** 함께 커밋/롤백된다.
 *
 * T33 (docs/03 §1.2·§1.3): 페어링 해제(REVOKED)·소유권 이전(TRANSFERRED) 모두 **이전 vehicleId** 의 cmd 토픽으로 `TOKEN_REVOKED`(QoS 1, retain 없음).
 * 그룹 정리(MEMBER_LEFT·GROUP_ENDED)의 커밋 후 작업보다 뒤에 등록되므로 차량은 그룹 소식을 먼저 받는다. 롤백되면 보내지 않는다.
 */
@Component
class VehicleLifecycleListener(
    private val groups: GroupService,
    private val commands: VehicleCommandPort,
) {

    @EventListener
    fun onUnpaired(event: VehicleUnpaired) {
        val reason = when (event.reason) {
            VehicleUnpaired.Reason.REVOKED -> GroupService.LEFT_REASON_UNPAIRED
            VehicleUnpaired.Reason.TRANSFERRED -> GroupService.LEFT_REASON_TRANSFERRED
        }
        // 소유권 이전이면 옛 그룹에는 옛 별명으로 알린다(C104)
        val groupId = groups.evictVehicle(event.vehicleId, reason, event.previousNickname)
        if (groupId != null) log.info("vehicle evicted from group vehicleId={} groupId={} reason={}", event.vehicleId, groupId, reason)
        // 이미 발급된 차량 access token 은 만료(24h)까지 서명상 유효 — 차량이 스스로 지우게 알린다(그 차가 지금 재페어링 중이면 iat 비교로 무시한다)
        afterCommit { commands.tokenRevoked(event.vehicleId) }
    }

    companion object {
        private val log = LoggerFactory.getLogger(VehicleLifecycleListener::class.java)
    }
}
