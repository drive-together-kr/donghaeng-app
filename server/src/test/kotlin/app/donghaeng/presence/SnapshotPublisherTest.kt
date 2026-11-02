package app.donghaeng.presence

import app.donghaeng.common.config.DhProperties
import app.donghaeng.event.GroupEventRecorded
import app.donghaeng.group.GroupMembershipApi
import app.donghaeng.group.GroupRoster
import app.donghaeng.group.RosterMember
import app.donghaeng.mqtt.MqttPublisher
import app.donghaeng.protocol.GroupEvent
import app.donghaeng.protocol.GroupEventType
import app.donghaeng.protocol.Place
import app.donghaeng.protocol.Severity
import app.donghaeng.protocol.Telemetry
import app.donghaeng.protocol.Topics
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test
import java.time.Clock
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** C071·C109: 그룹 종료와 2 s 스냅샷 틱이 엇갈려도 끝난 그룹의 retained 스냅샷이 남지 않는다 */
class SnapshotPublisherTest {

    private class RecordingPublisher : MqttPublisher {
        /** 발행·삭제를 한 줄로 기록 — 순서를 본다 */
        val log = CopyOnWriteArrayList<String>()
        val expiries = CopyOnWriteArrayList<Long>()
        override val connected = true
        override fun <T> publish(topic: String, type: String, data: T, serializer: KSerializer<T>, qos: Int, retain: Boolean): Boolean {
            log += "publish $topic"; return true
        }
        override fun <T> publishExpiring(topic: String, type: String, data: T, serializer: KSerializer<T>, qos: Int, retain: Boolean, expirySeconds: Long): Boolean {
            expiries += expirySeconds
            return publish(topic, type, data, serializer, qos, retain)
        }
        override fun clearRetained(topic: String): Boolean { log += "clear $topic"; return true }
    }

    private object EmptyStore : PresenceStore {
        override fun recordTelemetry(vehicleId: String, telemetry: Telemetry, tsMs: Long) {}
        override fun recordGeo(groupId: String, vehicleId: String, lat: Double, lon: Double) {}
        override fun recordPresence(vehicleId: String, online: Boolean, tsMs: Long) {}
        override fun stateOf(vehicleId: String): VehicleState? = null
        override fun onlineOf(vehicleId: String): Boolean? = null
    }

    private fun roster(gid: String) = GroupRoster(gid, "그룹", "veh_A", Place(35.0, 127.0, "목적지"), listOf(RosterMember("veh_A", "아빠 차", "#1", "LEADER", 0)))

    /** 명단을 돌려준 직후(= 틱이 명단을 읽은 뒤, 발행 전) [onRead] 를 실행한다 — 그 사이 그룹 종료가 커밋되는 경합을 흉내 */
    private class RacingMembership(private val rosters: List<GroupRoster>) : GroupMembershipApi {
        var onRead: () -> Unit = {}
        override fun activeGroupIdOf(vehicleId: String): String? = null
        override fun rosterOf(groupId: String): GroupRoster? = rosters.firstOrNull { it.groupId == groupId }
        override fun activeRosters(): List<GroupRoster> = rosters.also { onRead() }
    }

    private val props = DhProperties(publicBaseUrl = "http://x", mqtt = DhProperties.Mqtt(uri = "tcp://x", publicUri = "tcp://x"))
    private val builder = SnapshotBuilder(EmptyStore, props, Clock.systemUTC())
    private val publisher = RecordingPublisher()

    private fun ended(gid: String) = GroupEventRecorded(gid, GroupEvent("evt_end", GroupEventType.GROUP_ENDED.name, null, Severity.INFO, null, JsonObject(emptyMap())))

    @Test
    fun `a group that ends between the roster read and the publish is not re-retained after the clear`() {
        val membership = RacingMembership(listOf(roster("grp_END"), roster("grp_LIVE")))
        val snapshots = SnapshotPublisher(membership, builder, publisher)
        snapshots.reclearDelayMs = 60_000 // 이 테스트에서는 두 번째 삭제를 보지 않는다
        membership.onRead = { snapshots.onRecorded(ended("grp_END")) }

        assertEquals(1, snapshots.publishAll())
        assertEquals(listOf("clear ${Topics.groupState("grp_END")}", "publish ${Topics.groupState("grp_LIVE")}"), publisher.log)
        // 종료 직후 다음 틱(명단 캐시가 아직 끝난 그룹을 들고 있어도)도 건너뛴다
        membership.onRead = {}
        snapshots.publishAll()
        assertTrue(publisher.log.none { it == "publish ${Topics.groupState("grp_END")}" })
    }

    @Test
    fun `snapshots carry a message expiry and the retained clear is repeated one tick later`() {
        val snapshots = SnapshotPublisher(RacingMembership(listOf(roster("grp_LIVE"))), builder, publisher)
        snapshots.reclearDelayMs = 50
        snapshots.publishAll()
        assertEquals(listOf(SnapshotPublisher.STATE_EXPIRY_SECONDS), publisher.expiries, "retained 스냅샷은 만료가 있다(놓친 삭제·서버 중단에도 브로커가 지운다)")

        snapshots.onRecorded(ended("grp_X"))
        val deadline = System.currentTimeMillis() + 5_000
        while (publisher.log.count { it == "clear ${Topics.groupState("grp_X")}" } < 2 && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(2, publisher.log.count { it == "clear ${Topics.groupState("grp_X")}" }, "한 틱 뒤 한 번 더 지운다")
        snapshots.shutdown()
    }
}
