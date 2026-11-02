package app.donghaeng.mqtt

import app.donghaeng.protocol.CmdType
import app.donghaeng.protocol.DhJson
import app.donghaeng.protocol.Envelope
import app.donghaeng.protocol.GroupEnded
import app.donghaeng.protocol.MessageTypes
import app.donghaeng.protocol.Telemetry
import app.donghaeng.protocol.LocSource
import app.donghaeng.protocol.Topics
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.serialization.json.JsonElement
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Clock
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** C111: 서버 자신의 cmd 가 공유 구독으로 되돌아와도 '위조 의심' WARN 을 남기지 않는다. 실제 위조(봉투 vid 불일치)는 그대로 WARN */
class TopicRouterTest {

    private class Recording(override val kind: Topics.VehicleTopicKind) : MqttInboundHandler {
        val got = mutableListOf<String>()
        override fun handle(vehicleId: String, envelope: Envelope<JsonElement>) { got += vehicleId }
    }

    private val telemetry = Recording(Topics.VehicleTopicKind.TELEMETRY)
    private val cmd = Recording(Topics.VehicleTopicKind.CMD)
    private val router = TopicRouter(listOf(telemetry, cmd), emptyList(), Clock.systemUTC())
    private val appender = ListAppender<ILoggingEvent>()
    private val logger = LoggerFactory.getLogger(TopicRouter::class.java) as Logger

    @BeforeEach
    fun attach() {
        appender.start()
        logger.addAppender(appender)
    }

    @AfterEach
    fun detach() {
        logger.detachAppender(appender)
    }

    private fun warnings() = appender.list.filter { it.level.isGreaterOrEqual(Level.WARN) }

    @Test
    fun `the server's own cmd echoed by the shared subscription is dropped silently`() {
        val own = DhJson.encode(Envelope(type = CmdType.GROUP_ENDED.name, vid = "srv_01HSERVER", ts = 1, seq = 1, data = GroupEnded("grp_1", "LEADER_ENDED")), GroupEnded.serializer())
        router.route(Topics.vehicleCmd("veh_A"), own.toByteArray())
        assertEquals(emptyList(), warnings().map { it.formattedMessage })
        assertTrue(cmd.got.isEmpty())
    }

    @Test
    fun `a telemetry envelope whose vid differs from the topic is still dropped with a WARN`() {
        val spoof = DhJson.encode(
            Envelope(type = MessageTypes.TELEMETRY, vid = "veh_SPOOF", ts = 1, seq = 1, data = Telemetry(lat = 37.5, lon = 127.0, heading = 0f, speedKph = 0f, locSource = LocSource.FAKE)),
            Telemetry.serializer(),
        )
        router.route(Topics.vehicleTelemetry("veh_A"), spoof.toByteArray())
        assertTrue(telemetry.got.isEmpty())
        assertTrue(warnings().any { it.formattedMessage.contains("envelope vid mismatch") }, appender.list.toString())
    }
}
