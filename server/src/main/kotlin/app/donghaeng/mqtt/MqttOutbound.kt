package app.donghaeng.mqtt

import app.donghaeng.event.GroupEventRecorded
import app.donghaeng.group.VehicleCommandPort
import app.donghaeng.protocol.CmdType
import app.donghaeng.protocol.GroupEnded
import app.donghaeng.protocol.GroupEvent
import app.donghaeng.protocol.GroupJoined
import app.donghaeng.protocol.MessageTypes
import app.donghaeng.protocol.Topics
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * group 모듈의 cmd 출구 구현 — `dh/v1/vehicle/{vid}/cmd` 에 QoS 1 로 발행한다 (docs/03 §4·§5.5).
 * group 은 커밋 뒤에만 부르므로 여기서는 바로 발행한다.
 */
@Component
class MqttVehicleCommandPort(private val publisher: MqttPublisher) : VehicleCommandPort {

    override fun groupJoined(vehicleId: String, cmd: GroupJoined) {
        val sent = publisher.publish(Topics.vehicleCmd(vehicleId), CmdType.GROUP_JOINED.name, cmd, GroupJoined.serializer())
        log.info("cmd GROUP_JOINED vehicleId={} groupId={} sent={}", vehicleId, cmd.groupId, sent)
    }

    override fun groupEnded(vehicleId: String, cmd: GroupEnded) {
        val sent = publisher.publish(Topics.vehicleCmd(vehicleId), CmdType.GROUP_ENDED.name, cmd, GroupEnded.serializer())
        log.info("cmd GROUP_ENDED vehicleId={} groupId={} reason={} sent={}", vehicleId, cmd.groupId, cmd.reason, sent)
    }

    companion object {
        private val log = LoggerFactory.getLogger(MqttVehicleCommandPort::class.java)
    }
}

/**
 * 저장된 그룹 이벤트를 `dh/v1/group/{gid}/event` 로 팬아웃한다 (docs/03 §5.4, 봉투 type = `EVENT`).
 * [GroupEventRecorded] 는 event 모듈이 커밋 뒤에 발행한다.
 */
@Component
class GroupEventMqttFanout(private val publisher: MqttPublisher) {

    @EventListener
    fun onRecorded(e: GroupEventRecorded) {
        val sent = publisher.publish(Topics.groupEvent(e.groupId), MessageTypes.EVENT, e.event, GroupEvent.serializer())
        log.info("group event fan-out groupId={} type={} eventId={} sent={}", e.groupId, e.event.type, e.event.eventId, sent)
    }

    companion object {
        private val log = LoggerFactory.getLogger(GroupEventMqttFanout::class.java)
    }
}
