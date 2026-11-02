package app.donghaeng.routesync

import app.donghaeng.KRedisContainer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * C076·C110: 실제 Redis 의 [RedisProposalStore] 가 메모리 대역(RouteSyncServiceTest)과 같은 계약을 지키는지 —
 * 모르는(없거나 만료된) 제안의 ACK 는 키를 만들지 않고 null, 있는 제안에만 응답을 적고 TTL 은 save 때 것 그대로.
 */
@Testcontainers(disabledWithoutDocker = true)
class RedisProposalStoreIT {

    companion object {
        @Container
        @JvmStatic
        val redis = KRedisContainer(DockerImageName.parse("redis:7")).withExposedPorts(6379)

        private lateinit var factory: LettuceConnectionFactory
        private lateinit var template: StringRedisTemplate

        @BeforeAll
        @JvmStatic
        fun connect() {
            factory = LettuceConnectionFactory(RedisStandaloneConfiguration(redis.host, redis.getMappedPort(6379))).apply { afterPropertiesSet() }
            template = StringRedisTemplate(factory).apply { afterPropertiesSet() }
        }

        @AfterAll
        @JvmStatic
        fun close() {
            factory.destroy()
        }
    }

    @Test
    fun `an ack for an unknown proposal creates no key and returns null`() {
        val store = RedisProposalStore(template)
        assertNull(store.recordAck("prp_UNKNOWN0000000000000000000", "veh_Z", true))
        assertFalse(template.hasKey(RedisProposalStore.key("prp_UNKNOWN0000000000000000000")), "예전에는 HSET 이 먼저라 15분짜리 키가 생겼다")
        assertNull(store.find("prp_UNKNOWN0000000000000000000"))
    }

    @Test
    fun `acks are recorded only on a saved proposal and keep its original TTL`() {
        val store = RedisProposalStore(template)
        store.save(ProposalRecord("prp_SAVED00000000000000000000", "grp_1", "veh_A", listOf("veh_B", "veh_C"), 1_000))
        val ttlBefore = template.getExpire(RedisProposalStore.key("prp_SAVED00000000000000000000"))
        val after = assertNotNull(store.recordAck("prp_SAVED00000000000000000000", "veh_B", true))
        assertEquals(mapOf("veh_B" to true), after.acks)
        assertEquals(listOf("veh_B", "veh_C"), after.targets)
        assertFalse(after.allAnswered)
        val ttlAfter = template.getExpire(RedisProposalStore.key("prp_SAVED00000000000000000000"))
        assertTrue(ttlAfter in 1..ttlBefore, "ACK 가 TTL 을 늘리지 않는다: before=$ttlBefore after=$ttlAfter")
        assertTrue(store.recordAck("prp_SAVED00000000000000000000", "veh_C", false)!!.allAnswered)
    }
}
