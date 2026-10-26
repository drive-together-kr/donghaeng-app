package app.donghaeng

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Kotlin 에서 Testcontainers 의 self-type 제네릭(SELF)을 쓰기 위한 서브클래스 */
class KPostgresContainer(image: String) : PostgreSQLContainer<KPostgresContainer>(image)
class KRedisContainer(image: DockerImageName) : GenericContainer<KRedisContainer>(image)

/**
 * 통합 테스트: 실제 PostgreSQL 16 + Redis 7 컨테이너로 부팅해 헬스체크와 Flyway V1 적용을 확인한다.
 * Docker 가 없으면 건너뛴다 (disabledWithoutDocker).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class HealthEndpointIT {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = KPostgresContainer("postgres:16")

        @Container
        @ServiceConnection(name = "redis")
        @JvmStatic
        val redis = KRedisContainer(DockerImageName.parse("redis:7")).withExposedPorts(6379)
    }

    @Autowired
    lateinit var rest: TestRestTemplate

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Test
    fun `actuator health is UP without authentication`() {
        val res = rest.getForEntity("/actuator/health", String::class.java)
        assertEquals(HttpStatus.OK, res.statusCode)
        assertTrue(res.body.orEmpty().contains("UP"), res.body)
    }

    @Test
    fun `flyway V1 migration was applied`() {
        val applied = jdbc.queryForObject(
            "select count(*) from flyway_schema_history where success = true",
            Long::class.javaObjectType,
        )!!
        assertTrue(applied >= 1, "flyway_schema_history success rows = $applied")

        val travelGroup = jdbc.queryForObject(
            "select count(*) from information_schema.tables where table_schema = 'public' and table_name = 'travel_group'",
            Long::class.javaObjectType,
        )!!
        assertEquals(1L, travelGroup, "travel_group table should exist")
    }
}
