package app.donghaeng.common.idempotency

import app.donghaeng.common.api.ApiException
import app.donghaeng.common.jdbc.toDb
import app.donghaeng.protocol.DhJson
import app.donghaeng.protocol.rest.ApiHeaders
import app.donghaeng.protocol.rest.ErrorCodes
import app.donghaeng.protocol.rest.IdempotencyKey
import kotlinx.serialization.KSerializer
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** 저장된 첫 성공 응답 (상태 코드 + JSON 본문) */
data class StoredResponse(val status: Int, val body: String)

/**
 * `Idempotency-Key`(docs/03 §2.3·§2.6) 저장소 — (호출자, 경로, 키) → 첫 성공 응답, 24시간.
 * 모든 메서드는 호출자의 트랜잭션에 참여한다(응답 저장이 실제 처리와 함께 커밋·롤백되도록).
 */
interface IdempotencyStore {

    /**
     * 같은 (호출자, 경로, 키) 요청을 **호출자 트랜잭션이 끝날 때까지** 줄 세운 뒤(동시에 온 재시도는 첫 요청의 커밋을 기다린다),
     * [now] 에 만료되지 않은 저장 응답이 있으면 돌려준다. 반드시 `@Transactional` 안에서 부른다.
     */
    fun lockAndFind(callerId: String, path: String, key: String, now: Instant): StoredResponse?

    fun save(callerId: String, path: String, key: String, response: StoredResponse, now: Instant, ttl: Duration)

    /** 정기 정리 — [now] 까지 만료된 행. 지운 수 */
    fun deleteExpired(now: Instant): Int
}

/**
 * 재시도 안전 실행 (계약 3, docs/03 §2.3·§2.6).
 *
 * - 키가 없으면 [run] 은 그냥 처리한다(지금까지와 같다).
 * - 키가 있으면 같은 (호출자, 경로, 키) 를 잠근 뒤 저장된 응답이 있으면 **처리 없이**(저장·팬아웃 없음) 그 본문을 돌려주고,
 *   없으면 처리한 뒤 결과를 같은 트랜잭션에 저장한다. 처리가 예외(4xx·5xx)로 끝나면 롤백되어 아무것도 기억하지 않는다 — 같은 키로 다시 보내면 다시 처리한다.
 * - 본문은 첫 응답 그대로다(재시도 본문이 달라도 첫 응답). 키 원문은 로그에 끝 6자만 남긴다.
 */
@Component
class Idempotency(
    private val store: IdempotencyStore,
    private val clock: Clock,
) {
    fun <T> run(callerId: String, path: String, key: String?, status: Int, serializer: KSerializer<T>, block: () -> T): T {
        if (key == null) return block()
        val now = clock.instant()
        store.lockAndFind(callerId, path, key, now)?.let { stored ->
            log.info("idempotent replay path={} caller={} key=…{} status={}", path, callerId, key.takeLast(6), stored.status)
            return DhJson.json.decodeFromString(serializer, stored.body)
        }
        val result = block()
        store.save(callerId, path, key, StoredResponse(status, DhJson.json.encodeToString(serializer, result)), now, TTL)
        return result
    }

    companion object {
        private val log = LoggerFactory.getLogger(Idempotency::class.java)

        /** 응답을 기억하는 기간 */
        val TTL: Duration = Duration.ofHours(24)

        /**
         * 헤더 값 → 키. 없거나 비었으면 null(키 없이 처리), 형식([IdempotencyKey.isValid])이 틀리면 400 `VALIDATION_FAILED`.
         */
        fun parseKey(raw: String?): String? {
            val key = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (!IdempotencyKey.isValid(key)) {
                throw ApiException(
                    HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_FAILED,
                    "요청 값이 올바르지 않아요. (${ApiHeaders.IDEMPOTENCY_KEY}: 8~64자 영문·숫자·_·- 여야 해요)",
                )
            }
            return key
        }
    }
}

@Repository
class JdbcIdempotencyStore(private val jdbc: NamedParameterJdbcTemplate) : IdempotencyStore {

    override fun lockAndFind(callerId: String, path: String, key: String, now: Instant): StoredResponse? {
        // 두 키(int, int) 형식 — group 의 차량 잠금(한 키)·무전 빈도 제한(1616)과 키 공간이 겹치지 않는다
        jdbc.queryForObject(
            "SELECT pg_advisory_xact_lock(:ns, hashtext(:lockKey))",
            // 구분자는 공백 — 호출자 ID·경로·키에는 공백이 없다(PostgreSQL text 는 NUL 을 담지 못한다)
            mapOf("ns" to LOCK_NAMESPACE, "lockKey" to "$callerId $path $key"),
            Any::class.java,
        )
        return jdbc.query(
            """
            SELECT status, body FROM idempotency_record
             WHERE caller_id = :caller AND request_path = :path AND idem_key = :key AND expires_at > :now
            """.trimIndent(),
            mapOf("caller" to callerId, "path" to path, "key" to key, "now" to now.toDb()),
        ) { rs, _ -> StoredResponse(rs.getInt("status"), rs.getString("body")) }.firstOrNull()
    }

    override fun save(callerId: String, path: String, key: String, response: StoredResponse, now: Instant, ttl: Duration) {
        // 만료된 옛 행이 남아 있으면 덮어쓴다(같은 키를 24시간 뒤 다시 쓰는 경우)
        jdbc.update(
            """
            INSERT INTO idempotency_record (caller_id, request_path, idem_key, status, body, created_at, expires_at)
            VALUES (:caller, :path, :key, :status, :body, :now, :expiresAt)
            ON CONFLICT (caller_id, request_path, idem_key) DO UPDATE
               SET status = EXCLUDED.status, body = EXCLUDED.body, created_at = EXCLUDED.created_at, expires_at = EXCLUDED.expires_at
            """.trimIndent(),
            mapOf(
                "caller" to callerId,
                "path" to path,
                "key" to key,
                "status" to response.status,
                "body" to response.body,
                "now" to now.toDb(),
                "expiresAt" to now.plus(ttl).toDb(),
            ),
        )
    }

    override fun deleteExpired(now: Instant): Int =
        jdbc.update("DELETE FROM idempotency_record WHERE expires_at <= :now", mapOf("now" to now.toDb()))

    internal companion object {
        /** advisory lock 두 키 형식의 첫 키 — 재시도 안전 키 전용 */
        const val LOCK_NAMESPACE = 1717
    }
}

/** 만료된 재시도 기록 정리 — `dh.retention.interval-ms`(기본 1시간)마다 */
@Component
class IdempotencyRetention(
    private val store: IdempotencyStore,
    private val clock: Clock,
) {
    @Scheduled(fixedDelayString = "\${dh.retention.interval-ms:3600000}", initialDelayString = "\${dh.retention.initial-delay-ms:120000}")
    fun purgeExpired(): Int =
        try {
            store.deleteExpired(clock.instant()).also { if (it > 0) log.info("idempotency records purged count={}", it) }
        } catch (e: Exception) {
            log.warn("idempotency purge failed cause={}", e.javaClass.simpleName)
            0
        }

    companion object {
        private val log = LoggerFactory.getLogger(IdempotencyRetention::class.java)
    }
}
